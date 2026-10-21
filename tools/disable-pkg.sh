#!/bin/sh
# Disable one package: log it on the TV first (so undo works), then disable, then verify.
# Skips PERSISTENT packages, which ignore pm disable-user.
# Usage: sh disable-pkg.sh <serial> <package> [package...]
D="$1"; shift
[ -n "$1" ] || { echo "usage: sh disable-pkg.sh <serial> <pkg>..." >&2; exit 1; }
LOG=/data/local/tmp/tvhome-disabled.txt
st() { adb -s "$D" shell "$@" </dev/null 2>&1 | tr -d '\r'; }

for p in "$@"; do
  flags=$(st "dumpsys package $p 2>/dev/null | sed -n '/pkgFlags=\[/{p;q;}'")
  if [ -z "$flags" ]; then echo "SKIP  $p (not installed)"; continue; fi
  case "$flags" in
    *PERSISTENT*) echo "SKIP  $p (PERSISTENT — Android restarts it anyway)"; continue ;;
  esac
  if st "pm list packages -d" | grep -Fqx "package:$p"; then echo "ALREADY OFF $p"; continue; fi
  st "echo $p >> $LOG" || { echo "SKIP  $p (could not write undo log)"; continue; }
  st "pm disable-user --user 0 $p" >/dev/null 2>&1
  if st "pm list packages -d" | grep -Fqx "package:$p"; then
    echo "OFF   $p"
  else
    echo "FAIL  $p (still enabled)"
    st "sed -i '/^$p\$/d' $LOG" >/dev/null 2>&1
  fi
done
