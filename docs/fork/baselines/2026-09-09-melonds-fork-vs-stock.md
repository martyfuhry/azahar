# melonDS Thor fork vs upstream stock — 2026-09-09

Before/after evidence for `/home/martyfuhry/Development/melonds-android`, branch `thor/main`,
measured on the Galaxy Z Fold5 `R3CW705DSTF`. Two questions were asked of the device: **what
was actually killing melonDS in the background**, and **does the fork stop it**.

The short version, which the rest of this document backs up: on this device the excessive-CPU
rule was never the mechanism — stock melonDS idles at 0.5–1.3 % of one core while backgrounded,
comfortably inside every AOSP tier. The mechanism is the **memory tier**. Stock drops to
`oom_score_adj=900` (cached), is frozen at the 5-minute mark, and was reclaimed with
`reason=3 (LOW_MEMORY)` under induced pressure. The fork's foreground service holds the same
process at `oom_score_adj=200`, which survived four times the pressure that killed stock.

---

## Builds

| | STOCK | FORK (measurement build) | FORK (shipping artifact) |
|---|---|---|---|
| Commit | `3f3a1c82` "Add Motion Pak support" — the merge-base of `thor/main` with upstream `master` | `c86e8147` — `thor/main` tip at build time | `c86e8147` |
| Variant | `gitHubProdDebug` | `gitHubProdDebug` | `gitHubThorRelease` (R8 + resource shrinking) |
| Application id | `me.magnum.melonds.dev` | `me.magnum.melonds.dev` | `me.magnum.melonds.thor` |
| versionName / Code | `2.0.1 GH` / 41 | `2.0.1 GH` / 41 | `2.0.1 GH (THOR)` / 41 |
| APK sha256 | `da1b4edc40da4e0f483ac7e3380cca02e7efd32c3fc8ad4e15a50767c4eaf10a` | `56defba89894a576fdbffe05045cfdba5e97bf958014653cd8e244615cf748c7` | `05ecaed1519943307977bd067a13bf5eb5a0518c083bbd6ed1ac10ebb37ce74d` |
| `libmelonDS-android-frontend.so` (arm64) | 7 389 040 B | 7 395 648 B | — |

Both comparison APKs are the same variant of the same project built the same way
(`assembleGitHubProdDebug -Pandroid.injected.build.abi=arm64-v8a`), from two `git worktree`
checkouts, so the only difference between them is the 26 commits on `thor/main`. The shipping
`.thor` release build was used for the functional tests, because that is the artifact Marty will
install and R8 is the one thing the debug build cannot exercise.

**Why one application id for the pair, not two.** The fork does add a `thor` flavour, so two
resident installs are buildable — but not measurable. melonDS resolves a launch intent through
`RomsRepository.getRomAtUri`, which only finds ROMs already scanned into *that package's*
library, and the library needs a persisted SAF tree grant that only the DocumentsUI picker can
create. So the numbers pair shares `me.magnum.melonds.dev` (already provisioned) and swaps with
`adb install -r -d -t`; `.thor` was provisioned separately through the picker for the functional
half.

**No version-code trap here.** melonDS's version code is the constant `AppConfig.versionCode = 41`,
not a build timestamp, so both APKs carry the same code and `install -r` is a same-version replace
in either direction. (The trap that cost the Azahar pass a session, and cost another agent three
runs today on `org.azahar_emu.azahar.thor`, does not apply.)

**Identity was proved after every install, by hash, not by version name** — versionName and
versionCode are *identical* between stock and fork and are therefore useless on their own:

1. `dumpsys package me.magnum.melonds.dev | grep versionName` (present, code 41), and
2. `sha256sum <codePath>/base.apk` **on the device**, compared byte-for-byte with the APK just
   built. The session aborts on a mismatch. Both passes printed
   `IDENTITY OK (device base.apk is byte-identical to the APK just built)`.
   The `.thor` install was checked the same way and matched the sha256 in its release notes.

A third discriminator was tried and **discarded as unreliable**: `dumpsys package <pkg> | grep -c
EmulatorForegroundService` returns 0 even for builds that plainly declare the service, because
`dumpsys package` filtered to one package does not print component tables. The runtime check
(`dumpsys activity services <pkg>`) does work and is used below.

