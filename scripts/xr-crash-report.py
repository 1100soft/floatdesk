#!/usr/bin/env python3
"""Write one bounded crash report: the crash-buffer stack plus the preceding native journal for that PID.

The headset must be reachable by adb (run outside the sandbox). Reports go to
.agent-artifacts/crash-reports/ and only the newest --keep reports are retained.
"""

import argparse
import datetime
import os
from pathlib import Path
import re
import subprocess

PACKAGE = "dev.floatdesk.client"
ROOT = Path(__file__).resolve().parents[1]
THREADTIME = re.compile(
    r"^(?P<date>\d\d-\d\d) (?P<time>\d\d:\d\d:\d\d\.\d+)\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>\w)\s+(?P<tag>[^:]*?)\s*: (?P<message>.*)$"
)
NATIVE_CRASH = re.compile(
    r"pid: (\d+), tid: \d+, name: (\S+)\s+>>> " + re.escape(PACKAGE) + " <<<"
)
JAVA_CRASH = re.compile(r"Process: " + re.escape(PACKAGE) + r", PID: (\d+)")


def adb_path():
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    return str(sdk / "platform-tools/adb")


def logcat(*args):
    return subprocess.run(
        [adb_path(), "logcat", "-d", "-v", "threadtime", *args],
        check=True,
        capture_output=True,
        text=True,
        errors="replace",
        timeout=30,
    ).stdout


def parse(text):
    rows = []
    for line in text.splitlines():
        match = THREADTIME.match(line)
        if match:
            rows.append((match, line))
    return rows


def find_crash(rows, pid=None):
    """Return the crashed PID and the crash-buffer lines for its most recent incident."""
    hits = []
    for index, (match, _) in enumerate(rows):
        message = match["message"]
        native = NATIVE_CRASH.search(message)
        java = JAVA_CRASH.search(message)
        found = (native or java) and int((native or java)[1])
        if found and (pid is None or found == pid):
            hits.append((index, found, "native" if native else "java"))
    if not hits:
        return None, "", []
    index, crashed, kind = hits[-1]
    if kind == "java":
        lines = [line for match, line in rows if int(match["pid"]) == crashed]
    else:
        # The tombstone is logged by crash_dump under its own PID. Take that writer's
        # block from its header to the next header.
        writer = rows[index][0]["pid"]
        start = index
        while (
            start > 0
            and rows[start - 1][0]["pid"] == writer
            and "*** ***" not in rows[start][1]
        ):
            start -= 1
        end = index
        while (
            end + 1 < len(rows)
            and rows[end + 1][0]["pid"] == writer
            and "*** ***" not in rows[end + 1][1]
        ):
            end += 1
        lines = [line for _, line in rows[start : end + 1]]
    return crashed, kind, lines


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--pid",
        type=int,
        help="Report this PID instead of the most recent Floatdesk crash",
    )
    parser.add_argument("--journal-lines", type=int, default=200)
    parser.add_argument(
        "--context-lines", type=int, default=60, help="Preceding Floatdesk tag lines"
    )
    parser.add_argument("--crash-lines", type=int, default=300)
    parser.add_argument(
        "--keep", type=int, default=10, help="Retain only the newest reports"
    )
    parser.add_argument(
        "--crash-log",
        type=Path,
        help="Use a saved `logcat -b crash -v threadtime` dump",
    )
    parser.add_argument(
        "--main-log", type=Path, help="Use a saved `logcat -b main -v threadtime` dump"
    )
    parser.add_argument(
        "--out", type=Path, default=ROOT / ".agent-artifacts/crash-reports"
    )
    args = parser.parse_args()

    crash_text = (
        args.crash_log.read_text(errors="replace")
        if args.crash_log
        else logcat("-b", "crash")
    )
    pid, kind, crash = find_crash(parse(crash_text), args.pid)
    if pid is None:
        raise SystemExit("No Floatdesk crash found in the crash buffer")
    main_text = (
        args.main_log.read_text(errors="replace")
        if args.main_log
        else logcat(
            "-b",
            "main",
            "-s",
            "FloatdeskNative:I",
            "Floatdesk:I",
            "Floatdesk:W",
            "Floatdesk:E",
        )
    )
    own = [
        (match, line) for match, line in parse(main_text) if int(match["pid"]) == pid
    ]
    journal = [line for match, line in own if match["tag"] == "FloatdeskNative"]
    context = [line for match, line in own if match["tag"] == "Floatdesk"]

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    args.out.mkdir(parents=True, exist_ok=True)
    report = args.out / f"xr-crash-{stamp}-pid{pid}.txt"
    sections = [
        f"Floatdesk crash report {stamp}",
        f"pid={pid} kind={kind} crash_lines={len(crash)} journal_lines={len(journal)} context_lines={len(context)}",
        "Journal lines are recorded before each native call, so the last lines precede the failing operation.",
        "",
        "== Crash buffer ==",
        *crash[-args.crash_lines :],
        "",
        f"== FloatdeskNative journal (last {args.journal_lines}) ==",
        *(
            journal[-args.journal_lines :]
            or ["(none: the main buffer rotated, or the build predates the journal)"]
        ),
        "",
        f"== Floatdesk context (last {args.context_lines}) ==",
        *context[-args.context_lines :],
        "",
    ]
    report.write_text("\n".join(sections))
    for stale in sorted(args.out.glob("xr-crash-*.txt"))[: -args.keep]:
        stale.unlink()
    print(report)


if __name__ == "__main__":
    main()
