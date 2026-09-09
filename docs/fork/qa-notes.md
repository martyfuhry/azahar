# Field QA notes

Observations from Marty playing on the AYN Thor, with corrections to agent diagnoses.

## thor-v1-rc1 (2026-09-08 evening / 2026-09-09 morning)

**Controller auto-mapping worked.** The Thor's built-in pad was seeded correctly on first
launch with no manual mapping, and play was reported as working well with performance fine.

**This corrects the stuck-gamepad diagnosis.** `fix/resume-input-focus` concluded from code
that the pad had no bindings, so an unbound `KEYCODE_BUTTON_B` fell through Android's
key-character-map fallback to `BACK`, opening the drawer. Since the pad *was* mapped, that
chain cannot have been the trigger on this device. The likelier cause of the reported
symptom (game running, bottom-screen touch fine, every pad button driving the drawer/menus,
immediately after an autosave resume) is `DrawerLayout` restoring itself **open** from saved
instance state after Android killed the backgrounded process, which commit `4113f2d86`
(`onViewStateRestored` closes a restored-open drawer) addresses directly.

The unbound-key swallowing (`386d4cbd6`) remains correct and worth keeping: it protects any
device or moment where a binding genuinely is missing. But it is not the fix that mattered
here, and a future session should not treat "the pad was unmapped" as established.

**Still unconfirmed:** neither fix has been verified on device. The `uinput` repro harness in
`~/Development/azahar-builds/resume-input-repro/` exercises the unbound-key path; the
restored-open-drawer path needs a real process kill while backgrounded, then a relaunch.

## Savestates DO survive between our release candidates (2026-09-09)

Marty loaded an rc1-created autosave in rc2, in Animal Crossing, with no trouble. The release
notes for both rc1 and rc2 say savestates from another build are rejected by design and tell
him to save in-game before upgrading. **That is wrong for our own builds**, and the reason is
a change we made ourselves.

`ValidateSaveState` (`src/core/savestate.cpp:78-91`) has three outcomes, not two: revision
matches means `OK`; revision differs but `Common::g_build_version` matches means
`RevisionMismatch`, which still loads; only a differing build version is `BuildMismatch`, and
the autosave resume path rejects only that (`:196`). rc1 and rc2 share an upstream base
version and differ only by git revision, so they are compatible.

Before the autosave work, `MakeHeader` left `build_version` blank in **file** headers, so
every cross-revision file state landed in `BuildMismatch` and was refused. `MakeHeader` now
writes it (`:227-229`), which is what makes this work; the commit body called it intended.

**So:** upgrading between our release candidates preserves the autosave. It would only break
on a rebase onto a new upstream release, where `g_build_version` itself changes.

**Done 2026-09-09:** the offending bullet has been corrected in place in both
`releases/thor-v1-rc1.md` and `releases/thor-v1-rc2.md`, struck through rather than deleted so
the record of the mistake survives.

## The Thor's own records, and what they corrected (2026-09-09)

A one-minute read-only `adb` snapshot of the Thor, the first data ever taken from the device:
`baselines/2026-09-09-thor-device-snapshot.md`. It corrected four things at once.

**The device identifiers were invented.** `ThorDefaults.knownDevices` matched manufacturer
`ayn` plus `thor` in `Build.MODEL` *or* `Build.DEVICE`. The real strings are manufacturer
`AYN`, model `AYN Thor`, brand `qti`, device and name both `kalama`. The manufacturer+model
half happens to work; the `Build.DEVICE` fallback was a live hazard, because `kalama` is
Qualcomm's SM8550 platform name and is shared with unrelated hardware — a vanilla install on
any of them would have got a two-panel 4x profile. Fixed, with the trap written into the code.

**melonDS was not being killed for memory — and then the replacement theory was wrong too.**
The whole melonDS fork was justified by a low-memory reclaim during sleep. The Thor has no
`LOW_MEMORY` record and no excessive-CPU record; it has **eight** foreground native crashes
between 2026-08-24 and 08-29, across three different fatal signals.

