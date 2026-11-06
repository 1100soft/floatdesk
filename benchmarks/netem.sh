#!/bin/sh
# Apply or remove latency, jitter, and loss on the loopback or a LAN device
# so the RTP proof of concept can be measured under impairment.
#
#   sudo ./netem.sh add eth0 20ms 5ms 0.5%
#   sudo ./netem.sh del eth0
set -eu

action="${1:-}"
device="${2:-lo}"

case "$action" in
  add)
    delay="${3:-20ms}"
    jitter="${4:-5ms}"
    loss="${5:-0.1%}"
    tc qdisc replace dev "$device" root netem delay "$delay" "$jitter" loss "$loss"
    ;;
  del)
    tc qdisc del dev "$device" root || true
    ;;
  *)
    echo "usage: $0 add <device> [delay] [jitter] [loss]" >&2
    echo "       $0 del <device>" >&2
    exit 2
    ;;
esac
