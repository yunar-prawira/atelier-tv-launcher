#!/bin/sh
# Appends a timestamped sample of the audio route + ARC/CEC state, so the moment
# "no sound" happens is captured instead of remembered.
# Usage: sh audio-monitor.sh <serial> [interval_seconds] [samples]
D="$1"; IV="${2:-5}"; N="${3:-0}"
[ -n "$D" ] || { echo "usage: sh audio-monitor.sh <serial> [interval] [samples]" >&2; exit 1; }
i=0
while :; do
  i=$((i+1))
  [ "$N" -gt 0 ] && [ "$i" -gt "$N" ] && break
  out=$(adb -s "$D" shell 'echo "ROUTE=$(dumpsys audio | grep -m1 "^ *Devices:" )"; echo "ARC=$(getprop persist.tcl.isARCConnect) EARC=$(settings get global earc_status)"; echo "CEC=$(dumpsys hdmi_control | grep -E "mSystemAudioActivated|mArcEstablished|display_name: HT" | tr -s " " | tr "\n" " ")"; echo "POWER=$(dumpsys power | grep -m1 mWakefulness)"' </dev/null 2>&1 | tr -d '\r' | tr '\n' ' ')
  printf '%s | %s\n' "$(date '+%F %T %Z')" "$out"
  sleep "$IV"
done
