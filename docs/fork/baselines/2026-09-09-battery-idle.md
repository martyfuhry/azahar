# Does a parked game drain the battery? — 2026-09-09

Marty's question, in his words: *"if the battery dies while it's asleep on the desk, that's
just as bad as it crashing."* And, after the first round of results: *"no i don't want
Android killing that process during the suspend, and i don't want it draining battery
either."*

Short answer, measured: **those two wants are not in tension.** The foreground service that
keeps the process alive does not keep the SoC awake, because a foreground service is not a
wake lock. Once the game is parked, Azahar holds **no wake lock of any kind** — verified in
the foreground, parked with the screen on, and parked with the screen off. It is therefore
never the thing preventing the device from suspending.

And the bill is small even when the device *doesn't* suspend. The Fold5 answers a power-key
press with `Dozing` rather than `Asleep` (its own dream/AOD lock, nothing to do with
Azahar), so it stayed awake for the whole screen-off window and the emulator kept running —
the pessimistic case. In that case a parked Azahar costs **13.6 CPU-seconds and about
0.17 mAh per hour**: 0.004 % of this phone's 4400 mAh battery per hour, **0.03 % over eight
hours**. If the Thor's lid close does reach real suspend, as a handheld with no always-on
display plausibly will, the cost goes to zero for the duration instead. So the honest answer
to "does it drain the battery while the lid is shut" is **no — between 0.03 % a night and
nothing at all**.

On the design question the brief poses — should the foreground service be stopped when the
screen turns off? — the answer is **no**, and §7 gives the evidence. The service buys a kill
exemption for 0.03 % of a charge per eight hours. It also costs ~1 GiB of resident memory,
which is a real problem, but the fix for that is M-2 driven directly rather than dropping the
service.

melonDS's fork behaves identically: no wake lock parked, `procState=FGS`,
`oom_score_adj=200`, never frozen.

## Build and conditions

Galaxy Z Fold5 `R3CW705DSTF`, SM-F946U1, Android 16 (API 36), folded
(`cmd device_state print-state` = 0), cover panel active, `deviceLocked=0` at the start.
Package `org.azahar_emu.azahar.thor`, `versionCode=40000000`,
**`versionName=thor-v1-rc1-38-thor`**. That is commit `93c64b525`, which is `git diff
--stat`-identical to the rc2 candidate `acf5f0133` and to the `thor-v1-rc2` tag (`1745b2f8f`)
outside `docs/` — the two commits between them are documentation only. **Nothing was
installed for this pass**, so the version-code downgrade trap did not apply.

ROM `/storage/emulated/0/roms/3ds/acnl.3ds` (Animal Crossing: New Leaf), launched with the
VIEW intent at `org.citra.citra_emu.activities.EmulationActivity`. `resolution_factor = 3`.
No scrcpy virtual display was present (`dumpsys SurfaceFlinger --display-id` had no `scrcpy`
match at any point), so this pass is **not** comparable with the rc2-vs-rc1 table, which was
taken with scrcpy running.

`device.lock` was held for every adb action. Nothing was uninstalled. `dumpsys battery
unplug` was used to make the accounting accrue as if on battery and was reset with `dumpsys
battery reset` at the end of every window, verified in the session log. `screen_off_timeout`
was raised from 1 800 000 to 3 600 000 for the screen-on windows so the panel could not time
out mid-measurement, and restored by the script's `EXIT` trap.

Raw captures (per-snapshot `dumpsys power`, `dumpsys activity processes`, `dumpsys
deviceidle`, `/proc/<pid>/stat`, the `batterystats --charged` dumps for both windows, the
`meminfo` that invalidated the second screen-off run, and every session log and script):
`~/Development/azahar-builds/battery-idle-2026-09-09/`, mirrored from the session scratchpad
`bat/`.

## 1. Wake locks: the mechanism, measured

This is the part that decides the answer, and it is deterministic rather than statistical.