The first replacement theory was upstream #1624, fixed nightly-only in a commit his installed
2.0.1 provably predates. That is **also ruled out**: `cc6c3ba3` is a one-line Kotlin change, so
its failure mode is `reason=4 APP CRASH(EXCEPTION)` and every melonDS loss here is `reason=5
APP CRASH(NATIVE)` — and the device records both reasons, hours apart, on 09-08. Independently,
three different fatal signals cannot come from one null dereference. **The cause is unknown**,
the stack is unrecoverable (≈3-day retention, eleven-day-old crashes), and nothing in the fork
or upstream is known to fix it. Corrected in `melonds-android/RELEASE-NOTES.md`,
`melonds-android/THOR-NOTES.md`, `upstream-candidates.md` and the snapshot itself.

Worth noting for the pattern below: **the wrong replacement theory was written into three
documents before the second look at the device overturned it.** Speed of correction is not the
same as being right; the fix was still a guess dressed as a finding.

**The excessive-CPU kill is a Fold5 fact.** `upstream-issues/01` asserted the paused-audio
burn "gets the process killed" and made *a Thor kill record* a precondition for filing. There
is no such record and there may never be one. Scoped to the burn, which is general, plus one
device's kill record, labelled as one.

**The GPU driver differs.** Thor 512.676.53 against the Fold5's 512.676.1. Same Adreno 740,
different driver build, and three of our four crash signatures are inside the driver.

### The pattern, now seen four times

The stuck-gamepad diagnosis, the savestate build-locking claim, the melonDS memory theory, and
its #1624 replacement were all confident conclusions reached from code and from the lab phone,
written up as findings, and contradicted as soon as the actual device was consulted. Each time
the mechanism was real *somewhere* — it just was not what was happening here.

The fourth is the instructive one, because it happened *during the correction of the third*. A
partial read of the device (two crashes) produced a new theory that fitted beautifully, and a
fuller read the same day (eight crashes, three signals) destroyed it. The lesson is not only
"read the device" but **read all of it, and check what the evidence would look like if the
theory were false.** One `getprop` on the fix's file extension — Kotlin, therefore
`reason=4`, therefore not these crashes — would have killed #1624 in a minute.

The cheap countermeasure is still the right one: **read the device before writing the
explanation.** `dumpsys activity exit-info <pkg>`, `dumpsys dropbox`, `dumpsys usagestats` and
`getprop` take a few minutes, need no install, change nothing, and would have caught all four.
Prefer them to another hour of source reading whenever a claim is about what a device *did*.

And note the retention window that cost us the melonDS stack: **DropBox keeps crash records
for about three days.** Read a crash within three days of it happening or it is gone.

## For the next release's notes: hardware shaders

The Thor's live `config.ini` was found with **`use_hw_shader = false`** in `[Renderer]`,
alongside `resolution_factor = 4` (`baselines/2026-09-09-thor-diagnostics.md` §7). That is
software vertex shading at the most expensive resolution: `pica_core.cpp:1068` gates
`accelerate_draw` on the setting, so with it off the 3DS geometry pipeline is interpreted on
the CPU rather than handed to the GPU. Slower, hotter, worse battery.

**Nothing in the fork wrote it.** The default is `true` in `settings.h:560` and
`BooleanSetting.kt:97`, neither `ThorDefaults` nor `GraphicsPresets` touched it, and it is
exposed in the Android settings UI, so it was toggled off by hand. No bug to hunt.

It is now written by **`GraphicsPresets`** — all three tiers, Battery saver included — so any
preset puts it back, and `ThorDefaults` picks it up through the Best-looking profile.

**The bullet the next release's notes owe him:** the first-run profile only fills in keys
`config.ini` has no value for, so **an upgrade will not repair this on its own**. Anyone who
has been running with hardware shaders off gets them back only by running
**Settings > System > AYN Thor > "Apply Thor defaults"**, by picking any graphics preset, or by
flipping *Enable Hardware Shader* by hand. Worth saying explicitly, because "why is it slow at
4x" has an answer that is not in any of our code.

No restart is required: the value is read per draw, and `HW_SHADER` is not in
`BooleanSetting.NOT_RUNTIME_EDITABLE`. The one caveat is OpenGL-only — the disk shader cache is
refused at renderer init when hardware shaders are off (`gl_shader_disk_cache.cpp:115`), so a
mid-session enable on the GL renderer compiles shaders as it goes until the next boot. Vulkan,
which is what the Thor profile selects, has no equivalent gate.
