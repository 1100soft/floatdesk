#!/usr/bin/env python3
"""Run the headset matrix: automated adb scenarios, prompted wearer scenarios, one dated result.

Run outside the sandbox so adb can see USB. Results go to
.agent-artifacts/headset-matrix/matrix-<stamp>.{json,md}. A scenario that needs
the wearer is recorded as "not-run" with --auto-only or when stdin is not a
terminal, never as passed. After every scenario the crash buffer is checked for
a new Floatdesk crash, and xr-crash-report.py captures it when one appears.
"""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

PACKAGE = "dev.floatdesk.client"
ROOT = Path(__file__).resolve().parents[1]
CRASH = re.compile(
    r">>> " + re.escape(PACKAGE) + r" <<<|Process: " + re.escape(PACKAGE) + r", PID:"
)

MANUAL = [
    (
        "workspace-close",
        "Open Workspace, then close it. The desktop and every magnifier return at their saved poses.",
    ),
    (
        "flat-curved-flat",
        "Switch the desktop flat to curved to flat. Geometry, cutouts and hand aiming match the visible surface in each mode.",
    ),
    (
        "enlarged-overlap",
        "Enlarge the desktop so it overlaps a magnifier. The magnifier stays visible through its cutout and only opaque panes cut holes.",
    ),
    (
        "child-open-resize-close",
        "In Mountlet, open a child window, resize it with the corner handle, then close it. Repeat three times.",
    ),
    (
        "nested-menus",
        "Open a menu cascade three levels deep on a magnified window, then dismiss it.",
    ),
    (
        "host-reconnect",
        "Restart the host service (scripts/manage-host.py restart). The headset reconnects without restarting the app.",
    ),
    (
        "hand-routing",
        "Use Hand Mouse with one hand, then both. Drag a window, lose hover mid-drag and release: the host button releases.",
    ),
    (
        "cursor-ownership",
        "Move the cursor across overlapping desktop and magnifier. The cyan marker follows the owning pane.",
    ),
    (
        "gaze-switch",
        "Aim a hand at a magnifier, then gaze at another pane. Gaze to magnify switches without stealing the aimed hand.",
    ),
    (
        "palm-edit",
        "Use the palm buttons to move and resize a pane, then confirm the layout persists after reopening Workspace.",
    ),
]


class Device:
    def __init__(self, adb):
        self.adb = adb

    def call(self, *values, timeout=30, check=True):
        return subprocess.run(
            [self.adb, *values],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=timeout,
            check=check,
        ).stdout

    def pid(self):
        return self.call("shell", "pidof", PACKAGE, check=False).strip()

    def epoch(self):
        return self.call("shell", "date", "+%s").strip() + ".000"

    def crashes_since(self, since):
        return [
            line
            for line in self.call(
                "logcat", "-b", "crash", "-d", "-T", since, "-v", "threadtime"
            ).splitlines()
            if CRASH.search(line)
        ]


def ensure_device(device):
    if "\tdevice" in device.call("devices"):
        return "adb listed the headset"
    device.call("kill-server", check=False)
    device.call("start-server", check=False)
    listed = device.call("devices", "-l")
    if "\tdevice" in listed or " device " in listed:
        return "adb had dropped the headset; restarting the server recovered it"
    usb = subprocess.run(["lsusb"], capture_output=True, text=True).stdout
    if re.search(r"SM_I610|xrvst2|Galaxy XR", usb):
        raise SystemExit(
            "The headset is on USB but adb does not list it. Check USB debugging authorization."
        )
    raise SystemExit("The headset is absent from both adb and lsusb: it is unplugged.")


def wait_for(device, pid, pattern, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if device.pid() != pid:
            return False, "process exited"
        log = device.call("logcat", "-d", "--pid=" + pid, "-s", "Floatdesk:I")
        if re.search(pattern, log):
            return True, ""
        time.sleep(0.5)
    return False, f"no `{pattern}` within {seconds:g} s"


def cold_start(device, args):
    device.call("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)
    device.call(
        "shell",
        "monkey",
        "-p",
        PACKAGE,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
    )
    end = time.monotonic() + 15
    pid = ""
    while not pid and time.monotonic() < end:
        time.sleep(0.5)
        pid = device.pid()
    if not pid:
        return "fail", "Floatdesk did not start"
    ok, why = wait_for(device, pid, r"desktop surface attached", args.ready_seconds)
    if not ok:
        return "fail", f"pid {pid}: desktop never attached ({why})"
    journal = device.call("logcat", "-d", "--pid=" + pid, "-s", "FloatdeskNative:I")
    if "op=" not in journal:
        return "fail", f"pid {pid}: no native journal lines"
    ok, why = wait_for(device, pid, r"pane ready key=", args.ready_seconds)
    return "pass", f"pid {pid}: desktop attached, journal present" + (
        ", a pane presented" if ok else f", no pane presented ({why})"
    )


def sleep_wake(device, args):
    if device.pid() == "":
        return "fail", "Floatdesk is not running"
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts/check-xr-wake.py")],
        capture_output=True,
        text=True,
        timeout=120,
    )
    tail = (result.stdout or result.stderr).strip().splitlines()[-1:] or [""]
    return ("pass" if result.returncode == 0 else "fail"), tail[0]


AUTOMATED = [
    ("cold-start", cold_start),
    ("sleep-wake", sleep_wake),
]


def crash_report(pid):
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts/xr-crash-report.py")]
        + (["--pid", pid] if pid else []),
        capture_output=True,
        text=True,
        timeout=120,
    )
    return (result.stdout or result.stderr).strip()


