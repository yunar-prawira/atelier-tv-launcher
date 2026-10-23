# Driving this project with an AI agent (any agent, not just Hermes)

This repo was built in a conversation with an AI agent — Hermes Agent — but **nothing in the method
depends on Hermes**. The agent only needs four capabilities:

| Capability | Why it is needed | Anything that has it |
|---|---|---|
| Run shell commands | build the APK, `adb` calls | Claude Code, Codex CLI, Cursor + terminal, OpenHands, a home-grown loop |
| Read images | judge a `screencap` against a mockup | any vision-capable model |
| Keep a working file | notes, checklists, build output | the agent's own scratch folder |
| Survive across sessions | resume without re-discovering the device | a `STATUS.md` / notes file in the repo |

If the agent cannot see images, it can still build and install, but it cannot verify the UI — it
will "finish" a screen nobody has looked at. That is the one capability worth insisting on.

What the agent **cannot** do, no matter how good it is: press the physical remote, approve the
"Allow debugging" prompt, decide that a vendor launcher may be disabled, or judge whether the
result looks premium. Those stay with the owner. Design the loop around that split.

---

## The loop (repeat until the device agrees)

```
recon (read-only)  ->  propose + get approval  ->  change  ->  verify from the device  ->  record
      ^                                                                                    |
      +------------------------------------------------------------------------------------+
```

### 1. Recon — read, never write

Ask the agent to prove it can see the device before it plans anything:

```sh
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell wm size && adb shell wm density
adb shell dumpsys power | grep -i wakefulness
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME
adb shell pm list packages -d | wc -l          # already-disabled packages
adb exec-out screencap -p > shot.png           # what the owner sees right now
```

Two facts from this step decide everything later: **the screencap resolution** (a 4K panel usually
composes at 1080p, so all design work is measured against the capture, not the panel spec) and
**which activity currently owns HOME** (that is the rollback target).

### 2. Propose — the agent writes the plan, the owner approves the risk

The agent should output, before touching anything: the packages it wants to disable with plain
names, what it will replace, the exact rollback command, and what the TV looks like if the step
fails. Approval happens once, in chat, not per command.

Red lines worth stating in the prompt:

- never disable **both** the vendor launcher and the setup app in the same step;
- never `pm uninstall` a system package (only `pm disable-user`, which is reversible);
- never put a keystore or a signed APK into the repo;
- never leave the set without a working HOME; assert it after every change.

### 3. Change — one reversible action at a time, then read it back

Every write is followed by a read that proves it landed:

```sh
adb shell pm disable-user --user 0 <pkg> && adb shell pm list packages -d | grep -c <pkg>
adb install -r out/atelier.apk && adb shell dumpsys package com.yunar.atelier | grep versionName
adb shell input keyevent KEYCODE_HOME && \
  adb shell dumpsys activity activities | grep -m1 mResumedActivity
```

`adb` returning exit 0 is not evidence. The second half of each line is the evidence.

### 4. Verify — screenshots plus state dumps, never vibes

A UI change is only done when three independent readings agree:

| Reading | Command | What it catches |
|---|---|---|
| pixels | `adb exec-out screencap -p > shot.png`, then look at it | layout overflow, invisible focus, clipped labels |
| state | `dumpsys activity` / `dumpsys accessibility` / `dumpsys package` | wrong activity resumed, service not bound, stale version |
| log | `adb logcat -d -b crash`, plus the app's own tags | crashes and swallowed keys that the screenshot still looks fine for |

Three traps this catches, all of which look like "it works":

- a focus ring drawn on a child view is invisible while `dumpsys` shows focus moving — press one
  D-pad step and look at the screenshot, a static capture cannot reveal it;
- a tile row that overflows off-screen keeps the app tiles you expected and silently drops the
  last one (the `ALL APPS` door), because nothing errors;
- `KEYCODE_POWER` puts the set to sleep but networking stays up for a while, so a "TV is off"
  claim needs a ping check, not a keypress.

### 5. Record — the next session starts from notes, not from zero

Keep a `STATUS.md` in the project (gitignored if it mentions the owner's network) split into
**verified done** / **not done + the exact next action**, plus the device's quirks. An agent that
resumes reads that file, re-runs the recon block, and continues — instead of re-discovering the
keycode of the gear button.

---

## Ground truth hierarchy

When sources disagree, the agent should trust them in this order:

1. **the device's own output** (`getprop`, `dumpsys`, `logcat`, a screenshot);
2. **this repo's `docs/DEVICE-NOTES.md`** — quirks measured on the same firmware;
3. **Android/AOSP documentation**;
4. **the model's memory of how Android "usually" works** — last, and marked as an assumption.

Vendor firmware wins over documentation every time: on TCL `BeyondTV4`, `cmd hdmi_control` has no
shell implementation, the gear key is keycode 4077, and a sideloaded accessibility service refuses to
bind without the `APP_AUTO_START` appop. None of that is in AOSP docs.

---

## Design work: measurable mockups, not generated pictures

The design conversation that produced this launcher went: reference screenshots from the owner →
hand-written SVG at the capture resolution → PNG renders → implementation in `px()`-scaled layout →
screenshot diff. Two rules make that loop work with an agent:

- **Never ask an image model for UI.** It produces elements the launcher cannot express and pixels
  nobody can measure. Draw the screen as SVG at the exact `screencap` size (1920×1080 here), render
  it, and keep the numbers (font sizes, baselines, gaps) in a table next to it.
- **Express layout in the mockup's coordinate space, then scale once.** `px(v) = v * widthPixels /
  1920` makes the composition hold at any density, so the mockup numbers can be typed straight into
  the code instead of being re-derived per device.

Feedback rounds are cheap this way: the owner says "clock on the right, but keep the icon neatness",
the agent produces a new render in minutes, and only the approved render becomes code.

---

## A prompt skeleton that works

```
Goal: <what changes on the TV, in one sentence>.
Constraint: never leave the set without a working HOME; every write needs a read-back.
Device: adb target <host:port>; read docs/DEVICE-NOTES.md first.
Environment: Debian VM, JDK 17, Android SDK at <path>, no Gradle.
Method: recon -> plan (wait for my approval) -> change -> verify (screenshot + dumpsys + logcat)
        -> record in STATUS.md.
Report: what changed, how it was verified, what is still unverified, and the rollback command.
```

The last line matters more than it looks: an agent that separates **verified** from **plausible**
is the difference between a usable repo and a bug report with a nice commit message.

---

## What is Hermes-specific here (and why it does not matter)

Hermes supplied the shell tool, the vision tool, the persistent notes and the ability to hand a
sub-task to a second agent for an audit. Equivalent, in other stacks:

| Hermes feature | Generic equivalent |
|---|---|
| terminal tool | any shell tool (Claude Code `Bash`, Codex CLI, plain `subprocess`) |
| vision on a screenshot | any multimodal model call with the PNG attached |
| persistent memory / notes file | `STATUS.md`, `CLAUDE.md`, `AGENTS.md`, a notes dir |
| delegated audit subagent | a second agent/chat with the repo path and a read-only brief |
| skills (reusable procedures) | `docs/` files the agent is told to read first |

So the transferable asset is the **order of operations and the evidence rules**, not the runtime.
Point any competent agent at this file plus `docs/DEVICE-NOTES.md` and it will repeat the project
instead of rediscovering the traps.
