#!/bin/sh
# Read-only diagnostic snapshot for an Android TV / Google TV reached over adb.
# Nothing is changed. Usage: sh diag.sh <tv-ip-or-serial>
set -e

TARGET="$1"
[ -n "$TARGET" ] || { echo "Usage: sh diag.sh <TV_IP[:PORT]>" >&2; exit 1; }
case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null 2>&1 || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || {
  echo "Can't reach $TV (state: $(adb -s "$TV" get-state 2>&1 | tail -1))." >&2; exit 1; }

t() { _o=$(adb -s "$TV" shell "$@" </dev/null 2>&1) || true; printf '%s\n' "$_o" | tr -d '\r'; return 0; }
hdr() { printf '\n===== %s =====\n' "$1"; }

hdr "IDENTITY"
t 'getprop ro.product.model; getprop ro.product.manufacturer; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.fingerprint'

hdr "STORAGE / FILESYSTEMS (why an HDD is or isn't readable)"
echo "-- kernel filesystems that matter:"
t 'grep -Ei "ntfs|exfat|texfat|vfat|fuseblk|esdfs|sdfat" /proc/filesystems || echo "(none matched)"'
echo "-- volumes known to vold:"
t 'sm list-volumes all 2>/dev/null || echo "(sm unavailable)"'
echo "-- mounted storage:"
t 'mount | grep -Ei "media_rw|/storage|/mnt/media" || echo "(nothing mounted)"'
echo "-- block devices:"
t 'ls -l /dev/block/vold 2>/dev/null || echo "(no vold devices)"'

hdr "USB"
t 'dumpsys usb 2>/dev/null | grep -Ei "port|speed|state|host|device" | head -25 || echo "(dumpsys usb unavailable)"'

hdr "DISK"
t 'df -h /data /storage/emulated 2>/dev/null | grep -v "^Filesystem"'

hdr "AIRPLAY / CAST RECEIVERS PRESENT"
t 'pm list packages | grep -Ei "airplay|airtunes|raop|shairplay|airscreen|airpin|castreceiver|mediashell|remote.service" || echo "(none)"'

hdr "NETWORK (band + link speed matter for mirroring)"
t 'dumpsys wifi 2>/dev/null | grep -Ei "mCurrentBssid|mWifiInfo|SSID|Link speed|frequency|RSSI" | head -8 || echo "(n/a)"'
t 'ip -f inet addr show 2>/dev/null | grep -E "inet " || echo "(n/a)"'
t 'getprop | grep -Ei "net.hostname|wifi.interface" | head -5'

hdr "DISPLAY / AUDIO CAPS"
t 'wm size; wm density'
t 'dumpsys display | grep -Ei "mScreenState|DisplayDeviceInfo|fps" | head -6 || echo "(n/a)"'
t 'dumpsys audio 2>/dev/null | grep -Ei "Devices:|Output devices|sample rate" | head -6 || echo "(n/a)"'

hdr "PERFORMANCE"
t 'cat /proc/loadavg; free -m 2>/dev/null | head -3'
t 'top -b -n 1 -o %CPU 2>/dev/null | head -12 || echo "(top unavailable)"'

hdr "UI TUNING CANDIDATES (current values; nothing changed)"
t 'settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale'
t 'settings get global private_dns_mode; settings get global private_dns_specifier'
t 'settings get secure screensaver_enabled; settings get system screen_off_timeout'
t 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1'

hdr "PACKAGES"
t 'pm list packages | sed "s/^package://" | sort'
echo "-- currently disabled:"
t 'pm list packages -d | sed "s/^package://" | sort || true'

echo
echo "Diagnostic is read-only. Nothing on the TV was changed."
