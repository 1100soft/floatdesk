#!/usr/bin/env python3
"""Opt-in device check: suspend/wake a running Floatdesk without restarting it."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suspend-seconds", type=float, default=3)
    args = parser.parse_args()
    if args.suspend_seconds <= 0:
        parser.error("Suspend duration must be positive")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    adb = str(sdk / "platform-tools/adb")

    def call(*values):
        return subprocess.check_output([adb, *values], text=True, timeout=15)

    def power():
        return re.search(r"mWakefulness=(\w+)", call("shell", "dumpsys", "power"))[1]

    if power() != "Awake":
        raise SystemExit(
            "Headset must already be awake; no presence/settings override is used"
        )
    pid = call("shell", "pidof", "dev.floatdesk.client").strip()
    if not pid:
        raise SystemExit("Open Floatdesk before running this check")
    previous = call("logcat", "-d", "--pid=" + pid, "-s", "Floatdesk:I")
    generation = max(
        map(int, re.findall(r"scene recovery generation=(\d+)", previous)), default=-1
    )
    states = []
    with tempfile.TemporaryFile(mode="w+") as trace:
        logger = subprocess.Popen(
            [
                adb,
                "logcat",
                "-T",
                "1",
                "--pid=" + pid,
                "-s",
                "Floatdesk:I",
                "AndroidRuntime:E",
            ],
            stdout=trace,
            stderr=subprocess.DEVNULL,
        )
        try:
            time.sleep(0.3)
            call("shell", "input", "keyevent", "223")
            end = time.monotonic() + args.suspend_seconds
            while time.monotonic() < end:
                state = power()
                if not states or states[-1] != state:
                    states.append(state)
                time.sleep(0.25)
            call("shell", "input", "keyevent", "224")
            end = time.monotonic() + 20
            recovered = False
            while time.monotonic() < end:
                if call("shell", "pidof", "dev.floatdesk.client").strip() != pid:
                    raise AssertionError("Floatdesk process exited during wake")
                log = os.pread(trace.fileno(), 4 * 1024 * 1024, 0).decode(
                    errors="replace"
                )
                resumed = max(
                    map(int, re.findall(r"scene recovery generation=(\d+)", log)),
                    default=-1,
                )
                markers = list(re.finditer(r"scene recovery generation=\d+", log))
                after_scene = log[markers[-1].end() :] if markers else ""
                # Tracking can resume just before scene submission. Presentation
                # must belong to the newly submitted scene, not an older frame.
                recovered = (
                    resumed > generation
                    and "tracking resources reacquired after wake" in log
                    and "pane ready key=" in after_scene
                    and "rendered=" in after_scene
                    and "FATAL EXCEPTION" not in log
                )
                if recovered and power() == "Awake":
                    break
                time.sleep(0.5)
            if not any(state != "Awake" for state in states):
                raise AssertionError("Android did not enter suspension")
            if not recovered or power() != "Awake":
                raise AssertionError(
                    "Wake did not restore scene, tracking and pane/video output"
                )
            print(
                json.dumps(
                    {
                        "passed": True,
                        "same_process": True,
                        "states": states,
                        "full_sleep_observed": "Asleep" in states,
                    }
                )
            )
        finally:
            call("shell", "input", "keyevent", "224")
            logger.terminate()
            logger.wait(timeout=5)


if __name__ == "__main__":
    main()
