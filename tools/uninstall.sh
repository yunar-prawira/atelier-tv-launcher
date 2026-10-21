#!/bin/sh
# Removes Atelier and puts the TV back on its stock launcher — in the only safe order.
# Usage: sh tools/uninstall.sh <TV_IP> [--uninstall-apk]
#
# Why this script exists: uninstalling the only HOME activity leaves an Android TV with no home
# screen until it is recovered over adb. So we re-enable the stock launchers, point HOME back at
# one of them, PROVE that HOME resolves, and only then (optionally) uninstall the package.
set -eu

D=${1:?usage: uninstall.sh <TV_IP> [--uninstall-apk]}
MODE=${2:-}
PKG=com.yunar.atelier
STOCK=com.google.android.apps.tv.launcherx
STOCK_ACT=$STOCK/.home.HomeActivity

say() { printf '\n== %s ==\n' "$1"; }
die() { echo "FAILED: $1" >&2; exit 1; }

say "connect $D"
adb connect "$D" >/dev/null 2>&1 || true
adb -s "$D" get-state >/dev/null 2>&1 || die "adb cannot reach $D"

say "re-enable the stock launcher and the setup wrapper"
adb -s "$D" shell pm enable $STOCK || die "pm enable $STOCK failed"
adb -s "$D" shell pm enable com.google.android.tungsten.setupwraith || true

say "point HOME back at the stock launcher"
adb -s "$D" shell cmd package set-home-activity "$STOCK_ACT" >/dev/null 2>&1 || true
sleep 2

say "turn off the accessibility service"
adb -s "$D" shell settings put secure enabled_accessibility_services null >/dev/null 2>&1 || true
adb -s "$D" shell settings put secure accessibility_enabled 0 >/dev/null 2>&1 || true

say "prove HOME resolves somewhere safe"
HOME_NOW=$(adb -s "$D" shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME | tr -d '\r' | tail -1)
echo "HOME now: $HOME_NOW"
case "$HOME_NOW" in
  *"$PKG"*) die "HOME still resolves to Atelier - refusing to remove it. Re-enable the stock launcher manually first.";;
  "") die "HOME resolves to nothing - refusing to continue. Recover the stock launcher first.";;
esac

if [ "$MODE" = "--uninstall-apk" ]; then
  say "uninstall $PKG"
  adb -s "$D" uninstall $PKG || die "adb uninstall failed"
else
  say "leaving the package installed (pass --uninstall-apk to remove it)"
fi

echo
echo "OK. HOME is $HOME_NOW. Your app row lives in /sdcard/Android/data/$PKG/files/apps.txt and is"
echo "deleted with the package on uninstall."
