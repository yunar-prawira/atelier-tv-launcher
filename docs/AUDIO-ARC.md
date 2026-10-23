# Optional: HDMI ARC soundbar repair

**This is a good-to-have, not a reason to use this launcher.** Most setups never need it. If your
TV and soundbar come back from standby with sound every time, skip this file entirely — the code is
inert unless the link is actually down.

## The symptom

TV on, soundbar awake on paper, no audio, and nothing on the TV speakers either. Switching inputs or
restarting sometimes fixes it. On this set it happened after the TV slept for hours, not after a
reboot.

## What is actually happening (measured, this set)

1. The bar goes to standby on its own (Sony auto-standby, ~20 min without signal) and reports
   `<Report Power Status>` = `01` (standby) plus `<System Audio Mode Status>` = `OFF`.
2. The TV reads "system audio off" as "the user does not want ARC": it logs
   `setSystemAudioMode:on=false`, sends `<Request ARC Termination>` and **never** sends a wake
   request (`<Set System Audio Mode>`, `<Image View On>`). It only polls
   `<Give Device Power Status>` — a query, not an instruction.
3. Deadlock: the bar waits to be woken, the TV refuses ARC. Meanwhile the audio route stays pinned
   at `hmdi_arc` with `mArcEstablished=false`, so there is **no fallback to the TV speakers** — the
   room is silent rather than degraded.
4. When the bar finally does ask (`<Initiate ARC>`), a wedged TV answers
   `<Feature Abort> 05:00:C0:00` — abort code `0x00` is literally "refused".

## What this app does about it

- **Only when the link is down** (`persist.tcl.isARCConnect != 1`):
  `hdmi_control_enabled` off → **15 s** → on → verify after 12 s → retry once, with a 5-minute
  cooldown. It never touches a healthy handshake; the first version toggled blind and made things
  worse.
- **The gap is the whole trick.** Measured on the device: 2.5 s and 6 s gaps never rebuilt ARC (and
  could tear a working one down). 15 s worked on the first try, and held for repeated samples.
- Triggered from `onStart` (home shown, +8 s) and from the screen-on edge — **not** gated on window
  focus, because the owner usually power-ons straight into a streaming app.
- Every attempt appends to `arc.log` in the app's external files dir. The system logcat ring here is
  256 KiB and rotates within minutes, so it is not evidence an hour later.

## Before you blame the TV

Check the soundbar first — it is usually cheaper to fix:

- **Auto-standby.** On a Sony HT-S100F/S200F: hold **CINEMA** for 5 s. The USB indicator flashes
  **twice** = auto-standby on, **once** = off. Turning it off removes the trigger for the whole
  failure, with no software involved.
- **Control for HDMI** on the bar (HT-S100F/S200F: hold **VOICE** 5 s, USB LED flashes twice).
- **Wiring.** ARC only works on the port marked `arc: true` in `dumpsys hdmi_control` (HDMI 3 here),
  and a marginal cable breaks CEC long before it breaks video.
- **Connection type.** Optical carries no CEC and has no wake path; Bluetooth is a pairing problem,
  not a CEC one. Do not debug CEC on a non-HDMI link.

## What is not portable here

The lever (`hdmi_control_enabled`) is an AOSP global setting, so it likely exists on other sets.
The **timing** and the **diagnosis** above are the parts measured on one TCL firmware — treat them
as a hypothesis until you have watched your own CEC history. The health probe
(`persist.tcl.isARCConnect`) is a vendor property and must be replaced or dropped.
See [`COMPATIBILITY.md`](COMPATIBILITY.md).