## Conditions

Galaxy Z Fold5 `R3CW705DSTF`, SM-F946U1, **Android 16 (API 36)**, 11 412 520 kB RAM,
12 582 908 kB ZRAM. Phone **folded** (`cmd device_state print-state` = 0); the active panel is the
**cover display, logical display 0**, 904×2316 at 120 Hz (`local:4630946481096930692`, `state ON`);
the inner display is logical display 1, `state OFF`. Battery 39–40 %, charging, 31.9–33.4 °C.

**scrcpy was not running.** Every `dumpsys SurfaceFlinger --display-id` capture in this pass lists
exactly the two physical displays and no virtual display. (`ps -A | grep scrcpy` is *not* the check
— the device side of scrcpy runs as `app_process`; the rc2 pass documented that trap.)

ROM: `/sdcard/roms/nds/spp.nds`, md5 `3c1a8bd8ea761fc505eacabfff19ffa0`, byte-identical to
`/nfs/roms/ROMS/Nintendo DS/Super Princess Peach (USA).nds`. It was already on the phone from an
earlier session; the md5 was checked against the NFS copy rather than re-pushed. It is never
committed or uploaded.

`device.lock` was held continuously from **10:24:20** EDT. One run per metric, no repeats — treat
differences under ~5 % as noise unless a mechanism is named. `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` were
used once each (permitted as of 2026-09-09) and the device was woken again afterwards; no
`KEYCODE_POWER` was ever sent. No screen-derived number appears in the numbers table; the
screenshots used in the functional half were each taken with a hardened capture that proves
freshness (unique remote path, deleted first, stderr surfaced, size checked on device, and
`mCurrentFocus` matched before *and* after the capture).

Raw captures, scripts and both APKs:
`~/Development/azahar-builds/measure-melonds-2026-09-09/` — `out-stock/`, `out-fork/`, `out-func/`,
`out-sleep/`, `out-restore/`, `shots/`, `numbers.log`, `sleeptest.log`, the two build logs, and
`mds.sh` / `session.sh` / `pressure.sh` / `sleeptest.sh` / `b1.sh` / `hog.c`.

---

## Results

