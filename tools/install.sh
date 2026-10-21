#!/bin/sh
# Installs Atelier on a TV reachable over adb and verifies every step.
# Usage: sh tools/install.sh <TV_IP> [path/to/atelier.apk]
set -eu

D=${1:?usage: install.sh <TV_IP> [apk]}
APK=${2:-out/atelier.apk}
PKG=com.yunar.atelier
A11Y=$PKG/$PKG.AtelierA11y
[ -f "$APK" ] || { echo "APK not found: $APK (run build.sh first)" >&2; exit 1; }

say() { printf '\n== %s ==\n' "$1"; }
die() { echo "FAILED: $1" >&2; exit 1; }

say "connect $D"
adb connect "$D" >/dev/null 2>&1 || true
adb -s "$D" get-state >/dev/null 2>&1 || die "adb cannot reach $D (enable network debugging, same tailnet?)"

say "install $APK"
adb -s "$D" install -r "$APK" | grep -q Success || die "install failed (signature mismatch? uninstall first, which erases the row)"
adb -s "$D" shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS \
  || die "pm grant WRITE_SECURE_SETTINGS failed"
adb -s "$D" shell cmd appops set $PKG APP_AUTO_START allow \
  || die "appops APP_AUTO_START failed (accessibility will not bind on TCL)"

say "start the app BEFORE enabling accessibility (order matters on TCL)"
adb -s "$D" shell am start -n $PKG/.MainActivity >/dev/null
sleep 3

say "enable accessibility service"
adb -s "$D" shell settings put secure enabled_accessibility_services null
adb -s "$D" shell settings put secure accessibility_enabled 1
adb -s "$D" shell settings put secure enabled_accessibility_services $A11Y
sleep 3

say "make it HOME"
adb -s "$D" shell cmd package set-home-activity $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 2

say "verify"
HOME_NOW=$(adb -s "$D" shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME | tr -d '\r' | tail -1)
BOUND=$(adb -s "$D" shell dumpsys accessibility | grep -m1 "Bound services" | tr -d '\r')
CRASHED=$(adb -s "$D" shell dumpsys accessibility | grep -m1 "Crashed services" | tr -d '\r')

echo "HOME        : $HOME_NOW"
echo "accessibility: $BOUND"
echo "crashed     : $CRASHED"

echo "$HOME_NOW" | grep -q "$PKG" || die "HOME did not move to $PKG"
echo "$BOUND" | grep -q "$PKG" || die "accessibility not bound - the gear key will NOT work (toggle it again with the app running)"
case "$CRASHED" in *"$PKG"*) die "accessibility service is marked crashed - force-stop, restart the app, re-enable";; esac

echo
echo "OK: installed, HOME set, accessibility bound."