def prompt(name, steps):
    print(f"\n[{name}] {steps}")
    while True:
        answer = input("  Result? [p]ass / [f]ail / [s]kip: ").strip().lower()
        if answer[:1] in ("p", "f", "s"):
            break
    notes = input("  Notes (what you saw): ").strip()
    return {"p": "pass", "f": "fail", "s": "skipped"}[answer[:1]], notes


def write(results, meta, out):
    out.mkdir(parents=True, exist_ok=True)
    stem = out / ("matrix-" + meta["started"].replace(":", "").replace("-", ""))
    stem.with_suffix(".json").write_text(
        json.dumps({**meta, "results": results}, indent=2) + "\n"
    )
    lines = [
        f"# Headset matrix {meta['started']}",
        "",
        f"Commit `{meta['commit']}`. {meta['device']}.",
        "",
        "| Scenario | Kind | Result | Notes |",
        "| --- | --- | --- | --- |",
    ]
    for r in results:
        notes = r["notes"].replace("|", "/").replace("\n", " ")
        if r.get("crash"):
            notes += f" Crash: {r['crash']}"
        lines.append(f"| {r['name']} | {r['kind']} | {r['result']} | {notes} |")
    stem.with_suffix(".md").write_text("\n".join(lines) + "\n")
    return stem


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--auto-only", action="store_true", help="Record wearer scenarios as not-run"
    )
    parser.add_argument(
        "--only", action="append", default=[], help="Run only the named scenarios"
    )
    parser.add_argument("--ready-seconds", type=float, default=30)
    parser.add_argument(
        "--out", type=Path, default=ROOT / ".agent-artifacts/headset-matrix"
    )
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()
    if args.list:
        for name, _ in AUTOMATED:
            print(f"{name}\tautomated")
        for name, steps in MANUAL:
            print(f"{name}\tmanual\t{steps}")
        return
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    device = Device(str(sdk / "platform-tools/adb"))
    meta = {
        "started": datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "commit": subprocess.run(
            ["git", "-C", str(ROOT), "describe", "--always", "--dirty"],
            capture_output=True,
            text=True,
        ).stdout.strip(),
        "device": ensure_device(device),
    }
    if device.call("shell", "dumpsys", "power").find("mWakefulness=Awake") < 0:
        print(
            "Warning: the headset is not awake. Put it on before running scenarios.",
            file=sys.stderr,
        )
    interactive = sys.stdin.isatty() and not args.auto_only
    selected = set(args.only)
    results = []
    scenarios = [(n, "automated", f) for n, f in AUTOMATED] + [
        (n, "manual", s) for n, s in MANUAL
    ]
    for name, kind, action in scenarios:
        if selected and name not in selected:
            continue
        since = device.epoch()
        if kind == "automated":
            print(f"[{name}] running")
            try:
                result, notes = action(device, args)
            except (subprocess.SubprocessError, OSError) as error:
                result, notes = "fail", f"error: {error}"
        elif interactive:
            result, notes = prompt(name, action)
        else:
            result, notes = "not-run", action
        entry = {"name": name, "kind": kind, "result": result, "notes": notes}
        crashes = device.crashes_since(since)
        if crashes:
            entry["result"] = "fail"
            pid = re.search(r"pid: (\d+)|PID: (\d+)", crashes[0])
            entry["crash"] = crash_report(
                next(g for g in pid.groups() if g) if pid else ""
            )
        results.append(entry)
        print(f"  {entry['result']}: {entry['notes']}")
        if entry.get("crash"):
            print(f"  crash report: {entry['crash']}")
    stem = write(results, meta, args.out)
    print(f"\nWrote {stem}.json and {stem}.md")
    if any(r["result"] == "fail" for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