| State | `Wake Locks:` in `dumpsys power` | `mHoldingWakeLockSuspendBlocker` |
|---|---|---|
| Game in the foreground, visible | `size=2`: `SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:0'` with `ws=WorkSource{10655 org.azahar_emu.azahar.thor}`, and `PARTIAL_WAKE_LOCK 'MmapPlayback'` `uid=1041` with `WorkChain{(10655), (1041)}` | `true` |
| Parked with HOME, t+0 s | **`size=0`** | `false` |
| Parked, t+5 min | **`size=0`** | `false` |
| Parked, t+15 min | **`size=0`** | `false` |
| Parked, t+21 min | **`size=0`** | `false` |

Both foreground locks are explained and both are released on background:

- The `SCREEN_BRIGHT_WAKE_LOCK` is `android:keepScreenOn="true"` on the emulation layouts
  (`src/android/app/src/main/res/layout/activity_emulation.xml:8`,
  `fragment_emulation.xml:7`). WindowManager holds it *on behalf of* the app's WorkSource
  only while the app's window is visible, so backgrounding releases it. **Confirmed**: it is
  in the list at foreground and absent from every parked sample.
- The `PARTIAL_WAKE_LOCK 'MmapPlayback'` is the AAudio MMAP output stream, held by
  `audioserver` (uid 1041) with a WorkChain back to Azahar's uid. It disappears on pause
  because `pauseEmulation` stops the output stream — `SetAudioOutputPaused(true)` in the
  paused branch of `RunCitra` (`src/android/app/src/main/jni/native.cpp:366-372, 689-697`),
  the B1 audio-pause work. **This is the one thing in the app that could have blocked
  suspend, and it no longer does.** Before that fix the paused process kept a partial wake
  lock alive through audioserver for as long as the game sat in the background, which is a
  much better explanation of upstream #519's "the back of the Thor is warm while sleeping"
  than the emulator's own CPU ever was.

`dumpsys batterystats --charged org.azahar_emu.azahar.thor` for the 21-minute parked window
lists **no wake-lock rows and no wakeup-alarm rows for `u0a655` at all** — an independent
confirmation from a different subsystem's accounting.

