#!/bin/sh
# Read-only: why does the soundbar stay silent after cold boot / wake?
# Usage: sh sound-diag.sh <tv-ip-or-serial>
set -e

TARGET="$1"
[ -n "$TARGET" ] || { echo "Usage: sh sound-diag.sh <TV_IP[:PORT]>" >&2; exit 1; }
case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null 2>&1 || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || {
  echo "Can't reach $TV." >&2; exit 1; }

t() { _o=$(adb -s "$TV" shell "$@" </dev/null 2>&1) || true; printf '%s\n' "$_o" | tr -d '\r'; }
hdr() { printf '\n===== %s =====\n' "$1"; }

hdr "POWER STATE (awake or just woken?)"
t 'dumpsys power | grep -E "mWakefulness|mHoldingWakeLockSuspendBlocker|Display Power" | head -5'

hdr "ACTIVE AUDIO OUTPUT (the money shot)"
echo "-- current output device(s):"
t 'dumpsys audio | grep -E "^- .*(DeviceType|address)|Devices:|speaker|hdmi|arc" | head -20'
echo "-- audio_policy output routes:"
t 'dumpsys media.audio_policy 2>/dev/null | grep -E "Output.*device|Device [0-9]+:|AUDIO_DEVICE_OUT_(HDMI|SPEAKER|HDMI_ARC)" | head -20 || echo "(n/a)"'

hdr "HDMI-CEC"
t 'dumpsys hdmi_control 2>/dev/null | head -60 || echo "(hdmi_control service not present)"'

hdr "CEC / ARC RELATED SETTINGS"
t 'settings list global | grep -iE "cec|hdmi|arc|audio" || echo "(none)"'
t 'settings list secure | grep -iE "cec|hdmi|audio" || echo "(none)"'
t 'getprop | grep -iE "cec|arc|hdmi|audio.*out" || echo "(none)"'

hdr "CEC / AUDIO LOG SINCE BOOT"
t 'logcat -b all -d -v time 2>/dev/null | grep -iE "hdmi_cec|hdmicontrol|HdmiCec|audio.?policy.*(device|rout)|AudioSystem|ARC" | tail -40 || echo "(no log)"'

hdr "SOUNDBAR SEEN AS AN HDMI DEVICE?"
t 'dumpsys hdmi_control 2>/dev/null | grep -iE "logical|vendor|osd|power status|audio" | head -20 || echo "(n/a)"'

echo
echo "Read-only. Nothing changed on the TV."
