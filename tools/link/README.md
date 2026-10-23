# Reference agent host for the Atelier link

The TV dials out to this process, because Android kills `adbd` on deep sleep and it cannot be
restarted without root. Nothing inbound ever needs to reach the TV.

```
python3 atelier_link.py          # foreground: link :7391, admin 127.0.0.1:7392
./ensure.sh                      # idempotent start (safe from cron every 5 min)
```

First run creates `link.token` next to the script (0600). Push it to the TV once, together with the
host it should dial:

```
printf '<this-host-tailnet-ip>:7391\n' > /tmp/link.host
adb push /tmp/link.host  /sdcard/Android/data/com.yunar.atelier/files/link.host
adb push link.token      /sdcard/Android/data/com.yunar.atelier/files/link.token
```

Then, from this machine:

```
curl -s http://127.0.0.1:7392/state                                  # last heartbeat + device state
curl -s -X POST http://127.0.0.1:7392/enqueue -d '{"cmd":"arc-rearm"}'
curl -s -X POST http://127.0.0.1:7392/enqueue -d '{"cmd":"getprop persist.tcl.isARCConnect"}'
```

`/sync` is bound to the tailnet address only and needs the bearer token; the admin port is bound to
127.0.0.1, so command enqueue never leaves the host. Commands are prefix-whitelisted on the TV side
(`getprop`, `settings get|put`, `pm list`) plus the special `arc-rearm`; anything else is refused
with a reason. `ensure.sh` exists because a crashed rendezvous silently turns into "the TV is
offline" — run it from cron and it heals itself.