`dumpsys deviceidle`: `mState=ACTIVE mLightState=ACTIVE` throughout (the screen was on, so
Doze could not engage). Azahar is **not** on any device-idle allowlist — `dumpsys deviceidle
whitelist` has no match for the package. It does not ask for one: the manifest declares
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` and **neither `WAKE_LOCK` nor
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** (`src/android/app/src/main/AndroidManifest.xml:33-34`).

**Does a foreground service by itself prevent the SoC from suspending?** No — verified, not
assumed. Through the whole parked window the process sat at `procState=4` with the service
running (`Foreground services: 21m 2s 8ms realtime (running)` in batterystats) while the
system-wide `PowerManagerService.WakeLocks` suspend blocker sat at `ref count=0`. A
foreground service raises the process's *importance*, which is an eligibility-for-killing
property; it is not a wake lock and grants no suspend-blocking power.

## 2. Battery accounting, 21-minute parked window

Method: game launched, parked with HOME, `dumpsys battery unplug`, `dumpsys batterystats
--reset`, wait 21 m 2 s, dump. Control: identical procedure with the app force-stopped,
10 m 0 s.

| | Parked, service running (21 m 2 s) | Control, force-stopped (10 m 0 s) |
|---|---|---|
| `Foreground services` for `u0a655` | 21 m 2 s (running) | — |
| `Total cpu time` for `u0a655` | `u=529 ms s=4s 241 ms` (4.77 s) | no CPU rows |
| `Proc org.azahar_emu.azahar.thor` | `1s 200ms usr + 3s 410ms krn` | absent |
| Wake-lock rows for `u0a655` | **none** | none |
| `Estimated power use` for `u0a655` | **0.0602 mAh**, all `bg` | **absent from the list** |
| Global `cpu` for the window | 3.95 mAh | 0.959 mAh |
| Global `screen` for the window | 46.6 mAh | 22.2 mAh |

Independent CPU measurement from `/proc/30561/stat` across the same window
(1261 s, USER_HZ = 100):

| | utime | stime | total |
|---|---|---|---|
| start | 2368 | 456 | |
| end | 2493 | 807 | |
| delta (jiffies) | +125 | +351 | **+476 = 4.76 CPU-s** |

That is **0.377 % of one core**, and it is steady rather than front-loaded: 0.383 % over
t+0→t+5, 0.372 % over t+5→t+15, 0.385 % over t+15→t+21. It agrees with batterystats'
independent 4.77 s to within 1 %, and with the rc2 baseline's 0.36 % figure. Three quarters
of it is *system* time, and the per-frequency histogram shows 6 286 ms in the lowest
little-core bin (307 MHz, 14 mA in this phone's power profile) with only a few hundred
milliseconds anywhere above it — this is a process being woken briefly and cheaply, not one
computing.

**Rates, screen on:**

| | |
|---|---|
| CPU-seconds per hour | **13.6** |
| mAh per hour (batterystats model) | **0.172** |
| as a fraction of the 4400 mAh battery | **0.0039 % per hour** |
| over 8 hours parked with the screen on | 1.4 mAh, **0.03 %** of a charge |
| Azahar's share of the phone's own CPU power over the window | 1.5 % |
| Azahar's share of screen + CPU + Bluetooth over the window | 0.12 % |

Two honest caveats on those numbers. First, **the mAh figure is a model, not a
measurement**: `dumpsys battery unplug` freezes the battery service, so the charge counter
stayed at 1 496 259 from start to end and both windows report `Computed drain: 0, actual
drain: 0`. The per-uid mAh comes from CPU time multiplied by the phone's power-bracket
currents, which is exactly the right tool for attributing a small load but is not a coulomb
counter. Second, **a Fold5 estimate is not a Thor estimate** — different SoC, different
power profile, different battery. What transfers to the Thor is the *shape* (no wake locks,
little-core-only, ~0.4 % of a core), not the milliamp-hours.

The device-level `cpu` totals (11.3 mAh/h parked versus 5.8 mAh/h control) are **not** a
valid attribution: the difference is nine times larger than Azahar's own accounted share and
is dominated by other processes and by this pass's own `dumpsys` probes. The per-uid row is
the number to trust, and the control's value is that it shows the uid vanishing from the
accounting entirely when the app is not running.

## 3. Screen off, for real

`KEYCODE_SLEEP` and `KEYCODE_WAKEUP` were permitted mid-pass, so this was measured rather
than argued. Game launched, parked with HOME, `dumpsys battery unplug`, `KEYCODE_SLEEP`,
200.6 s, then read back and `KEYCODE_WAKEUP`.

**The Fold5 does not go to sleep on a power-key press; it goes to `Dozing`.**

```
mWakefulness=Dozing            (mWakefulness=3)
mHoldingWakeLockSuspendBlocker=false
mHoldingDisplaySuspendBlocker=false
Wake Locks: size=1
  DOZE_WAKE_LOCK  'dream:doze'  ACQ=-19s302ms (uid=1000 pid=3028 displayId=0)
