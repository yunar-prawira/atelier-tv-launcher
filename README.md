# Atelier — a quiet home screen for a TCL Google TV

A ~29 KB Android TV launcher that replaces the ad-filled stock home screen, written in plain
Java and built **without Gradle or Android Studio**. It is deliberately boring: a clock, one
row of apps you choose, and a dock with Input / Applications / Settings / Edit.

Everything here was developed against one specific set: **TCL 43A30 (Smart TV Pro),
Android 11, platform `BeyondTV4`, 2 GB RAM**, driven only by a D-pad remote, reached over
`adb` on a Tailscale network. Vendor firmware outranks documentation, so the quirks in
[`docs/DEVICE-NOTES.md`](docs/DEVICE-NOTES.md) are the most valuable part of this repo.

## What it does

- **Home screen**: clock + date, one justified row of up to 10 apps, no ads, no "recommended" rows.
- **Edit**: the `Edit` chip turns the row into a management surface. Press OK on a tile to get
  `Move` / `Remove from home` / `Uninstall`; `Move` then uses the D-pad left/right, OK to drop.
- **Applications**: a full-screen, alphabetically sorted list of everything launchable; hold OK
  on an entry to add it to the home row.
- **Settings / Input** chips: land on the TV's own Settings app and TCL's input panel.
- **Vendor gear key**: an accessibility service catches the remote's gear key (vendor keycode
  `4077`, *not* `KEYCODE_SETTINGS`) from **any** foreground app — single press opens TCL's quick
  panel, double press opens full TV Settings.
- **HDMI ARC re-arm**: at boot and on screen wake the launcher toggles
  `hdmi_system_audio_control_enabled` off/on, which wakes a soundbar that is sitting in CEC
  standby while the TV already routes audio to ARC.
- **Boot kick for the VPN**: Android TV does not auto-start Tailscale after a reboot; the
  launcher starts it once per boot so remote `adb` access comes back by itself.

## Build

Requirements: JDK 17, Android SDK with `platforms/android-34` and `build-tools/34.0.0`.
No Gradle, no Android Studio.

```sh
export ANDROID_HOME=$HOME/android-sdk
export KEYSTORE_PASS='your-keystore-password'   # required; no default, no key in the tree
sh build.sh          # -> out/atelier.apk
```

`build.sh` signs with `$KEYSTORE` (default `~/.atelier/key.jks`, created on first run if missing).
The keystore is never stored in this repository: whoever holds it can sign an update the TV will
accept silently, so treat it as the device's identity, not as build output.

`build.sh` compiles resources with `aapt2`, Java with `javac`, dexes with `d8`, aligns and
signs with `apksigner`. The compiled-in default row comes from `apps.txt`; if that file is absent
the script copies `apps.txt.example` (the file is gitignored because it is per-device state).
Version defaults to 1.1 / code 2 — override with `VERSION_NAME` / `VERSION_CODE`.

## Install on the TV

```sh
adb connect <TV_IP>:5555
adb install -r out/atelier.apk

# settings writes (ARC re-arm) and default-home role need these:
adb shell pm grant com.yunar.atelier android.permission.WRITE_SECURE_SETTINGS
adb shell cmd appops set com.yunar.atelier APP_AUTO_START allow

# let it be HOME
adb shell cmd package set-home-activity com.yunar.atelier/.MainActivity

# accessibility service (gear key). Order matters on TCL — see DEVICE-NOTES.md
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services com.yunar.atelier/com.yunar.atelier.AtelierA11y
```

Then confirm:

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell dumpsys accessibility | grep "Bound services"
```

Reinstalling the APK resets the adb-granted permission and the accessibility binding; re-run the
two command blocks above after every `adb install -r`.

## Removing it

```sh
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.MainActivity
adb install -r out/atelier.apk   # or simply:
adb uninstall com.yunar.atelier
```

If `launcherx` and the setup wrapper were disabled (see the debloat notes), re-enable them first
with `adb shell pm enable <package>` before uninstalling this launcher — a TV with no HOME
activity is a dead TV.

## Repo layout

```
src/com/yunar/atelier/MainActivity.java   home screen, pages, key handling
src/com/yunar/atelier/AtelierA11y.java    accessibility service for the vendor gear key
res/                                      strings + accessibility service config
AndroidManifest.xml                       permissions, HOME + LEANBACK intent filters
build.sh                                  aapt2 + javac + d8 + zipalign + apksigner
tools/                                    read-only device diagnostics (adb)
docs/DEVICE-NOTES.md                      the TCL-specific findings, in detail
```

Signing: `key.jks` is generated locally and is **not** in this repo. Publish with your own key.

## Licence

MIT. See [LICENSE](LICENSE).
