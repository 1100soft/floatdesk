#!/usr/bin/env bash
# Keep the host independent of a terminal/tool lifetime and regular-file log limits.
set -euo pipefail

project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
stream_binary=${FLOATDESK_BIN:-"$project_root/target/release/floatdesk"}
if [[ ! -x "$stream_binary" ]]; then
    echo "Build the host with cargo build --release, or set FLOATDESK_BIN to its executable." >&2
    exit 1
fi
stream_binary=$(realpath -- "$stream_binary")
desktop_environment=(--setenv="DISPLAY=${DISPLAY:?Run this from your X11 desktop session}")
if [[ -n ${XAUTHORITY:-} ]]; then
    desktop_environment+=(--setenv="XAUTHORITY=$XAUTHORITY")
fi

exec systemd-run --user --collect --unit=floatdesk-live \
    --description="Floatdesk existing desktop stream" \
    --property=Restart=on-failure --property=RestartSec=2s \
    --property=LimitFSIZE=infinity \
    --property=StandardOutput=journal --property=StandardError=journal \
    "${desktop_environment[@]}" "$stream_binary" serve --existing "$@"