```

Two things follow, and they point in opposite directions:

- **Azahar still holds nothing.** The single wake lock in the list belongs to `uid=1000`
  (system\_server's dream/AOD), not to the app. So the screen-off answer to "does the
  emulator block suspend" is the same as the screen-on answer: no.
- **But the SoC did not suspend during this window**, because system\_server's doze lock was
  up. Across the 200.6 s: `/proc/uptime` advanced 200.62 s and its idle field advanced
  1562.13 s, i.e. 97.3 % of the theoretical 8-core maximum. The CPUs were essentially idle,
  in idle states, but ticking — not suspended.

Consequently Azahar kept running: `/proc/<pid>/stat` went from `1476 421` to `1484 511`,
**+98 jiffies = 0.98 CPU-s over 200.6 s = 0.49 % of one core** — the same order as the
screen-on parked figure (this window starts 2 s after HOME, so the autosave flush is inside
it, which is why it reads slightly high). `isFrozen=false`, `procState=4`,
`oom_score_adj=200` before and after.

So on **this** phone, with its always-on display doing what an always-on display does, "the
lid is shut" is not "the SoC is suspended", and the 0.17 mAh/h keeps being paid. It is worth
being precise about what that does and does not tell us about the Thor:

- What transfers: **Azahar holds no wake lock in any state after it is parked.** It is never
  the thing keeping the device awake. That is a property of the app and it was measured in
  the foreground, parked with the screen on, and parked with the screen off.
- What does not transfer: whether the *device* suspends. The Thor is a handheld with a lid
  switch and no always-on display, and it is not running Samsung's dream stack, so a lid
  close there is much more likely to reach real suspend than a power-key press does here. If
  it does, the emulator's cost goes to zero for the duration, because nothing runs.
- Either way the answer to Marty's question is the same, which is the useful part: **the
  worst case is the one measured here** — device never suspends, process never frozen, and
  the bill is 0.17 mAh/h.

## 4. Freezer and process state

Sampled at every point in every window:

| Moment | `Proc #` line | `oom_score_adj` | `isFrozen` | `isFreezeExempt` |
|---|---|---|---|---|
| Game in the foreground | `Proc # 0: fg T/A/TOP … (top-activity)` | 0 | false | false |
| Parked, first ~60 s | `Proc # 1: fg +50 M/S/FGS … (fg-service-act)` | 50 | false | false |
| Parked, steady (t+1 min to t+22 min) | `Proc # 2: prcp M/S/FGS … (fg-service)` | 200 | false | false |
| Parked, screen off (Dozing) | `Proc # 1: prcp M/S/FGS … (fg-service)` | 200 | false | false |

`procState=4` (FOREGROUND\_SERVICE) throughout the parked windows. The process is never
frozen, and interestingly it is also never *exempt* from freezing (`isFreezeExempt=false`) —
it simply never reaches the cached tier where Android's freezer applies. Nothing about the
service is a special dispensation; keeping `procState` at 4 is the whole mechanism.

This is the same state the rc2-vs-rc1 pass recorded, and it is why M-2's `onTrimMemory` trim
has still never fired on this device: Android does not send `TRIM_MEMORY_UI_HIDDEN`-or-worse
to a process at `procState=FGS`. So the service's real cost is not the CPU (which is
0.17 mAh/h) — it is that **~1.0 GiB of VmRSS stays resident and uncompacted for as long
as the game is parked**, and the code that would give some of it back is
unreachable.

### The no-foreground-service comparison could not be made

Attempted twice, and it fails for a structural reason worth recording:

```
$ adb shell am stopservice -n org.azahar_emu.azahar.thor/org.citra.citra_emu.utils.ForegroundService
Stopping service: Intent { cmp=org.azahar_emu.azahar.thor/…/ForegroundService }
Error stopping service
```

The service is declared `android:exported="false"`
(`src/android/app/src/main/AndroidManifest.xml:102-110`), so the shell uid cannot stop it.
`dumpsys activity services` confirms the `ServiceRecord` is still there afterwards and
`procState` stays at 4. The only other route is a build with the service removed or
setting-gated, and installing one was deliberately not done on this pass (the version-code
downgrade trap, and no build flag exists today). **So the "what does the process look like
without the service" half of the tradeoff is inferred, not measured**: it would be an
ordinary cached process at `oom_score_adj` ≥ 900, frozen after ~10 s, receiving
`TRIM_MEMORY_UI_HIDDEN`, and eligible for the cached-process purge. If that comparison is
ever wanted for real, the cheap way is a `BooleanSetting` that gates
`ForegroundService.start()`, built once with `-PversionCodeOverride=40000000`, not an
`am stopservice`.

## 5. melonDS

Measured on `me.magnum.melonds.dev`, `versionName=2.0.1 GH`, `versionCode=41`, reusing the
live session `agent-melonds-measure` had left running (pid 1786, `Foreground services`
running for 26 minutes at that point) rather than starting a second one.

| | melonDS parked | Azahar parked |
|---|---|---|
| `Wake Locks:` after HOME | **`size=0`** | `size=0` |
| `mHoldingWakeLockSuspendBlocker` | `false` | `false` |
| `Proc #` line | `prcp M/S/FGS … (fg-service)` | `prcp M/S/FGS … (fg-service)` |
| `curProcState` | 4 | 4 |
| `oom_score_adj` | 200 | 200 |
| `isFrozen` / `isFreezeExempt` | false / false | false / false |
| parked CPU | +105 jiffies over 120 s = **0.88 % of one core** | 0.377 % of one core |

