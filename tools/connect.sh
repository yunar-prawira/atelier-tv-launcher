#!/bin/sh
# Attaches adb to the TV over the network — same Wi-Fi or a VPN such as Tailscale.
#
# Usage:
#   sh tools/connect.sh                       # try mDNS discovery (wireless debugging on)
#   sh tools/connect.sh 192.168.1.50          # TV on your LAN
#   sh tools/connect.sh 100.101.102.103       # TV on your tailnet
#   sh tools/connect.sh --pair 192.168.1.50:37123 123456
#        ^ Android 11 "Wireless debugging -> Pair device with pairing code"
#          then connect with the *debug* port: sh tools/connect.sh 192.168.1.50:5555
set -eu

PORT_DEFAULT=5555
say() { printf '%s\n' "$*"; }
die() { echo "FAILED: $*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || die "adb not installed (apt install android-tools-adb / brew install android-platform-tools)"

if [ "${1:-}" = "--pair" ]; then
  TARGET=${2:?usage: connect.sh --pair <ip>:<pairing_port> <code>}
  CODE=${3:?usage: connect.sh --pair <ip>:<pairing_port> <code>}
  adb pair "$TARGET" "$CODE" || die "pairing failed - the code expires in ~60 s, read a fresh one from the TV"
  say "Paired. Now connect with the debug port shown on the TV (usually 5555 or a random one):"
  say "    sh tools/connect.sh <ip>:<debug_port>"
  exit 0
fi

# No target: look for an already-paired wireless-debugging service on the local network.
if [ $# -eq 0 ]; then
  say "No target given - asking adb mDNS for a paired TV ..."
  TARGET=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF; exit}')
  [ -n "${TARGET:-}" ] || die "nothing found. Pass the TV's IP: sh tools/connect.sh <ip>[:port]
  - same Wi-Fi: Settings > Network, or 'adb shell ip route' while connected over USB
  - tailnet: 'tailscale status' on this machine, pick the peer whose OS is android"
  say "found: $TARGET"
else
  TARGET=$1
  case "$TARGET" in *:*) : ;; *) TARGET="$TARGET:$PORT_DEFAULT" ;; esac
fi

say "connecting to $TARGET"
adb connect "$TARGET" || true

# The first connection lands on "unauthorized" until somebody taps Allow on the TV.
i=0
while [ $i -lt 24 ]; do
  STATE=$(adb -s "$TARGET" get-state 2>/dev/null || true)
  case "$STATE" in
    device) break ;;
    unauthorized) say "waiting: tap 'Allow' (tick 'Always allow') on the TV ..." ;;
  esac
  i=$((i + 1)); sleep 5
done

STATE=$(adb -s "$TARGET" get-state 2>/dev/null || true)
[ "$STATE" = "device" ] || die "not connected (state: ${STATE:-none}).
  - unauthorized: accept the on-screen prompt on the TV
  - offline/refused: is Network debugging on? is the port right? is the TV awake?
  - VPN: is the TV online in 'tailscale status' and on the same tailnet?"

say "connected: $TARGET"
adb -s "$TARGET" shell getprop ro.product.model | tr -d '\r' | sed 's/^/device: /'
adb -s "$TARGET" shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/android: /'
