#!/bin/sh
# Passive wake-catcher: adb goes offline while an Android TV sleeps, so this keeps
# reconnecting and, the moment the TV is reachable again, samples the audio/CEC state
# every 2 s for 90 s — capturing what happens at wake without touching the TV.
# Usage: sh wake-watch.sh <serial> [total_minutes]
D="$1"; MINS="${2:-180}"
[ -n "$D" ] || { echo "usage: sh wake-watch.sh <serial> [minutes]" >&2; exit 1; }
st() { adb -s "$D" shell "$@" </dev/null 2>&1 | tr -d '\r' | grep -v '^Failed to write'; }

snap() {
  _arc=$(st 'getprop persist.tcl.isARCConnect')
  _sa=$(st 'dumpsys hdmi_control | grep -m1 mSystemAudioActivated' | tr -s ' ')
  _ae=$(st 'dumpsys hdmi_control | grep -m1 mArcEstablished' | tr -s ' ')
  _sb=$(st 'dumpsys hdmi_control | grep -m1 "display_name: HT-S100F"' | tr -s ' ' | sed 's/.*power_status: \([0-9]\).*/SB=\1/')
  _rt=$(st 'dumpsys audio | grep -m1 "^ *Devices:"' | tr -s ' ')
  _pw=$(st 'dumpsys power | grep -m1 mWakefulness' | tr -s ' ')
  printf '%s | ARC=%s | %s | SB=%s | %s | %s | %s\n' "$(TZ=Asia/Jakarta date '+%F %T WIB')" "$_arc" "$_sa" "$_sb" "$_ae" "$_rt" "$_pw"
}

END=$((MINS * 20))
n=0
prev=offline
while [ "$n" -lt "$END" ]; do
  n=$((n+1))
  adb connect "$D" >/dev/null 2>&1
  state=$(adb -s "$D" get-state 2>&1 | tr -d '\r')
  if [ "$state" = "device" ]; then
    if [ "$prev" != "device" ]; then
      echo "=== $(TZ=Asia/Jakarta date '+%F %T WIB') TV REACHABLE AGAIN (was $prev) — sampling wake ==="
      i=0
      while [ "$i" -lt 45 ]; do i=$((i+1)); snap; sleep 2; done
      echo "=== wake window closed; back to idle polling ==="
    fi
    prev=device
  else
    [ "$prev" = "device" ] && echo "=== $(TZ=Asia/Jakarta date '+%F %T WIB') TV WENT OFFLINE (state: $state) ==="
    prev="$state"
  fi
  sleep 3
done
echo "watcher finished after ${MINS} min"