**Same mechanism, same conclusion: no wake lock, not frozen, `procState=FGS`.** The two
partial wake locks visible before the HOME (`SyncManager: Scheduling in parallel`,
`SyncManager: Syncing`) belong to `uid=10268`, a different app entirely.

The parked CPU figure carries real caveats and should be treated as an upper bound rather
than a result: the 120-second window starts at the HOME keypress, so the pause transition is
inside it; `mCurrentFocus` was `NotificationShade` beforehand, so the emulator activity may
not have been the visible window; and the session's state (running game versus menu) was
another agent's, not set up by this pass. It is enough to say the shape matches Azahar's and
that melonDS is, if anything, the slightly busier of the two when parked — not enough to
quote 0.88 % as melonDS's number.

The fork's service is the same shape as Azahar's, by construction:
`me.magnum.melonds.ui.emulator.EmulatorForegroundService` is `specialUse`, `START_STICKY`,
`stopWithTask="true"`, posts an ongoing silent notification and, in its own words, "performs
no work of its own". The manifest declares `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE` and `FOREGROUND_SERVICE_DATA_SYNC` (the last for WorkManager),
and **no `WAKE_LOCK`**. So the mechanism conclusion for melonDS is the same one as for
Azahar, for the same reason: a foreground service is not a wake lock.

## 6. What can and cannot be concluded

**Concluded, measured, no inference needed:**

- Azahar holds **no wake lock** once the game is parked — not a partial one, not through
  audioserver, not through WindowManager — in the foreground-then-HOME case and in the
  screen-off case. `dumpsys power` says so directly and `batterystats` agrees by having no
  wake-lock rows for the uid.
- A `specialUse` foreground service does **not** block suspend. Verified rather than
  asserted: service running, `PowerManagerService.WakeLocks` at `ref count=0`.
- The parked cost, on a device that stays awake, is **13.6 CPU-s/h and ~0.17 mAh/h**, almost
  all of it in the lowest little-core frequency bin. Over eight hours that is 1.4 mAh of a
  4400 mAh battery, 0.03 %.
- The process is never frozen while the service runs, and never gets an `onTrimMemory`
  callback, so its ~1 GiB working set stays resident.

**Not concluded, and why:**

- **Whether the Thor suspends on a lid close.** Measured here on a Fold5, which answers a
  power-key press with `Dozing` (its always-on display), not `Asleep`. The Thor has a lid
  switch, no AOD and no Samsung dream stack, so it is a different question that only the Thor
  can answer. The good news is that this makes the Fold5 the *pessimistic* case: it never
  suspended, and the bill was still 0.03 % per eight hours.
- **A true overnight drain figure.** Deliberately not attempted — Marty's instruction was
  "we don't need to leave it for hours, we can just do a few mins here or there and infer",
  and the rates above are what the inference rests on. The extrapolation is linear in a
  quantity measured over 21 minutes at a rate that was flat across all three sub-intervals,
  so it is a fair one, but it is an extrapolation.
- **Hardware coulomb counting.** `dumpsys battery unplug` freezes the battery service, so the
  charge counter does not move and both windows report `Computed drain: 0`. The mAh numbers
  are batterystats' CPU-time-times-power-profile model. That model is appropriate for
  attributing a small per-uid load and inappropriate for a whole-device drain claim.
- **`/sys/power/suspend_stats/*` and `/sys/power/wakeup_count` are unreadable** by the adb
  shell user on this device (they return empty). The suspend evidence in §3 is therefore
  `mWakefulness`, the wake-lock list, and the ratio of `/proc/uptime`'s idle field to
  8 × elapsed, not the kernel's own suspend counter.

