#!/bin/bash
# Keeps the Atelier link agent alive. Idempotent: safe to run every few minutes from cron.
set -u
DIR="/home/ubuntu/atelier-link"
cd "$DIR" || exit 1

if curl -s -m 3 http://127.0.0.1:7392/state >/dev/null 2>&1; then
  exit 0
fi

# stale pid file, if any
[ -f link.pid ] && kill "$(cat link.pid)" 2>/dev/null
nohup python3 "$DIR/atelier_link.py" > "$DIR/link.out" 2>&1 &
echo $! > link.pid
sleep 2

if curl -s -m 3 http://127.0.0.1:7392/state >/dev/null 2>&1; then
  echo "$(TZ=Asia/Jakarta date '+%F %T WIB') started pid=$(cat link.pid)"
else
  echo "$(TZ=Asia/Jakarta date '+%F %T WIB') FAILED to start" >&2
  exit 1
fi
