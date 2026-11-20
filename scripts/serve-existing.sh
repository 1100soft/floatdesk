#!/usr/bin/env bash
# Persist the host across logout/reboot; active instances are left running.
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
exec python3 "$project_root/scripts/manage-host.py" "$@"