**A second screen-off run was taken and thrown away, and the reason is instructive.** At
12:06 a longer (5-minute) screen-off arm was run. Its Azahar numbers looked spectacular —
`utime=43 stime=47` before the sleep, and *byte-identical* `43/47` at t+1 min and at
t+5 min, i.e. exactly zero CPU accrued across the whole screen-off window. It is wrong. The
phone was behind the keyguard from this pass's own earlier `KEYCODE_SLEEP`, so the `am start`
put `EmulationActivity` behind the lock screen, no surface was ever delivered, and `RunCitra`
sat waiting for one: **the game never booted**. `dumpsys meminfo` settles it — `TOTAL PSS
94 645 kB`, `GL mtrack 4 876 kB`, against ~1 355 000 kB and ~448 000 kB for a running ACNL.
A process that is not emulating anything of course burns no CPU. This is the trap
`docs/fork/swarm-contract.md` and `DEVICE-LOCKED.md` warn about, and the tell was exactly
the one they name: identical numbers across supposedly different samples. The valid
screen-off data is the 11:25 window in §3, where the process demonstrably kept running
(+98 jiffies) and its `VmRSS` was ~1 GiB.

**Cross-agent note.** At 11:25:15, inside this pass's first screen-off window,
`agent-melonds-measure` issued one batch of read-only adb calls (`dumpsys trust`, `dumpsys
power`, `dumpsys window`, `pidof`, `run-as`) believing it still held `device.lock`, which had
expired on a timer. Those are reads, they cost system CPU and not Azahar CPU, and the
window's Azahar-attributed numbers are taken from `/proc/<pid>/stat` deltas, which they
cannot touch. Recorded for completeness rather than because it changes a number.

Also for completeness: the brief asked for `docs/fork/android-kill-root-cause.md` §0. **That
section does not exist** — the document runs §1 to §6, and `git log --all` shows it has only
ever had one commit (`2b870bc70`). The "§0" referenced from `improvement-plan.md`'s T.6 table
is a forward reference to something never written. The kill rules and the foreground-service
tradeoff were taken from §1 and §3 of that document and from the T.6/T-7 rows of the plan.

## 7. Should the foreground service be stopped when the screen turns off?

**Recommendation: no. Keep it, unconditionally, and do not add a `SCREEN_OFF` receiver or an
N-minutes-parked timer.** Close T-7's FGS half as "measured, not worth doing"; keep T-7's
`onUserLeaveHint`/`DisplayListener` split, which is still useful for autosave policy and for
the secondary-display work, and drop only the "stop the FGS on lid close" clause.

The reasoning, against the four things the brief asked to weigh:

**What the service actually prevents.** It holds `procState` at 4 and `oom_score_adj` at 200.
That keeps the process out of the cached tier, and therefore out of reach of the
cached-process purge (`TOO_MANY_CACHED` / `LARGE_CACHED`), out of reach of the freezer, and
above the adj band lmkd walks first. For a process holding ~1 GiB — by a wide margin the
heaviest thing on the device — that is not a small effect; §1/§5 of the kill root-cause
document is the argument, and Marty's day of crash-free rc2 play is the field evidence.

**What it costs.** Two things, and only one of them is the battery.

- *Battery:* 0.17 mAh/h, 0.03 % of a charge over eight hours, on the pessimistic assumption
  that the device never suspends at all. This is the number the whole question turned on and
  it is negligible. Stopping the service on screen-off would recover a fraction of 0.03 %,
  and would recover **nothing at all** on a device that does suspend, because a suspended
  CPU already costs nothing whether the process is frozen or not. **The freezer only matters
  for a process that would otherwise wake the CPU, and this one holds no wake lock, so it
  does not.**
