#!/bin/sh
# One-command undo for everything done to the TV.
#   sh restore.sh <serial>            re-enable the stock launcher (keeps Atelier installed)
#   sh restore.sh <serial> --all      also re-enable every package in the on-device disable log
#   sh restore.sh <serial> --pkg X     re-enable one package
D="$1"; MODE="$2"; ARG="$3"
[ -n "$D" ] || { echo "usage: sh restore.sh <serial> [--all|--pkg <name>]" >&2; exit 1; }
case "$D" in *:*) ;; *.*.*.*) D="$D:5555" ;; esac
adb connect "$D" >/dev/null 2>&1
[ "$(adb -s "$D" get-state 2>/dev/null | tr -d '\r')" = "device" ] || { echo "Can't reach $D" >&2; exit 1; }
t() { adb -s "$D" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
LOG=/data/local/tmp/tvhome-disabled.txt

enable() {
  t "pm enable $1" >/dev/null 2>&1
  if t "pm list packages -d" | grep -Fqx "package:$1"; then
    echo "FAIL  $1 still disabled"
  else
    echo "ON    $1"
    t "sed -i '/^$1\$/d' $LOG" >/dev/null 2>&1
  fi
}

case "$MODE" in
  --pkg) [ -n "$ARG" ] || { echo "--pkg needs a package name" >&2; exit 1; }; enable "$ARG" ;;
  --all)
    echo "== stock launcher first, then everything else =="
    enable com.google.android.apps.tv.launcherx
    enable com.google.android.tungsten.setupwraith
    if t "cat $LOG 2>/dev/null" | grep -q .; then
      for p in $(t "sort -u $LOG"); do enable "$p"; done
    else
      echo "(disable log is empty — nothing else to restore)"
    fi
    t 'cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity' >/dev/null 2>&1
    t 'input keyevent KEYCODE_HOME'
    echo "== home now: $(t 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1')"
    ;;
  *)
    echo "== restoring the stock launcher only =="
    enable com.google.android.apps.tv.launcherx
    enable com.google.android.tungsten.setupwraith
    t 'input keyevent KEYCODE_HOME'
    echo "== home now: $(t 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1')"
    ;;
esac