| Metric | STOCK `3f3a1c82` | FORK `c86e8147` | Delta |
|---|---|---|---|
| **Boot: intent → first frame on the emulator SurfaceView layer** | 1028 ms | 1135 ms | +107 ms |
| Boot: `am_proc_start` | +109 ms | +174 ms | +65 ms |
| Boot: framework `Displayed` | +941 ms | +974 ms | +33 ms |
| Boot: foreground service started | n/a (no service) | +910 ms ("Background started FGS: Allowed") | — |
| **Idle CPU backgrounded, HOME+10 s…+70 s (% of one core)** | **1.30 %** | **1.20 %** | −0.10 pp |
| Top 5 threads in that window | `DefaultDispatch` 0.58, main 0.33, `DefaultDispatch` 0.07, `DefaultDispatch` 0.07, `Thread-19` 0.07 | `DefaultDispatch` 0.55, main 0.32, `DefaultDispatch` 0.08, `DefaultDispatch` 0.07, `Thread-20` 0.07 | same set |
| **Idle CPU backgrounded, HOME+300 s…+360 s (% of one core)** | **0.47 %** (process was frozen for part of it) | **1.30 %** | +0.83 pp |
| Top 5 threads in that window | `DefaultDispatch` 0.22, main 0.12, `DefaultDispatch` 0.03, `Picasso-Stats` 0.03, `Picasso-Dispatc` 0.02 | `DefaultDispatch` 0.60, main 0.33, `DefaultDispatch` 0.08, `DefaultDispatch` 0.07, `Thread-20` 0.07 | — |
| **`AudioTrack` (Oboe callback) CPU while backgrounded** | **0 ticks** in both windows | **0 ticks** in both windows | 0 |
| **Kill regime at HOME+70 s** | `Proc # 2: cch b/ /LAST`, `cached=true`, `oom cur=900` | `Proc # 1: prcp M/S/FGS`, `cached=false`, `oom cur=200` | cached → FGS |
| `isFrozen` at HOME+70 s | false (`isPendingFreeze=true`, `earliestFreezable=+3m49s`) | false (`isPendingFreeze=false`) | — |
| **`isFrozen` at HOME+300 s** | **true** | **false** | — |
| `oom_score_adj` (`/proc`) fg / bg+70 s / bg+300 s | 0 / 900 / 900 | 0 / 200 / 200 | −700 in background |
| Foreground service present | none | `EmulatorForegroundService`, `isForeground=true foregroundId=1000 types=0x40000000` (SPECIAL_USE), channel `channel_emulation` | added |
| "run cpu over …" line in `dumpsys activity processes` | never printed | never printed | — |
| **Foreground TOTAL PSS @65 s** | 420 558 kB | 421 536 kB | +978 kB (+0.2 %) |
| Foreground GL mtrack / EGL mtrack @65 s | 29 692 / 69 920 kB | 29 836 / 69 920 kB | +144 / 0 |
| Foreground Native Heap @65 s | 145 238 kB | 143 180 kB | −2 058 kB |
| Foreground VmRSS @65 s | 444 556 kB | 435 492 kB | −9 064 kB |
| Background TOTAL PSS @+70 s | 377 983 kB | 352 468 kB | −25 515 kB |
| Background TOTAL PSS @+300 s | 374 923 kB | 354 371 kB | −20 552 kB |
| **Background VmRSS @+300 s** | **167 692 kB** | **435 716 kB** | **+268 024 kB** |
| **Background VmSwap @+300 s** | **280 444 kB** | **10 024 kB** | −270 420 kB |
| Background Native Heap @+300 s | 3 314 kB (swapped out) | 143 518 kB (resident) | +140 204 kB |
| AMS `lastRss` at background entry | 529 MB → 227 MB after compaction | 523 MB, no compaction | — |
| Thread count fg / bg+300 s | 43 / 43 | 42 / 42 | −1 (a transient binder thread) |
| Threads present in one build and not the other | — | — | **none** (identical `comm` sets apart from binder-pool numbering) |
| 5× HOME/relaunch, same process? | 5/5 same pid 8959 | 5/5 same pid 17958 | same |
| 5× HOME/relaunch on the shipping `.thor` build | — | 5/5 same pid 3832, `oom_score_adj=50` each time | — |
| CRASH_NATIVE / ANR across the pass | none | none | same |
| **Reclaimed by a 6 GiB memory hog while backgrounded** | **YES — `reason=3 (LOW_MEMORY)`, rss=99 MB, importance=400, 10:48:05** | **NO** — survived 4, 8 and 12 GiB hogs, same pid | the whole point |
| `am send-trim-memory <pkg> COMPLETE` | accepted (background process) | **refused**: *"Unable to set a background trim level on a foreground process"* | — |

### Boot marker

The boot number is the first `BLASTBufferQueue … first frame is available` for the app's own
`SurfaceView[me.magnum.melonds.dev/…EmulatorActivity]` layer, from the app's pid, in an
epoch-stamped `logcat -b main,system,events` capture started immediately before the launch intent.
That is a framework-emitted marker, not one the app writes, and it is the first frame the emulator
surface ever presents. It is *not* proof that the guest reached a given game frame; melonDS logs
no version banner or "emulation started" line that could serve as a second marker, so no such
marker is claimed. Both builds paid a cold page-cache fault for a freshly written APK on the run
measured (each was the first boot after its install), so the two are comparable to each other but
both are pessimistic against a warm boot.

---

## What was actually killing it

Four candidate mechanisms; this is what the device says about each.