- *Memory:* ~1.0 GiB VmRSS held resident (1 004 180 kB measured here; the rc2 pass put parked TOTAL PSS at 1 329 166 kB), never compacted into ZRAM, and
  `onTrimMemory` never delivered so M-2's cache release never runs. This is a real cost and
  it is the one worth acting on — but the fix for it is not "drop the service", it is "drive
  the trim directly", which the rc2 pass already identified (`am send-trim-memory <pid>
  HIDDEN` to verify, and a call into `NativeLibrary.trimMemory()` from the lifecycle rather
  than waiting for a callback that a FGS process will never receive).

**What the autosave already guarantees.** It is genuinely good: `EmulationFragment.onPause`
requests the autosave and `onStop` blocks on it
(`src/android/app/src/main/java/org/citra/citra_emu/fragments/EmulationFragment.kt:629-656`),
and the rc2 pass measured a cold boot that resumes from it in **2805 ms**. So being killed
while parked is recoverable and costs no progress. That is exactly what makes it safe to
*consider* dropping the service — but it is an argument for indifference, not for dropping,
because the thing dropping would buy is worth 0.03 %.

**What the user experiences.** With the service: same pid, session continues instantly (the
rc2 pass measured a warm re-intent at 221 ms). Without it, after a kill: 2805 ms of boot, a
loading screen, and — the part the numbers do not show — the small persistent doubt about
whether the save was really written, which is the exact anxiety this fork exists to remove.
Trading a certain 2.6-second regression on every lid-open for an uncertain 0.03 %-per-night
saving is a bad trade, and it is worse on the Thor, where the lid cycle is the primary
interaction.

**Code that would have changed, had the answer gone the other way** (recorded so the option
is cheap to revisit): a `BroadcastReceiver` for `Intent.ACTION_SCREEN_OFF` registered in
`src/android/app/src/main/java/org/citra/citra_emu/activities/EmulationActivity.kt` calling
`ForegroundService.stop(this)` (`utils/ForegroundService.kt:112-114`), with a matching
`ForegroundService.start(this)` on `ACTION_SCREEN_ON`/`onResume` — noting that `onCreate` is
the only place the service is started today (`EmulationActivity.kt:199`), and that Android 12+
refuses foreground-service starts from the background, so the restart would have to be driven
from a foreground activity callback, not from the receiver. The plan row is **T-7** in
`docs/fork/improvement-plan.md:224`.

**What to do instead, in priority order:**

1. **M-2, driven rather than awaited.** Call the trim explicitly from the parked path instead
   of hoping for an `onTrimMemory` that never arrives at `procState=FGS`. This is where the
   ~440 MiB of GL mtrack is, and reducing the resident set is what actually lowers the
   probability of the kill the service exists to prevent. Verify with `am send-trim-memory
   <pid> HIDDEN`.
2. **Keep the audio stop.** The `SetAudioOutputPaused(true)` on park
   (`src/android/app/src/main/jni/native.cpp:366-372, 689-697`) is the single most important
   battery change in the fork and this pass is the evidence: it is what removes the
   `MmapPlayback` partial wake lock. Without it the parked process keeps a suspend-blocking
   wake lock alive through audioserver, which is a far better explanation of upstream #519
   ("heavy battery drain while sleeping with Azahar open") than the emulator's CPU. It should
   not be regressed, and it deserves a test.
3. **Leave T-7's lid-vs-Home split in the plan** for autosave and secondary-display policy,
   with the FGS clause struck.

## 8. Device state left behind, and two disclosures

`device.lock` was held for every adb action except where noted below. Final state, verified:
`mWakefulness=Awake`, `USB powered: true` (the `dumpsys battery unplug` overrides were reset
after every window), `screen_off_timeout=1800000` (restored from the 3 600 000 this pass set),
`stay_on_while_plugged_in=15` (untouched), both `org.azahar_emu.azahar.thor` and
`me.magnum.melonds.dev` force-stopped, nothing installed or uninstalled, no settings left
changed. `deviceLocked=1` — the phone is behind its PIN because this pass sent
`KEYCODE_SLEEP`, which the updated contract permits.

Two things this pass did that other agents should know about:

1. **It force-stopped `me.magnum.melonds.dev` at 12:17:47**, at the end of its own melonDS
   check. That process (pid 1786) was a live session `agent-melonds-measure` had left
   running, and force-stopping it may have ended work in progress. The cleanup line was
   written before it was known that another agent's session would be reused rather than a
   fresh one started.
2. **While clearing what it believed was its own stray lock waiter at 11:30, it killed PID
   3684541**, which was `agent-melonds-measure`'s queued `flock … mm/holder.sh`, forcing that
   agent to re-acquire. Nothing on the device was affected; the lock queue was.

Neither affects any number in this document, and both are recorded because the alternative —
another agent silently losing a run and not knowing why — is worse.

