#!/usr/bin/env bash
# Bukti Auto Standby soundbar mati: sampling CEC tiap 2 menit selama 30 menit.
# Kalau bar tetap power_status: 0 walau TV idle > 20 menit, auto standby memang off.
D="${1:?usage: autostandby-watch.sh <adb-serial> [logfile]}"; OUT="${2:-$HOME/atelier/docs/autostandby-watch.log}"
for i in $(seq 1 15); do
  s=$(adb -s "$D" shell 'echo "SB=$(dumpsys hdmi_control | grep -m1 "HT-S100F" | sed -E "s/.*power_status: (-?[0-9]+).*/\\1/") SA=$(dumpsys hdmi_control | grep -m1 mSystemAudioActivated | tr -d " " ) AR=$(getprop persist.tcl.isARCConnect) RT=$(dumpsys audio | grep -m1 "Devices:") WF=$(dumpsys power | grep -m1 mWakefulness | tr -d " ")"' </dev/null 2>&1 | tr -d '\r' | tr '\n' ' ')
  printf '%s | %s\n' "$(TZ=Asia/Jakarta date '+%F %T WIB')" "$s" | tee -a "$OUT"
  sleep 120
done
