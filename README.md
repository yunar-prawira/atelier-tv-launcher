# Atelier — a quiet home screen for a TCL Google TV

An Android TV launcher (~30 KB) that replaces the ad-filled stock home screen, written in plain
Java and built **without Gradle or Android Studio**. It is deliberately boring: a clock, one
row of apps you choose, and a dock with Input / Applications / Settings / Setup / Edit.

Everything here was developed against one specific set: **TCL 43A30 (Smart TV Pro),
Android 11, platform `BeyondTV4`, 2 GB RAM**, driven only by a D-pad remote, reached over
`adb` on a Tailscale network. Vendor firmware outranks documentation, so the quirks in
[`docs/DEVICE-NOTES.md`](docs/DEVICE-NOTES.md) are the most valuable part of this repo.

## What it does

- **Home screen**: clock + date, one justified row of up to 10 apps, no ads, no "recommended" rows.
- **Setup** (dock chip): rename the wordmark, add an app by package name, reset the row to the
  apps compiled into the build. Everything below is reachable with the remote alone.
- **Edit**: the `Edit` chip turns the row into a management surface. Press OK on a tile to get
  `Move` / `Remove from home` / `Uninstall`; `Move` shows an on-screen hint and uses left/right,
  OK to drop.
- **Applications**: a full-screen, alphabetically sorted list of everything launchable; hold OK
  on an entry to add it to the home row.
- **Settings / Input** chips: land on the TV's own Settings app and TCL's input panel (Settings
  falls back to `Settings.ACTION_SETTINGS` on other builds instead of doing nothing).
- **Vendor gear key**: an accessibility service catches the remote's gear key (vendor keycode
  `4077`, *not* `KEYCODE_SETTINGS`) from **any** foreground app — single press opens TCL's quick
  panel, double press opens full TV Settings. MENU / GUIDE are deliberately left to the app.
- **HDMI ARC re-arm**: at boot and on screen wake the launcher toggles
  `hdmi_system_audio_control_enabled` off/on, which wakes a soundbar that is sitting in CEC
  standby while the TV already routes audio to ARC.
- **Boot kick for the VPN**: Android TV does not auto-start Tailscale after a reboot; the
  launcher starts it once per boot so remote `adb` access comes back by itself.

## What a replicator needs — and what this repo does not contain

You need your **own** TV, your **own** `adb` path to it (USB or network), a JDK, the Android SDK,
and your **own** signing key. Nothing here phones home: the app has no network code, no analytics,
no accounts.

Deliberately absent from this repository and from its git history:

- the author's keystore, any keystore password, and any signed APK;
- device addresses, tailnet names/keys, tokens, shell history, logs or screenshots of the author's
  TV (`STATUS.md`, `apps.txt`, `out/` are gitignored);
- any credential that would let a reader reach someone else's TV. The only paths in the docs are
  placeholders such as `<TV_IP>`.

So: yes, anyone can build and test this on their own set, and nothing they clone gives them access
to the machine or TV it was developed on.

## Reaching the TV: same Wi-Fi, or a VPN

Two setups are supported and both use plain `adb`; pick the one that matches where the agent runs.

**A — agent on the same network** (laptop, home server, Raspberry Pi, Hermes on your own machine):

```sh
# one-time, over USB, to switch the TV's adbd to TCP
adb devices                       # accept the "Allow debugging" prompt on the TV
adb tcpip 5555
adb connect 192.168.1.50:5555     # then unplug USB

# Android 11 wireless debugging (no USB at all):
#   TV: Settings > System > Developer options > Wireless debugging > Pair device with pairing code
sh tools/connect.sh --pair 192.168.1.50:37123 123456
sh tools/connect.sh 192.168.1.50:5555
```

**B — agent somewhere else, over a VPN such as Tailscale** (this is how the reference set was built:
Hermes on a VPS, TV at home):

```sh
# on the TV: install "Tailscale" from the Play Store, sign in to the same tailnet
tailscale status                  # on the agent machine; find the peer whose OS is android
sh tools/connect.sh 100.101.102.103:5555
```

Details that matter for the VPN path:

- Find the TV by `os: android` in `tailscale status` (or `--json`), not by node name — the owner
  renames the node to anything.
- `adb` over TCP goes **offline while the TV sleeps**. Wake the set, then reconnect.
- Tailscale on Android TV is an app, not a boot service: after a reboot nothing is reachable until
  it is started. That is what the launcher's boot kick is for — it starts Tailscale once per boot
  and re-issues HOME only if another activity took over.
- Never expose port 5555 to the internet. Either keep it on the LAN or reach it through the tailnet
  and restrict it with ACLs.