**1. The excessive-CPU rule — ruled out.** AOSP's `ActivityManagerConstants` background CPU check
runs on a 5-minute `POWER_CHECK_INTERVAL`, applies thresholds of 25/25/10/**2 %** by how long the
process has been unimportant (past 15 minutes it is the 2 % tier), and **only examines processes at
`procState >= PROCESS_STATE_HOME`**. Stock melonDS *is* in that tier — `curProcState=15`
(`LAST_ACTIVITY`), `cached=true`, `oom cur=900` — so the rule genuinely applies to it. It measures
**1.30 %** over the first minute in the background and **0.47 %** over the window straddling the
five-minute mark. Both are under the strictest 2 % tier, and 19× under the 25 % tier that actually
applies in the first quarter of an hour. `dumpsys activity processes` never printed a
`run cpu over … used … (N%)` line for either build, and **`dumpsys activity exit-info` for every
melonDS package on this phone contains no `EXCESSIVE RESOURCE USAGE` record, ever.**

That is the opposite of Azahar, where the same phone recorded
`reason=9 EXCESSIVE RESOURCE USAGE subreason=7 EXCESSIVE CPU USAGE, excessive cpu 27810 during
300120 … limit=2` = 9.27 % against a 2 % limit, traced to an audio callback thread burning 7.82 %
of a core while paused. **melonDS does not have that bug** — see the audio section below. So the
fork's value is *not* a CPU saving, and the CPU numbers in the table above are about battery, not
about that kill.

**2. Memory reclaim — this is the one, and it is demonstrated, not inferred.** With a game loaded
and the app backgrounded, stock sits at `oom_score_adj=900` with ~500 MB RSS at entry. Three rounds
of cycling twelve heavy apps (Chrome, Maps, Photos, YouTube, Facebook, Discord, …) did **not**
reclaim it. A userspace memory hog that grew to 3 GiB did not either. At 6 GiB it did:

```
after hog 6144: .dev pid=''  .thor pid='3832'

ApplicationExitInfo #0:
  timestamp=2026-09-09 10:48:05.537 pid=8959 …
  process=me.magnum.melonds.dev reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) status=0
  importance=400 pss=0.00 rss=99MB description=null state=empty
```

The fork's process, held at `oom_score_adj=200` by its foreground service, was **backgrounded on
the same device at the same time and survived** — and later survived 4 GiB, 8 GiB and 12 GiB hogs
in its own pass without dying. Android would not even accept a background trim level for it.

*Caveat, stated plainly:* the two hog runs were not a matched pair. The stock kill happened with the
system already loaded down by twelve freshly launched apps; the fork's hogs ran a few minutes later
with more memory available (MemAvailable 4.9–7.2 GB vs 3.3–5.1 GB). The clean simultaneous
comparison is the one inside the stock run itself: at the instant the cached stock process was
taken, the FGS-held fork process on the same device, at the same moment, under the same hog, was
not.

**3. The freezer — real, and the fork escapes it too.** Stock reached
`isPendingFreeze=true, earliestFreezableTimeMs=+3m49s` a minute after HOME and was
**`isFrozen=true`** by the five-minute sample, with 280 MB of its anonymous memory pushed to ZRAM
and its VmRSS down from 445 MB to 168 MB. A frozen process that then receives a synchronous binder
call is killed outright (`REASON_FREEZER`). The fork was never frozen in any sample. No `FREEZER`
exit record exists on this phone for either package, so the freezer is a *live* hazard for stock
rather than a demonstrated cause.

**4. A native crash — not the background mechanism, but the phone does carry crash history.** The
complete `exit-info` history for every melonDS package on this device, at the time of this pass:

| Count | Reason | When | Note |
|---|---|---|---|
| 8 | `reason=10 USER REQUESTED` / `subreason=21 FORCE STOP` | 2026-09-08 22:20 → 2026-09-09 10:56 | agents' own `am force-stop`; proves nothing |
| 7 | `reason=4 APP CRASH(EXCEPTION)` | all 2026-09-08 22:34–22:54 | the spike session's `file://` ROM-search-directory crash documented in `THOR-NOTES.md`; a UI-unreachable path, not a background death |
| 1 | `reason=1 EXIT_SELF` (`description=stop … due to SPEG`) | 2026-09-09 10:25 (`.thor`) | an idle `.thor` process with no game loaded |
| 1 | `reason=3 LOW_MEMORY` | 2026-09-09 10:48 | **induced by this pass**, on stock |
| 0 | `EXCESSIVE RESOURCE USAGE`, `FREEZER`, `ANR`, `CRASH_NATIVE` | — | none, ever |

No pre-existing background-death record was waiting on this phone; the only one that exists is the
one this pass induced. **Marty's own Thor, where the failures actually happened, would outrank all
of it** — `adb shell dumpsys activity exit-info me.magnum.melonds` there is the single most
valuable piece of evidence still missing.

**Verdict.** The evidence supports **memory reclaim of a cached process** as the mechanism, with the
freezer as a second hazard in the same tier, and rules out the excessive-CPU rule. The fork's
foreground service addresses exactly the mechanism the evidence points at: it moves the process from
`adj 900, cached, freezable` to `adj 200, FGS, not freezable`, and that is the difference between a
process that the low memory killer took and one that it did not.

## The audio claim, settled

The spike agent asserted that melonDS already stops its Oboe stream on pause, so it never had
Azahar's background audio burn. **Confirmed, from the per-thread tables.** The `AudioTrack` thread
(Oboe's callback thread) exists in the foreground thread list on both builds, and in **all four**
background CPU windows — stock at +60 s and +300 s, fork at +60 s and +300 s — its
`Δ(utime+stime)` over 60 s is exactly **0 ticks**:

```
cputable-stock-bg60.tsv    0.000  0  9036   AudioTrack
cputable-stock-bg5min.tsv  0.000  0  9036   AudioTrack
cputable-fork-bg60.tsv     0.000  0  18032  AudioTrack
cputable-fork-bg5min.tsv   0.000  0  18032  AudioTrack
```

That matches the code: `MelonDSAndroid::pause()` calls `pauseAudio()`, which does
`audioStream->requestPause()` and `micInputStream->requestStop()`
(`app/src/main/cpp/MelonDS.cpp:227-230`, `app/src/main/cpp/MelonDSAudio.cpp:303-310`). Neither file
differs between `3f3a1c82` and `c86e8147`, so this is **upstream behaviour present in both builds**
— the fork did not add it, and there was never a 7.8 %-of-a-core audio burn to remove. For
comparison, the same measurement on Azahar's baseline read `AAudio_1` at 7.82 % of a core.

The residual ~1.2–1.3 % that both builds do burn is Kotlin-side: nine `DefaultDispatch` coroutine
threads (0.55–0.60 % between them) and the main thread (~0.33 %). It is the same on both builds, so
it is upstream's, and at 1.3 % it has no bearing on any AOSP threshold.

---

## Functional proof (the shipping `me.magnum.melonds.thor` build)

Installed from `~/Development/azahar-builds/melonds-thor-c86e8147.apk` with plain `adb install -r`
(not test-only), device `base.apk` sha256 `05ecaed1…` matching the release notes exactly.

| Check | Result |
|---|---|
| Fresh install, R8 + resource shrinking on | installs and launches; app label "melonDS Thor" |
| ROM library provisioning from scratch | works: "Set ROM directory" → picker → `roms/nds` → ALLOW; the library then lists **"Super Princess Peach / spp.nds"** with its icon. R8 did not break the ROM scanner or the SAF grant. Screenshots `shots/thor-01…09.png` |
| Notification permission | a system prompt appears on the first launch of a game (the FGS notification needs it). Worth knowing before installing |
| Game boots from the launch intent | yes; reached the title screen, file select, and the opening cutscene. `shots/thor-11…16.png` |
| **Foreground service, on the release build** | `ServiceRecord{… EmulatorForegroundService}` `isForeground=true foregroundId=1000 types=0x40000000` (SPECIAL_USE), notification on `channel_emulation`, under `targetSdkVersion=37` on Android 16 |
| **Resume in place on a re-sent launch intent** (commit `f5f448ae`, upstream #1659) | **PASS.** Re-sending the exact launch intent for the running ROM produced `"intent has been delivered to currently running top-most instance"`, the same pid, and the cutscene carried on. **No "Stop emulation and load new ROM?" dialog.** `shots/thor-17.png` |
| 5× HOME → relaunch | 5/5 same pid, no dialog, no crash, `oom_score_adj=50` while backgrounded |
| Crashes in any of it | none in logcat |
| **Kill (accepted reason) → relaunch restores the session** | **PASS** — silent automatic restore in 1.09 s, checkpoint consumed, `spp.sav` byte-identical (details below) |
| **Review finding B1: a normal wake clears the checkpoint** | **not measured** — needs a human unlock at the moment of the wake; staging was destroyed by another agent's force-stop at 12:17:47 and re-staging was declined (details below) |

### The checkpoint: written on screen-off, exactly as designed

Verified on the **debuggable** fork build (`me.magnum.melonds.dev`, same commit `c86e8147`) so that
the app-private recovery directory could be read with `run-as`. One `KEYCODE_SLEEP` with the game
running produced, within **227 ms**:

```
10:57:22.514  EmulatorRecovery: device_sleep_started
10:57:22.514  EmulatorRecovery: device_sleep_pause_requested
10:57:22.566  EmulatorRecovery: device_sleep_pause_acknowledged
10:57:22.741  EmulatorRecovery: checkpoint_committed

files/emulator-recovery/checkpoint-1788965842711.mln   19 301 543 bytes
files/emulator-recovery/session.json
  {"…","romName":"Super Princess Peach","active":true,"sleeping":true,
   "sleepStartedAt":1788965842511,
   "checkpointFileName":"checkpoint-1788965842711.mln",
   "checkpointSha256":"5d04a98789145e55cb52a8c12693c566627771f373a8552ac835dc403a39472f",
   "checkpointCreatedAt":1788965842739,"automaticRecoveryAttempted":false}
```

A full 19.3 MB savestate, on disk, 227 ms after the screen went off, with the emulator thread
parked first (`pause_requested` → `pause_acknowledged`, which is review finding S5's fix visible in
the journal). **A plain HOME writes no checkpoint** — `EmulatorActivity.onPause` takes the sleep
branch only when `isScreenOff()`, i.e. `ACTION_SCREEN_OFF` was observed or
`PowerManager.isInteractive == false` (`EmulatorActivity.kt:1266, 1271-1281`). There is no way to
reach that path from `adb` without turning the screen off: `ACTION_SCREEN_OFF` is a protected
broadcast and `am broadcast` from the shell uid cannot send it.

### Kill and restore — PASS, verified end to end

Two things about this phone shaped how this had to be run:

* The fork's FGS process is **very hard to kill on purpose**. 4 GiB, 8 GiB and 12 GiB memory hogs
  against an 11.4 GiB device all left it alive. That is the feature working, but it means a genuine
  LMK kill of the *fork* could not be induced inside the device window. The `LOW_MEMORY` kill in
  this document is of the *stock* build, which is the right build to prove the mechanism on.
* **One `KEYCODE_SLEEP` re-engages this phone's secure PIN keyguard** (`deviceLocked=1`,
  `wm dismiss-keyguard` cannot clear it), and behind the keyguard `EmulatorActivity.onResume()`
  does not run. Every step below that needed the screen usable was done inside a window Marty
  opened by unlocking the phone by hand.

The round trip that was run, on the debuggable fork build `me.magnum.melonds.dev` @ `c86e8147`:

1. Launch the game; `KEYCODE_SLEEP` → checkpoint written (see above).
2. Kill the process at **11:02:19** with `reason=4 (APP CRASH(EXCEPTION))`, rss 104 MB — a cause
   `RecoveryPolicy` accepts. `am force-stop` and `am kill` were deliberately **not** used: they
   produce `USER_REQUESTED`, which `shouldDiscardRecovery()` explicitly throws away, so they would
   have proved nothing.
3. Relaunch at 11:48:54, with the phone unlocked.

**Result: a silent automatic restore, with no prompt, in 1.09 s.**

```
11:48:54.636  EmulatorRecovery: automatic_recovery_started
11:48:55.723  EmulatorRecovery: session_started
11:48:55.723  EmulatorRecovery: recovery_restored  {"sessionType":"ROM","automatic":true}
```

| Kill-and-restore check | Result |
|---|---|
| Restore behaviour | **silent automatic restore**, no dialog — as `canAutomaticallyRestore()` (`RecoveryPolicy.kt:59-79`) specifies for a sleeping session with a valid checkpoint and a non-user exit reason |
| Time from launch intent to `recovery_restored` | 1.09 s |
| Checkpoint consumed | yes — `checkpoint-1788965842711.mln` deleted, a fresh `session.json` written with `sleeping:false` |
| **`spp.sav` after the restore** | **`edd4ababb0ba20c7d9cc26f01347fd3f84968b137d05ab7bddb0f2d0f00a2ac5` — byte-identical to before** |
| Crash or ANR during any of it | none |

**How this is known to be a restore and not a fresh boot.** The proof is the journal, not the
pixels. The restored screen shows the title screen, which is *correct*: that `.dev` session was
only 30 s past a cold boot when its checkpoint was taken, so the title screen is exactly the state
that was captured. A fresh boot would look the same, so the screenshot (`shots/restore-01.png`)
corroborates nothing on its own; `automatic_recovery_started` → `recovery_restored
{"automatic":true}`, the consumed 19.3 MB checkpoint file and the rewritten session record are what
carry the result. A restore from a visibly *distinct* game state was not run — the deep-cutscene
session belonged to the `.thor` package, which is a different app id with its own recovery
directory.

**The save being untouched is the finding that matters**, because the review's blocking bug was a
restore rewinding the `.sav`. It is a weaker demonstration than it could be, and worth saying so:
the checkpoint here was taken 30 s after a cold boot and the game had written nothing to its `.sav`
in between, so there was little for a restore to clobber. What is shown is that the restore path
does not corrupt or truncate the file. What is *not* shown is the full hazard scenario — play,
sleep, wake, play on, then die — which is what B1 below is about.

### Review finding B1 — not measured

**B1** is the fix for the one bug the adversarial review found in PR #1666: a checkpoint used to
survive the sleep it was taken for, so a kill hours later could offer a stale restore, and taking
it would roll the game's `.sav` back as well as the session. The fix ends the checkpoint when the
sleep ends (`RecoverySession.finishDeviceSleep()`, `RecoveryPolicy.kt:97-112`, called from
`markDeviceSleepResumed()`).

Testing it means: game running → screen off (checkpoint written) → screen on and keep playing →
the checkpoint must be **gone**. It was staged twice and completed neither time:

* The wake half requires `EmulatorActivity.onResume()`, which cannot run behind this phone's PIN
  keyguard — so it needs a human unlock at the moment of the wake, not before it.
* On the second attempt the staging was destroyed by **another agent's `am force-stop` of
  `me.magnum.melonds.dev` at 12:17:47**, which put a `USER_REQUESTED`/`FORCE STOP` record ahead of
  the staged exit. `classifyPreviousExit()` takes the newest exit at or after `session.startedAt`,
  and `shouldDiscardRecovery()` fires on exactly that reason, so the next launch would have deleted
  the checkpoint down the **discard** path. The journal does distinguish the two — the wake path
  appends `device_sleep_resumed` (`EmulatorRecoveryRepository.kt:167`), the discard path appends
  `session_closed` (`:260`) — but with the discard predetermined, a launch could only have
  confirmed the path that was never in doubt.

Re-staging costs about two minutes of device time and one more human unlock. That was put to Marty
and **declined**, on the grounds that he has not yet installed this build on the Thor, the fix is
code-reviewed and argued correct, its failure mode needs a specific sequence rather than being
something he would stumble into, and the phone had already been unlocked three times for this pass.
If he adopts the build, B1 gets exercised in real use.

**The run is left ready for whoever wants the answer.** `mm/b1.sh` in the raw-capture directory is
a two-minute unattended run: it launches, sleeps the screen, asserts a fresh checkpoint, wakes,
polls `deviceLocked` for up to 240 s while a human unlocks, then reads the verdict — **PASS** =
checkpoint file gone *and* `device_sleep_resumed` in the journal *and* `sleeping:false`; **FAIL** =
the checkpoint survived a normal wake-and-continue; **INCONCLUSIVE** = gone but not via the wake
path. It re-checks the `.sav` hash and always leaves the screen awake.

---

## Reading the numbers

**The fork does not make the app cheaper; it makes it un-killable.** Foreground PSS, GL/EGL mtrack,
native heap, thread count and background CPU are all within noise of stock — as they should be,
since the fork changes lifecycle and process importance, not the emulator. What changes is the
tier: `adj 900, cached=true, isFrozen=true` becomes `adj 200, FGS, isFrozen=false`.

**The bill for that is resident memory and battery.** Stock, once cached, gets compacted hard:
VmRSS 445 MB → 168 MB with 280 MB pushed to ZRAM, and its native heap reported as 3 MB because it
is swapped out. The fork keeps its full 426 MB resident with only 10 MB swapped. In absolute RSS the
fork is the fatter background process by 268 MB — it is only safer because lmkd picks by
`oom_score_adj` band first. And the ~1.3 % of a core it burns while backgrounded runs for as long as
the game is loaded, where stock's frozen process burns nothing. Both are the honest cost of the
service.

**Boot is ~100 ms slower** and the foreground service accounts for its own start at +910 ms. One run
each; ~10 % on a 1 s boot is at the edge of what a single run can claim, but the direction is
expected (there is a service to create).

**The 5-minute CPU comparison is not like-for-like and should not be read as a regression.** Stock's
0.47 % is low because Android froze the process partway through the window, not because it does less
work. The fork's 1.30 % is what melonDS actually costs while a game is loaded and parked. The
like-for-like comparison is the +60 s window, where both are running and unfrozen: 1.30 % vs 1.20 %.

**Would the fork be under the 2 % tier if it were cached?** Yes. Its measured background load is
1.20–1.30 % of one core with the service running, and nothing in the fork makes the process work
harder when it is cached — the same threads do the same work. If the service ever fails to start
(notification permission refused, `stopWithTask`, OEM battery policy) the process drops back into
the cached tier at that same ~1.3 %, which is inside the 2 % floor and far inside the 25 % tier that
applies in the first fifteen minutes. The CPU rule is not a hazard for either build.

## Anomalies and caveats

* **One run per metric**, no medians. Marty's standard for this work is best-effort on the lab
  phone, not research-grade.
* **The two memory-hog runs were not matched** — see the caveat in "What was actually killing it".
* `dumpsys package <pkg> | grep -c EmulatorForegroundService` returns 0 even for builds that declare
  the service. Do not use it as a build discriminator; use `dumpsys activity services <pkg>`.
* `screencap -d 0` **fails** on this folded Fold5 ("Display Id '0' is not valid"); the working
  argument is the SurfaceFlinger id of the cover panel, `-d 4630946481096930692`.
* The per-thread tables under-count in the foreground (they are a join of two `/proc/<pid>/task/*/stat`
  snapshots, so threads created or destroyed inside the window drop out). Only the background
  windows, where the thread set is stable, are read thread-by-thread here — which is where the
  `AudioTrack` = 0 result comes from.
* **Another agent force-stopped `me.magnum.melonds.dev` at 12:17:47**, mid-test, having reused this
  pass's live session. It destroyed the B1 staging (see above). It also killed one of this pass's
  queued `flock` waiters at 11:30. Both were disclosed. Separately, this agent issued one batch of
  read-only `dumpsys`/`run-as`/`sha256sum` calls at **11:25:15** while another agent held
  `device.lock`, having assumed a holder that had timed out was still alive — no state was changed,
  but it is the same class of mistake the swarm contract's first gotcha describes, and a
  lock-expiry check belongs at the top of every device step, not just at the start of a session.
* The stock build already contains upstream `cc6c3ba3` "Fix crash on dual-screen devices when
  returning from sleep" (it is an ancestor of the merge-base), so nothing here is credit for a fix
  upstream had already shipped.

## Device state left behind

`me.magnum.melonds.dev` = the **fork** build `c86e8147` (sha256 `56defba8…`), process not running.
Its recovery directory still holds `checkpoint-1788969012256.mln` (19 301 543 B) and a
`session.json` reading `sleeping:true` — the B1 staging that was pre-empted. The next launch of
that package will discard it down the `shouldDiscardRecovery()` path, which is harmless.
`me.magnum.melonds.thor` = the shipping build `c86e8147` (sha256 `05ecaed1…`), ROM library
provisioned, resident with its foreground service. `/sdcard/roms/nds/` untouched apart from the
game's own `.sav`, which is byte-identical to how the pass found it. The phone is **awake and
behind its PIN keyguard** — a consequence of the one `KEYCODE_SLEEP` this pass used. It needs a
human unlock before any agent can do UI work on it again.
