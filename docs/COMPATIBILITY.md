# Compatibility — what is universal, what is TCL-only, what is untested

Every row says **what it is**, **why it is flagged that way**, and **what to do on another set**.
The honest summary: the launcher UI is portable, the remote channel is portable, the *service
layer* is where vendor firmware shows up.

Legend: **UNIVERSAL** = platform behaviour, expect it to work anywhere · **TCL** = depends on this
vendor's firmware/properties/keys · **UNTESTED** = plausible elsewhere but never verified on a
second brand, so treat it as a hypothesis, not a feature.

## Core launcher

| Piece | Flag | Notes / what to do on another set |
|---|---|---|
| Home screen, app row, drawer, dock, clock, backdrop | UNIVERSAL | Plain platform views, API 21+. No vendor APIs, no Gradle. |
| Replacing the stock HOME (role + `set-home-activity` + disabling the vendor launcher) | UNIVERSAL mechanics, **per-firmware procedure** | The commands are AOSP. *Which* packages are safe to disable is model-specific — resolve HOME before and after, keep an undo script, never disable every launcher. |
| Beating the setup-wraith recovery activity to own HOME | TCL | On Google TV builds the winner after disabling `launcherx` was `com.google.android.tungsten.setupwraith/.RecoveryActivity`. Other firmware will fail differently. |
| Gear-key remap (**keycode 4077**) | TCL | Vendor remotes do not send stock codes. Re-discover the code per remote — the cheapest oracle is the vendor IME's log (`TCL_IME…getKeyCode =`). |
| Accessibility service binding (needs `APP_AUTO_START` appop) | TCL | TCL refuses to bind a sideloaded a11y service without `cmd appops set <pkg> APP_AUTO_START allow`. Remove the workaround if your set binds normally. |
| Long-press on a D-pad, popup focus, "arm on key-down" | UNIVERSAL | Platform behaviour, applies to every remote. |
| `QUERY_ALL_PACKAGES` + `<queries>` | UNIVERSAL | Android 11+ requirement, or every icon/label lookup throws. |

## Remote access (the part that survives sleep)

| Piece | Flag | Notes / what to do on another set |
|---|---|---|
| Outbound link (`NetLink` + foreground `ControlService`) | UNIVERSAL | Standard HTTP + standard Android service. Nothing vendor-specific. |
| `INTERNET` permission is mandatory | UNIVERSAL | Without it **every** `socket()` fails with `EPERM`, which reads like a firewall. |
| `usesCleartextTraffic="true"` | UNIVERSAL | Needed for plain-HTTP to a private host on target 34. |
| Foreground service instead of a thread | UNIVERSAL pattern, TCL trigger | Something reaped the process here (`TGuardService … not depends on com.android.vending`). Other vendors have their own reapers; the pattern holds. |
| `adbd` dies on deep sleep and the TCP port stays closed | UNIVERSAL symptom, **untested fix elsewhere** | Measured here: port closed while `persist.adb.tcp.port=5555` still read 5555, and the set had **not** rebooted. Whether other brands restart `adbd` on wake is unknown — if yours does, the link is optional. |
| Reading state with app APIs instead of `dumpsys` | UNIVERSAL | An untrusted uid gets `Can't find service` / `Permission denied` on `/proc` everywhere. |

## Optional: HDMI ARC soundbar repair

**Good to have, not required.** Only sets whose soundbar sits in CEC standby while the TV keeps the
route pinned to ARC need this. Plenty of setups never see it. Details in
[`AUDIO-ARC.md`](AUDIO-ARC.md).

| Piece | Flag | Notes / what to do on another set |
|---|---|---|
| Cycling `hdmi_control_enabled` off → 15 s → on | **UNTESTED elsewhere** | The setting is AOSP, so the lever probably exists on other sets. Only the *sequence and the gap* were measured — on this set, 2.5 s and 6 s gaps never rebuilt ARC; 15 s did, first try. |
| Skip when the link is already healthy | UNIVERSAL | Never tear down a working handshake: a blind toggle is what silenced the room in v1.3. |
| Health probe via `persist.tcl.isARCConnect` | TCL | Vendor property. Substitute your model's equivalent, or drop the probe and rely on the timing retry. |
| `earc_status`, `persist.tcl.*`, `mArcEstablished` | TCL | All vendor/`dumpsys` diagnostics. Not part of the app's runtime path. |
| Diagnosing "soundbar silent": `<Initiate ARC>` answered with `<Feature Abort>` | UNIVERSAL CEC semantics, TCL behaviour | The CEC messages are standard; *this* firmware refusing ARC after wake is not. Read the same history on your set before assuming. |
| Keeping the soundbar awake (Sony HT-S100F: hold **CINEMA** 5 s → USB LED flashes once) | UNIVERSAL advice, model-specific button | The root cause is often the bar's own auto-standby, not the TV. Check the bar's manual first — that fix needs no code at all. |

## Not planned

- **Non-Android TVs** (Tizen, webOS, Roku): no `adb`, no `pm`, different home-screen model. This
  repo does not apply.
- **Phones/tablets**: different OTA and role constraints; the design decisions here assume a D-pad.