Whichever path you use, `sh tools/connect.sh` with no arguments will try mDNS discovery first and
then walk you through the failure ("unauthorized" means the prompt on the TV was not accepted).

## Build

Requirements: JDK 17, Android SDK with `platforms/android-34` and `build-tools/34.x`, and `zip`.
No Gradle, no Android Studio.

First, decide which apps your own TV should show — the row compiled into the APK is the first-run
default:

```sh
cp apps.txt.example apps.txt
adb shell pm list packages | sed 's/^package://' | sort | head -30   # pick yours
$EDITOR apps.txt        # one package per line; leave it empty to start with no tiles
```

Then build:

```sh
export ANDROID_HOME=$HOME/android-sdk
export KEYSTORE_PASS='your-keystore-password'   # required; there is no default
sh build.sh          # -> out/atelier.apk
```

`build.sh` signs with `$KEYSTORE` (default `~/.atelier/key.jks`, created on first run if missing).
The keystore is never stored in this repository: whoever holds it can sign an update the TV will
accept silently, so treat it as the device's identity, not as build output. A new keystore means
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — uninstalling to fix that also erases the app row.

`build.sh` compiles resources with `aapt2`, Java with `javac`, dexes with `d8`, aligns and signs
with `apksigner`. Version defaults to 1.1 / code 2 — override with `VERSION_NAME` / `VERSION_CODE`.

## Install on the TV

One script does the whole sequence **and verifies each step**:

```sh
sh tools/install.sh <TV_IP> out/atelier.apk
```

It installs, grants `WRITE_SECURE_SETTINGS`, sets the `APP_AUTO_START` appop TCL requires, starts
the app, enables the accessibility service in the order that actually binds on this firmware, sets
it as HOME, then prints `HOME`, `Bound services` and `Crashed services` and exits non-zero with a
specific message if any of them is wrong (a bound check is the difference between "the gear key
works" and "the gear key silently does nothing").

Doing it by hand, the order matters — start the app **before** enabling accessibility:

```sh
adb connect <TV_IP>:5555
adb install -r out/atelier.apk
adb shell pm grant com.yunar.atelier android.permission.WRITE_SECURE_SETTINGS
adb shell cmd appops set com.yunar.atelier APP_AUTO_START allow
adb shell am start -n com.yunar.atelier/.MainActivity
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services com.yunar.atelier/com.yunar.atelier.AtelierA11y
adb shell cmd package set-home-activity com.yunar.atelier/.MainActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell dumpsys accessibility | grep -E "Bound services|Crashed services"
```

After a normal `adb install -r` with the same key, permissions and the accessibility binding
usually survive. Verify instead of resetting: `dumpsys accessibility` should list Atelier under
`Bound services`. Only if that line is missing, re-run the three `settings put secure` lines —
clearing them while the app is not running is what makes a TCL set mark the service as crashed.

### Where the app row lives at runtime

```
/sdcard/Android/data/com.yunar.atelier/files/apps.txt      one package per line
/sdcard/Android/data/com.yunar.atelier/files/apps.txt.bak  previous version, kept automatically
/sdcard/Android/data/com.yunar.atelier/files/title.txt     the wordmark (optional)
```

Precedence: the on-device file wins; a **missing** file means "first run" and the row compiled into
the APK is used; an **empty** file is a real empty row. Writes are tmp-file + fsync + rename, so a
power cut cannot truncate the row. The file survives `adb install -r` and is deleted by uninstall.

## Removing it

`uninstall` while Atelier is still HOME leaves the TV with no home screen. Use the script, which
re-enables the stock launcher, points HOME back at it, proves that HOME resolves, and only then
offers to remove the package:

```sh
sh tools/uninstall.sh <TV_IP>                  # restore stock launcher, keep the APK
sh tools/uninstall.sh <TV_IP> --uninstall-apk  # restore first, then uninstall
```

By hand, in this order (the stock launcher's activity is `.home.HomeActivity`, not
`.MainActivity`):

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
# only when the line above no longer mentions com.yunar.atelier:
adb uninstall com.yunar.atelier
```

## Repo layout

```
src/com/yunar/atelier/MainActivity.java   home screen, pages, key handling
src/com/yunar/atelier/AtelierA11y.java    accessibility service for the vendor gear key
res/                                      strings + accessibility service config
AndroidManifest.xml                       permissions, HOME + LEANBACK intent filters
build.sh                                  aapt2 + javac + d8 + zipalign + apksigner
tools/install.sh, tools/uninstall.sh      verified install / safe removal
tools/*.sh                                read-only diagnostics (storage, CEC audio, wake)
docs/DEVICE-NOTES.md                      the TCL-specific findings, in detail
```

## Licence

MIT. See [LICENSE](LICENSE).
