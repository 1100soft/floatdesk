#!/usr/bin/env python3
"""Run reproducible, bounded-memory checks without starting the production app/service."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "client/android-xr"


def kotlin_lib(explicit: str | None) -> Path:
    if explicit:
        candidates = [Path(explicit)]
    else:
        wrapper = (CLIENT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        version = re.search(r"gradle-([\d.]+)-", wrapper).group(1)
        caches = {
            Path.home() / ".gradle",
            Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")),
        }
        candidates = [
            p
            for cache in caches
            for p in cache.glob(
                f"wrapper/dists/gradle-{version}-*/*/gradle-{version}/lib"
            )
        ]
    for path in candidates:
        if list(path.glob("kotlin-compiler-embeddable-*.jar")) and list(
            path.glob("kotlin-stdlib-*.jar")
        ):
            return path
    raise SystemExit(
        "Kotlin compiler cache not found; pass --gradle-lib /path/to/gradle/lib"
    )


def checks(groups: list[str], lib: str | None):
    if "rust" in groups:
        yield "rust-format", ["cargo", "fmt", "--all", "--", "--check"]
        yield "rust-tests", ["cargo", "test", "--offline", "--workspace"]
        yield (
            "rust-clippy",
            [
                "cargo",
                "clippy",
                "--offline",
                "--workspace",
                "--all-targets",
                "--",
                "-D",
                "warnings",
            ],
        )
    if "python" in groups:
        yield "python-lint", ["ruff", "check", "scripts"]
        yield "python-format", ["ruff", "format", "--check", "scripts"]
    if "jvm" in groups:
        library = kotlin_lib(lib)
        for path in sorted((CLIENT / "checks").glob("*.py")):
            if path.stem not in {"production_sources", "video_compositor_device"}:
                yield f"jvm-{path.stem}", [sys.executable, str(path), str(library)]
    if "android" in groups:
        yield (
            "android-build-lint",
            [
                str(CLIENT / "gradlew"),
                "-p",
                str(CLIENT),
                "--offline",
                "--max-workers=2",
                "-Dorg.gradle.jvmargs=-Xmx512m",
                "-Pkotlin.daemon.jvmargs=-Xmx512m",
                ":app:assembleDebug",
                ":app:lintDebug",
            ],
        )
    if "integration" in groups:
        yield (
            "host-release",
            ["cargo", "build", "--offline", "--release", "-p", "floatdesk"],
        )
        for name in (
            "check-gaze-focus",
            "check-display-worker",
            "check-render-pause",
            "check-tray-polling",
            "check-serve-existing",
            "check-virtual-screen-power",
            "check-viewed-detail",
        ):
            yield name, [sys.executable, str(ROOT / "scripts" / f"{name}.py")]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--group",
        action="append",
        choices=["rust", "python", "jvm", "integration", "android"],
        help="Repeat to select groups; default: rust, python and jvm",
    )
    parser.add_argument(
        "--gradle-lib", help="Directory containing Kotlin compiler and stdlib jars"
    )
    parser.add_argument(
        "--only", action="append", help="Run only named checks from selected groups"
    )
    args = parser.parse_args()
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    env.setdefault("JAVA_TOOL_OPTIONS", "-Xmx512m")
    log_dir = Path(tempfile.mkdtemp(prefix="floatdesk-verify-"))
    print(f"Check logs: {log_dir}", flush=True)
    failures = []
    selected = set(args.only or [])
    filtered = bool(args.only)
    if filtered and "integration" in (args.group or []):
        selected.add("host-release")
    count = 0
    for name, command in checks(
        args.group or ["rust", "python", "jvm"], args.gradle_lib
    ):
        if filtered and name not in selected:
            continue
        selected.discard(name)
        count += 1
        path = log_dir / f"{name}.log"
        with path.open("w") as log:
            try:
                code = subprocess.run(
                    command,
                    cwd=ROOT,
                    env=env,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    timeout=300,
                ).returncode
            except (OSError, subprocess.TimeoutExpired) as error:
                log.write(str(error))
                code = 1
        print(f"{'FAIL' if code else 'PASS'} {name}", flush=True)
        if code:
            failures.append(name)
            print(
                "\n".join(path.read_text(errors="replace").splitlines()[-15:]),
                flush=True,
            )
    if selected:
        parser.error("Unknown/unselected checks: " + ", ".join(sorted(selected)))
    print(f"{count - len(failures)}/{count} checks passed. Logs: {log_dir}")
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
