# Device notes — TCL 43A30 / Google TV / Android 11 (`BeyondTV4`)

Every line here cost a debugging session. Vendor firmware outranks both documentation and
intuition: verify with `dumpsys`, `getprop` and `logcat` on the actual set before believing any
of it applies to yours.

## Reaching the TV without a laptop on the same Wi-Fi

- Install **Tailscale for Android TV** on the set, same tailnet as the machine that runs `adb`.
  `adb connect 100.x.y.z:5555`.
- Android TV does **not** start Tailscale after a reboot. `always_on_vpn_app` is not enough: the
  VpnService is never started at boot. The launcher starts Tailscale once per boot (guarded by a
  boot-id in SharedPreferences) and re-issues HOME ~2.6 s later. Verified: adb answered again
  ~78 s after a reboot with nobody touching the remote.
- Find the TV among tailnet peers by `os: android` in `tailscale status --json`, not by name.
- adb over TCP goes `offline` while the set sleeps; a wake experiment cannot inject the waking
  key, so let the owner press power and poll for the device to answer.

## Replacing HOME

`cmd package set-home-activity` and the `android.app.role.HOME` role are not sufficient on
TCL/Google TV. After disabling `com.google.android.apps.tv.launcherx` the winner becomes
`com.google.android.tungsten.setupwraith/.RecoveryActivity`. The working sequence:

1. `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
   — note the current winner before touching anything.
2. `settings get global device_provisioned` and `settings get secure user_setup_complete` must both
   be `1`.
3. Disable **both** `setupwraith` and `launcherx`, then re-resolve. If HOME is not your package,
   re-enable both immediately — a TV with no HOME activity is a dead TV.

## D-pad focus: overlays do not own input here

A full-screen `FrameLayout` added as a sibling of the home view **does not reliably hold D-pad
focus** on this firmware. Symptoms: the highlight moves inside the overlay but OK activates the
tile behind it (Netflix opens instead of the menu item).

What did not fix it: `setFocusable(true)` + `requestFocus()`, an anti-stray click guard, tiles
returning `false` for every key while the overlay exists.

What did: **stop stacking overlays**. Show menus and lists as their own content view
(`setContentView(page)`), so the tiles are not in the view tree at all. Returning from a page is
`setContentView(root)` plus a focus restore.

## Key-up after a rebuild launches an app

A key-down handler that rebuilds the view tree (leaving move mode, choosing a menu action,
adding an app) is followed milliseconds later by the matching key-up. If that key-up lands on a
freshly focused tile, the framework turns it into a click and the app opens by itself.

`View.onKeyUp` fires the click for OK/ENTER on any clickable view. As a remote "hold" is a key
event and not a touch long-press, the tile must drive clicks itself:

- arm the tile only in **its own** `onKeyDown` (`armed = true`), fire `performClick()` in
  `onKeyUp` only when `armed`, then clear it;
- a rebuilt tile never saw the key-down, so it can never fire on a stray key-up;
- set `event.startTracking()` in `onKeyDown`, handle `onKeyLongPress`, and clear `armed` there so
  the hold does not also count as a click;
- while a modal mode owns the D-pad (e.g. move mode), the tile must return `false` in
  `onKeyDown` so the activity sees the key, and swallow `onKeyUp` so it does not click.

## Index numbers are not identities

A move mode that stores `moveIndex` breaks as soon as the list is re-read from disk and got
shorter — `Collections.swap(order, 9, 8)` on a 9-element list throws
`IndexOutOfBoundsException: Index: 9, Size: 9`, killing the launcher mid-interaction. Store the
**package name** being moved and re-derive the index on every key press; bounds-check both ends;
reconcile the stored list against installed packages on every rebuild.

## Uninstall from the launcher needs a permission

`Intent.ACTION_DELETE` with `package:<pkg>` resolves and starts the system uninstaller, which
then closes itself immediately:

```
E/UninstallerActivity: Uid 10066 does not have android.permission.REQUEST_DELETE_PACKAGES or android.permission.DELETE_PACKAGES
```

Declare `<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />`; it is a
normal permission and needs no runtime grant. Preinstalled apps (Netflix, YouTube) only offer
"replace with the factory version", which is the firmware's decision, not a bug in the launcher.

## Accessibility service: TCL blocks sideloaded services

- Binding fails with `forbid bind service ... callee_does't_have_OP_AUTO_START_permission` until
  `adb shell cmd appops set <pkg> APP_AUTO_START allow` is set.
- Enable it **after** the app process is running. Enabling the setting and then force-stopping
  the app marks the service `Crashed services:{...}` and it never rebinds; the settings string
  still reads `enabled=1`, which makes this look like a launcher bug. Correct order:

```sh
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services <pkg>/<pkg>.AtelierA11y
adb shell dumpsys accessibility | grep "Bound services"
```

- Trust `Bound services:` in `dumpsys accessibility`, never the settings string.
- The vendor gear key is **keycode 4077**, found in the vendor IME's log
  (`TCL_IME********onKeyDown.eventgetKeyCode =4077`). `getevent` through the adb pipe prints
  nothing on this stack, and injected `input keyevent` cannot validate accessibility key
  filtering — the physical remote is the only real test.
- The double press is the escape hatch: TCL's quick panel is stateful and stops appearing once
  shown, so map a second press within 2.5 s to full `com.android.tv.settings/.MainSettings`.

## HDMI ARC / soundbar silent after wake

Symptom: picture fine, soundbar silent, while `dumpsys audio` already routes to `hmdi_arc`
(firmware spelling) and `mSystemAudioActivated: true`, `mSystemAudioMute: false`, volume e.g. 34.

Cause seen here: the soundbar is still in **CEC standby** (`power_status: 0` on its CEC device
entry) while the TV pushes audio to ARC, so the TV talks to an amplifier that is not awake.
Resending `<System Audio Mode Request>` by toggling the setting forces a fresh handshake and the
soundbar wakes:

```sh
adb shell settings put global hdmi_system_audio_control_enabled 0
sleep 2
adb shell settings put global hdmi_system_audio_control_enabled 1
```

The launcher does exactly this at boot and when the screen comes back on, using
`WRITE_SECURE_SETTINGS` (grant with `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`).
No private-DNS ad blocking on a Tailscale-connected set: the VPN network reports
`Private DNS is broken` and *all* resolution dies.

## Other things that do not exist on this firmware

- `cmd hdmi_control` → `No shell command implementation.` No CEC shell path.
- Picture / Sound deep links: `com.tcl.settings/.PictureActivity` destroys itself when started
  from outside TCL's own Settings flow; `com.android.tv.settings/.device.displaysound.SoundActivity`
  returns to the caller; the quick-menu broadcast is protected by a TCL signature permission.
- `dumpsys meminfo` blocks and returns `SERVICE 'meminfo' DUMP TIMEOUT`; read
  `cat /proc/meminfo` (`MemAvailable`) and `ps -A -o RSS,NAME --sort=-RSS` instead.
- `screencap` returns 0 bytes while a `FLAG_SECURE` app is foreground (most DRM players): send
  `KEYCODE_HOME` first.
- Kernel has `ntfs`/`ufsd` but **no `exfat`** — a portable HDD formatted exFAT will never mount.
