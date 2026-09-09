# What this fork is for

In Marty's words, 2026-09-09:

> "this should be built for idiots like me who don't care to understand supersampling
> techniques we just want to play majoras mask, have it locked framerate and look awesome,
> and not fucking crash and lose my game"

That is the specification. Three things, in priority order:

1. **Don't crash, and never lose a save.** A lost session is the only failure that actually
   costs the player something irreplaceable. Everything about lifecycle, autosave, crash
   recovery and surface handling serves this, and it outranks any amount of speed.
2. **Locked framerate, looks great, no thought required.** Not "configurable to look great" —
   correct out of the box, on this hardware, without the player learning what a texture
   filter is. Opinionated defaults beat exposed options.
3. **Just play the game.** No setup ritual, no mapping controllers by hand, no folder wizard
   twice, no reading a settings guide to find out that a debug toggle has been costing
   performance for a year.

**How to use this when ranking work.** An item earns its place by serving one of those three,
for a player who will never read a settings screen. It does not earn its place by being
measurable. A change that moves a benchmark but that no player would notice is not a win here;
a change that removes a decision the player should never have had to make is.

The counter-example is on the record: hardware shaders had been disabled in Marty's config for
an unknown length of time, costing a large and immediately noticeable amount of performance.
No measurement pass in this project found it. He found it by asking why a setting was not on
by default. The lesson is that the settings surface is a defect in its own right, not a
feature to be documented.

---

# Azahar fork (thor/main) — improvement plan

**Revision 2, 2026-09-09.** Tree: `thor/main` @ `553e5deb2`. The first revision of this plan was
written on 2026-09-08 against `2b870bc70`, before rc1, before rc2, and before anything had been
measured or played. Two release candidates and one field session later, most of its top tier has
shipped, some of its reasoning has been disproved, and its ranking is wrong: it optimised for
frame time and memory, and the measurements say both are fine. This revision marks what shipped
with the number it actually bought, re-ranks the rest against measurement rather than guesswork,
and states four strategic conclusions the first revision only posed as questions.

Section numbers changed. The legal boundaries are still §6 and are unchanged. The measurement
recipes (§2) are unchanged apart from noting that the kit that implements them now exists.

Targets: AYN Thor (Android 13, SD 8 Gen 2 / Adreno 740, 8-12 GB, dual screen) first; desktop
Linux second. The fork never goes upstream, so maintainability-vs-benefit is not a constraint;
legal exposure is (see §6).

**The standing caveat, stated once and true of every number in this document.** Every measurement
this fork has ever taken was taken on a **Galaxy Z Fold5** (SM-F946U1, Android 16), folded on its
2316x904 cover panel, one run per metric, awake throughout, on a single screen with no real
secondary display. Same SoC as the Thor. Not the same device, panel, thermal envelope, lid or Android
version (the RAM SKU is now known: ~11.0 GiB, the 12 GB part). **No performance number in this
fork has ever been measured on the target hardware** — though two read-only `adb` sessions on
2026-09-09 did read the Thor's identifiers, OS level, Vulkan driver, memory, displays and crash
history; see `baselines/2026-09-09-thor-device-snapshot.md` and `-thor-diagnostics.md`.
Item **D-0 (RESCOPED: vibe check only, not a baseline — see Standard of evidence)** exists to fix that and is the highest-ranked item in the plan.

---

## 0. Where the fork actually stands

### 0.1 What is solved

| Problem the plan was built around | Status | Evidence |
|---|---|---|
| **Background CPU burn → `EXCESSIVE CPU USAGE` kill** | **Solved.** 8.19 % → **0.33 %** of one core while paused, against a 2 % limit. The *burn* was a real problem on any device; the *kill* it was named for has only ever been recorded on the Fold5 — the Thor's `exit-info` has no excessive-CPU record for any package (§4.2a). | rc1 vs baseline, `baselines/2026-09-08-rc1-vs-baseline.md`. The `AAudio_1` thread that burned 7.82 % producing silence is gone. |
| **Resident memory** | **Largely solved.** TOTAL PSS 1.70 GiB → **1.24 GiB** (−27 %); GL mtrack 770 → **312 MB** (−59.5 %); a further −49 MiB of EGL in rc2. | rc1 and rc2 baselines. |
| **Frame pacing** | **Was never a problem, and is now better anyway.** Zero janky and zero dropped frames on *every* build measured, including the pre-fork baseline. rc2's 60 Hz panel mode moved p95/p99 from 25 ms to 16 ms — smoothness, not throughput. | rc2 baseline; graphics-preset pass. |
| **Emulation speed** | **Not a problem.** ACNL holds 60 fps at **4x** with zero janky frames, 3.9 ms of GPU per 16.7 ms frame, and 96-101 % emulation speed at every scale from 1x to 4x. | `graphics-settings-guide.md` §2. |
| **Boot with autosave resume** | **Improved 20.5 %** (3527 → 2805 ms), mechanically explained: rc1 spent 738 ms loading a shader cache into a renderer the savestate load then destroyed. | rc2 baseline; the −738 ms matches the −722 ms delta almost exactly. |
| **Controller unusable on a fresh install** | **Solved and confirmed in the field.** Marty's Thor pad was seeded correctly on first launch with no manual mapping. | `qa-notes.md`. |
| **Savestates lost on every fork upgrade** | **Solved, accidentally, and we told him the opposite.** See §4.3. | `qa-notes.md`; `savestate.cpp:78-91`. |

### 0.2 What is not solved

1. **The background memory trim never fires.** M-2 shipped in rc2 and was never entered once in
   5.5 minutes of logcat covering HOME plus three minutes parked. The foreground service holds the
   process at `procState=FGS`, `oom_score_adj=200`, `isFrozen=false`, so Android never sends
   `TRIM_MEMORY_UI_HIDDEN` or worse. This is an **open bug**, not a delivered item. See **M-2b**.
2. **The foreground service is now doing more harm than good** — it is the reason for (1), it
   defeats the cached-app freezer, and the kill it was built to prevent has been solved by other
   means. See §4.1 and item **T-7**.
3. **Custom textures are never freed.** No unload path anywhere in
   `src/video_core/custom_textures/`; `CustomTexture::data` is a `std::vector<u8>` that lives for
   the process. A 1080p pack converges on 7.2 GiB, a 4K pack on 29 GiB. See **M-8**.
4. **We widened an upstream crash and do not know how.** Changing the texture filter mid-game
   crashed 12 times in 20 on the pre-fork baseline `b8aa5f893` and **19 times in 20 on rc1**. See
   **X-1**; this is the only item in the plan where the fork made something measurably worse.
5. **No performance number has been measured on the Thor**, no sleep/wake or deep-suspend soak
   has ever been run on any device, and no battery figure exists anywhere. Two read-only
   snapshots on 2026-09-09 read the device's static properties and its crash history, which is
   not the same thing as running the emulator on it. See **D-0**.
6. **The lifecycle work is almost entirely unverified.** Every rc1 Vulkan fix, every rc2
   dual-screen fix and both rc2 input fixes are code-review-driven with no reproduction. Two
   crashes we fixed were never made to happen on purpose.

### 0.3 The pattern worth naming

Three times now, our own documentation has asserted a limitation that the code did not have:

- the stuck-gamepad diagnosis (`fix/resume-input-focus` concluded the pad was unmapped; it was
  mapped — `qa-notes.md`);
- savestate build-locking (both release notes tell the user states are rejected across builds;
  our own autosave work made them compatible — §4.3);
- and, in this revision, the M-1 risk assessment (written against a 64 MiB oversize threshold that
  is actually 16 MiB — `majoras-mask-on-thor.md` §3.5).

In every case the document was written from code reading and never re-read after the code moved.
**Rule for this fork: a claimed limitation is a claim like any other and expires when the code it
describes changes.** Where this revision restates a limitation, it has been re-read against
`553e5deb2`.

---

## 0b. The Thor: what we know and what we are guessing

Sources: web research (`research/thor-research.md`), the Fold5 probes, the code sweeps, the
RetroArch storage diagnosis (`research/retroarch-thor-storage.md`), the field QA notes, and —
since 2026-09-09 — **`baselines/2026-09-09-thor-device-snapshot.md`, a read-only `adb` snapshot of
the device itself**. That snapshot is the first data ever taken from the Thor. It settles the
identifiers, the driver version and the crash history; it does **not** settle memory, thermals,
panels or anything that needs a running build, so D-0 is still owed.

### 0b.1 Hardware facts to design against

| Fact | Value | Design consequence |
|---|---|---|
| Identifiers (**measured 2026-09-09**) | `ro.product.manufacturer=AYN`, `ro.product.model=AYN Thor`, `ro.product.brand=qti`, `ro.product.device` = `ro.product.name` = `kalama` | `ThorDefaults.knownDevices` matches manufacturer + model. Brand is `qti`, so a brand match fails; `kalama` is Qualcomm's SM8550 platform name shared with unrelated hardware, so a device match is unsafe. |
| SoC (**measured**) | `ro.soc.model=QCS8550` — Snapdragon 8 Gen 2 (SM8550), Adreno 740 | Same silicon as the Fold5, so Fold5 **CPU** numbers transfer. Display, thermal and lid numbers do not. |
| GPU driver (**measured**) | Qualcomm Adreno Vulkan **512.676.53**; the Fold5 is **512.676.1** | Same GPU, different driver build. **GPU numbers and every driver-gated workaround transfer less freely than the SoC row implies** — G-6's blacklists, the swapchain work and the texture-filter crash all live in driver behaviour. Re-check on the Thor's driver before relying on a Fold5 GPU result, and name the driver in any upstream report. |
| CPU map (Fold5) | cpu0-2 A510 @2.0; cpu3-4 A715 + cpu5-6 A710 sharing one freq domain @2.8; cpu7 X3 @3.36; governor `walt` | Azahar sets no affinity, no priority, no ADPF anywhere. See T-5 (parked). |
| Top screen | 6" AMOLED 1080x1920 @ 120 Hz | rc2 puts it in its 60 Hz mode and calls `Surface.setFrameRate(60, FIXED_SOURCE)`. **Unverified that the Thor's mode list contains a mode our tolerance matches.** |
| Bottom screen | 3.92" AMOLED 1080x1240 @ 60 Hz | Now gets the same refresh-rate treatment. 3DS bottom is 320x240, so 3.4x saturates it. |
| RAM | 8 / 12 / 16 GB SKUs | **We do not know which SKU Marty has.** At ~1.3 GiB PSS the emulator is the largest process on an 8 GB device, which is the whole premise of the LMK argument in §4.1. Read it in D-0. |
| OS (**measured**) | Android 13, SDK 33 — confirmed by `getprop`, no longer inferred from the spec sheet | Cached-app freezer exists on 13. `FOREGROUND_SERVICE_SPECIAL_USE` not required until 34 (already declared). |
| Cooling | Active fan, AYN Quiet/Smart/Sports, firmware-controlled | **No app API exists.** We cannot read or set it. It is a variable to hold constant in soaks, and an observation to record during the battery soak (see D-0). |
| Control centre | AYN button: per-screen brightness, per-screen volume, turn either display off, long-press = bottom screen off | The bottom-screen-off shortcut is a real user path that our secondary-display code must handle. See **T-9**. |
| Launcher / frontends | Marty runs **Argosy** (`com.nendo.argosy`) and **Cocoon** on the Thor, over a library at `/storage/emulated/0/roms/<system>` | **This is not the AYN launcher, and it changes R-5's premise.** See **R-11**. |

### 0b.2 Thor-specific code paths (post-rc2)

`display/SecondaryDisplay.kt` (Presentation only when a real non-virtual display exists; identity
by `displayId`; started/stopped with the activity; one 500 ms re-check for a panel still powered
off), `native.cpp` secondary surface JNI, `renderer_vulkan.cpp` (both renderers now test the
*surface*, not the window pointer), `framebuffer_layout.cpp` (`SingleFrameLayout` no longer
publishes a bottom rect for a hidden screen), `utils/RefreshRateUtil.kt` (tolerant match,
same-resolution mode, applied to the Presentation too), `utils/ThorDefaults.kt` +
`utils/GraphicsPresets.kt` + `utils/SettingsProfile.kt` (first-run profile and the re-apply
action), `utils/ForegroundService.kt`, `activities/EmulationActivity.kt`.

### 0b.3 Lifecycle map, corrected for rc2

| Event | Covered today | Still open |
|---|---|---|
| **Home** (screen on) | pause + autosave + `awaitAutoSave` in `onStop`, FGS held, clock resync on resume, Presentation released, perf overlay stopped | The process stays at FGS state, so no trim and no freeze (M-2b, T-7). `awaitAutoSave` blocks the main thread up to 4 s in `onStop` (**AS-2**). |
| **Lid close** | Same, plus `DisplayListener` traffic that rc2 made safe (identity by id, stopped-state guard) | Everything above. Whether the Thor's bottom panel actually reports `STATE_OFF` on a lid close is **unverified**, and the whole rc2 dual-screen design depends on it (T-9). |
| **Lid open / return** | `onStart`/`onResume` re-pick the display with one 500 ms re-check; clock resync; autosave resume if the process died; re-sent launch intent resumes in place | Whether the frontend's intent even reaches `onNewIntent` (R-11). |
| **Background during boot** | Pause during boot is parked and applied when the loop starts | — |
| **Guest-initiated shutdown** | — | `doFrame()` reads `window` while `TryShutdown()` resets it (R-10). |

---

## 1. What shipped, and what it bought

Legend: **WIN** = measured improvement. **SHIPPED** = in the build, effect not isolated or not
measurable on the test title. **OPEN** = shipped but does not work. **UNVERIFIED** = shipped, no
reproduction of the problem it fixes exists.

### 1.1 rc1 (`f218c0cdb`, 2026-09-08)

| ID | Item | Outcome |
|---|---|---|
| **B1** | Stop the audio stream on pause (`Sink::SetPaused` for cubeb/SDL2/OpenAL/null) | **WIN.** Paused CPU 8.19 % → 0.33 % (−96 %). `AAudio_1` gone from the thread table. Exceeded the < 0.5 % target. |
| **B2** | Cheap audio callback when muted or at real-time speed | **WIN (folded).** Part of the number above; not isolated. |
| **B3** | Cancel the perf-overlay timer while paused; make a pause during boot take effect | **SHIPPED.** Contributes to the paused number; the boot-window half is a correctness fix with no metric. |
| **B4** | Name the unnamed threads (`VulkanFenceWait`, `DspLle`, a `cubeb`-named init thread whose children inherit the name) | **SHIPPED.** The thread table is now readable; this is why the rc2 `−3 threads` row could be attributed at all. |
| **M-1** | Upload ring 512 MiB → 64 MiB, plus a one-shot dedicated staging buffer for oversized requests | **WIN.** GL mtrack 770 → 312 MB; TOTAL PSS −27 %. The single largest memory change in the fork. **Caveat in §1.4.** |
| **G-2** | Skipped duplicate frames `Flush()` instead of `Finish()` | **SHIPPED**, no measurable effect on a 60 fps title. Would show on a 30 fps title; never tested on one. |
| **G-4** | Persist the driver `VkPipelineCache` on pause and every five minutes | **SHIPPED.** A 9429 KB cache survives a force-stop and reloads on the next boot. |
| **R-1** | `Swapchain::Destroy()` iterates the vectors instead of using a stale count | **UNVERIFIED.** Real defect, never reproduced. |
| **R-2 (part)** | Classify acquire/present results by what could fix them; cap recreation at 8; bound the `GetRenderFrame` fence wait | **UNVERIFIED.** Removes the `UNREACHABLE()` process kill on `VK_ERROR_DEVICE_LOST` after a wake and the 100 %-CPU spin. The `vk_master_semaphore` half is **not** done (R-2b). |
| **R-3** | Destroy the previous `VkSurfaceKHR` in `Swapchain::Create`; clear `last_render_surface` in `surfaceDestroyed`; give the secondary window the same path | **UNVERIFIED.** |
| **R-5** | `onNewIntent` resumes in place when the intent names the running title | **SHIPPED**, premise now in doubt — see **R-11**. |
| **R-6** | `configChanges` covers orientation/size/layout/smallestWidth/uiMode/density | **SHIPPED.** Also removes the `initMultiplayer()` re-run. |
| **R-9** | Trust restored `isEmulationReady` only when the core survived | **SHIPPED.** |
| **H-3 / C-2 / "H-D"** | `tools/thor/measure.sh` (D-1..D-5 + identity + soak), `perf_log_interval`, periodic `record_frame_times` flush, `<profileable android:shell="true"/>` on the release build | **WIN.** Every number in this document exists because of this. |
| **H-2** | `src/citra_bench/` headless harness + `EmuWindow_Null` | **SHIPPED**, and unused so far — no host benchmark has been cited in any measurement pass. |
| (pre-rc1) | Guest clock resync; autosave to a reserved slot with a freshness check; zstd-streamed savestates; foreground service; controller auto-mapping | **WIN** for the clock (the reason ACNL was playable at all), **WIN** for autosave (see §4.1), **confirmed in the field** for auto-mapping. |

### 1.2 rc2 (`31ea3ed33`, 2026-09-09)

| ID | Item | Outcome |
|---|---|---|
| **G-1** | The secondary window is no longer created against a hidden 1920x1080 `VirtualDisplay`; both renderers test the surface; the Vulkan present window is built and released with the surface | **WIN.** EGL mtrack −49 MiB foreground / −41 MiB paused, threads −3, `HiddenDisplay` gone from `dumpsys display`. Frame-time effect: **not measurable on this title** — the emulation thread was never the bottleneck. |
| **T-2** | Presentation identity by `displayId`; display list queried once; stopped-state guard; one 500 ms re-check; the "Built" heuristic replaced | **UNVERIFIED.** The Fold5 cannot produce a lid cycle against a real second panel. This is the change with the most moving parts in the fork and the least evidence. |
| **T-3** | Top-panel touch no longer injects touchscreen input (`bottom_screen_enabled` honoured; `SingleFrameLayout` stops publishing a hidden rect; `TouchMoved` guarded) | **SHIPPED**, covered by host tests, unverified on the Thor. |
| **T-4** | `enforceRefreshRate` actually applied (assign the `LayoutParams` back, tolerant match, same-resolution mode, Presentation too) + `Surface.setFrameRate` on both `SurfaceView`s | **WIN.** p95 and p99 present interval 25 ms → 16 ms; buckets go from bimodal 8/25 ms to 1784/1796 in a single 16 ms bucket. **This is smoothness, not throughput** — neither build janked or dropped a frame. |
| **L-1** | Resume first, then load the disk shader/pipeline cache into the renderer that survives | **WIN.** Boot-with-resume −722 ms (−20.5 %); the deleted duplicate pass was 738 ms. |
| **G-3** | `async_shader_compilation` on by default on Android | **SHIPPED**, effect not measured — it needs a cold cache and a new area, and every measurement resumed the same autosave. |
| **L-2** | "Initializing…" before `System::Load`; `initMultiplayer()` off the UI thread; "Complete" after the resume | **SHIPPED.** Perceived-boot only. |
| **M-2** | `onTrimMemory` → JNI → `System` request → `RasterizerCache::UnregisterAll()` + heap purge in the paused branch | **OPEN — never fires.** See §1.3. |
| **T-6** | `ThorDefaults` first-run profile + "Apply Thor defaults" menu action; touch overlay hidden by default while a physical pad is connected | **SHIPPED**, unverified on the Thor. Note the first-run pass only writes keys `config.ini` has no value for, so **on an upgrade the profile does nothing** until the menu action is run — which is why it had to be the first line of the rc2 notes. |
| (no id) | Unbound gamepad keys swallowed; a drawer restored open after a kill is closed; focus returned to the game on drawer close | **SHIPPED.** Field QA corrected the diagnosis: the pad *was* mapped, so the restored-open drawer is the fix that mattered. |

### 1.3 M-2 is an open bug, not a delivered item

The code is in rc2 and correct as far as anyone can tell. It was **not exercised even once**:
neither `Trimming memory at level N` (Kotlin) nor `Releasing cached resources on request of the
frontend` (`core.cpp:334`) appears anywhere in 5.5 minutes of logcat covering HOME plus three
minutes parked, and GL mtrack behind HOME stays within 1.5 MB of its foreground value on **both**
rc1 and rc2. The 41 MB of EGL that does disappear at HOME is the framework releasing the
swapchain, and it happens on rc1 too.

The reason is the foreground service from rc1. `Proc # 2: prcp M/S/FGS`, `procState=FGS`,
`oom_score_adj=200`, `isFrozen=false` — Android does not send `TRIM_MEMORY_UI_HIDDEN` or worse to
a process at foreground-service importance. So the ~448 MiB of GL mtrack that M-2 was written to
release stays resident for as long as the game is backgrounded, which on this device is most of
its life.

This is the plan's clearest example of two items fighting each other, and it is what makes §4.1
the central strategic question rather than a footnote.

### 1.4 Corrections to what shipped

- **M-1's oversize threshold is 16 MiB, not 64 MiB.** `ONE_SHOT_STAGING_DIVISOR = 4`
  (`vk_texture_runtime.cpp:168`) divides the 64 MiB ring, so `FindStaging` diverts anything over
  16 MiB (`:316`). The one-shot path was assessed as a rare fallback; with a texture pack it is the
  normal path for every large texture (164 of 4,513 in one pack cross it). It has never been
  exercised in the field and `AllocateOneShotStaging` ends in `LOG_CRITICAL` + `UNREACHABLE()` if
  `vmaCreateBuffer` fails — a hard native crash where the old code had a hard assert. Net: rc1
  turned "impossible" into "possible but untested". Re-read M-1's risk row with 16 MiB in it.
- **G-1's payoff was memory and threads, not frame time.** The first revision estimated
  "−1..−3 ms/frame on the emu thread". The measured foreground CPU went *up* 1.2 pp (single-run
  noise) and frame intervals did not move. The estimate was wrong because the emulation thread had
  headroom the plan assumed it did not have.
- **T-4's win is a deliberate refresh-rate change.** Reading "p95 −9 ms" as extra performance would
  be wrong; both builds were already frame-perfect.
- **The release notes for rc1 and rc2 both misstate savestate compatibility.** See §4.3. The next
  release notes must be corrected.

### 1.5 Enablers that were never done, and did not block anything

- **H-1 (build hygiene).** `ccache` is still not installed, the tests build still has
  `ENABLE_LTO:BOOL=ON`, and `src/android/gradle.properties` still says `-Xmx1536m`. Two waves of
  agents shipped anyway. **Demoted to a nice-to-have** (see T-tier below); it was ranked Tier 0 on
  a guess about iteration time that the last two days did not bear out.
- **C-1 (APK on tag).** `.github/workflows/apk-on-tag.yml` does not exist; every APK so far was
  built locally. Kept, but see **BUILD-1**: a CI-built APK would be signed with a different key
  *and* would set `BUILD_VERSION` to the tag, which silently breaks savestate compatibility (§4.3).
  C-1 is no longer a free win.

---

## 2. Metrics and exactly how to measure them

These recipes are correct, heavily used, and unchanged. What changed is that `tools/thor/` now
implements them; prefer the kit over hand-rolled commands, and read `tools/thor/README.md`.

### 2.1 Host-side

**H-A. Catch2 micro-benchmarks (no ROM needed).** Catch2 3.8.0 in `externals/catch2` has
`BENCHMARK` built in. Add `src/tests/bench/*.cpp` to `src/tests/CMakeLists.txt`, tag `[bench]`,
exclude from the default `ctest` run with `~[bench]`. Run:

```
../azahar-build-tests/bin/Release/tests "[bench]" --benchmark-samples 20 --benchmark-no-analysis -r xml -o bench.xml
```

Diff the `<mean>` values between before/after XML. Candidates: `System::SaveState`/`LoadState` on a
synthetic populated `MemorySystem`, `StaticPipelineInfo::Hash`, `PageTable` construction/`Clear`,
`MemorySystem` construction, `TimeStretcher::Process`.

**H-B. Headless full-boot harness.** Built: `src/citra_bench/` with `EmuWindow_Null`, software
renderer, `frame_limit = 0`, null audio, `-p movie.ctm` for deterministic input, ROM path from
`AZAHAR_BENCH_ROM` (**never committed**, §6). Prints wall time, frames, CPU time, peak RSS, current
`VmRSS`/`RssAnon`, and `PerfStats::Results`. Metrics: `frames/s`, `cpu_ms/frame`, `peak_rss_mb`,
`boot_ms`, `savestate_ms`, `savestate_bytes`. **It has never been used in anger** — the first item
that needs a host-side number should use it or say why not.

**H-C. Build-time metric:** `ninja -d stats` / wall time of a `tests` rebuild after touching
`src/core/core.h`.

### 2.2 On-device (`tools/thor/measure.sh`; `PKG=org.azahar_emu.azahar.thor`)

**Identity first, every time.** `measure.sh identity` prints versionName/versionCode, the
`Azahar Version:` log banner, pid, wakefulness and the virtual-display list. Never attribute a
number to a build without the versionName matching the APK. Two known kit gaps:
`identity` reports "scrcpy: not running" even when it is (the device side runs as `app_process`;
the reliable check is a virtual display named `scrcpy` in `SurfaceFlinger --display-id`), and the
per-thread CPU join drops threads created or destroyed inside the window, so only the *paused*
windows are safe to read thread by thread.

**D-1. Paused CPU %** (the kill metric). `measure.sh cpu 60`: per-thread `utime+stime` deltas from
`/proc/<pid>/task/*/stat` joined across a 60 s window, plus the process total and the
`dumpsys activity processes` block (`oomAdj`, `procState`, `isFrozen`, "run cpu over"). Take in
three states: foreground running / HOME with screen on / **screen off** (never yet done).
Target: **< 0.5 %** total while paused. Current: 0.33-0.36 %.

**D-2. Memory.** `measure.sh mem`: `dumpsys meminfo` TOTAL PSS, GL mtrack, EGL mtrack, Native Heap;
`VmRSS`/`VmSwap`/`RssAnon`/`RssFile`; `oom_score_adj`. Take at 45 s after first frame, 45 s after
HOME, 3 min after HOME. Targets: PSS paused **< 800 MB** (currently ~1.33 GB); GL+EGL **< 400 MB**
(currently ~490 MB foreground).

**D-3. Boot time.** `measure.sh boot`: epoch-stamped logcat from the `am start` to the first
emulated frame, broken into `am_proc_start`, `Displayed`, `VK_DRIVER`,
`LoadDriverPipelineDiskCache`, `Load completed`, first frame. Two scenarios: autosave ignored, and
autosave resume. **Discard the first boot after an install.**

**D-4. Frame timing.** `measure.sh frames 30`: `dumpsys SurfaceFlinger --timestats` per-layer
present-to-present buckets, p50/p95/p99, janky/dropped/late-acquire counts, averageFPS. Alternative:
Perfetto with `android.surfaceflinger.frametimeline` + `android.surfaceflinger.layers`
(`tools/thor/frametimeline.pbtxt`), grouped by `layer_name` — the only way to get **per-panel**
numbers on a real dual-screen device, which is what D-0 needs.

**D-5. Kill history.** `measure.sh exitinfo` before and after every soak; the description string
carries the CPU numbers. `measure.sh soak` runs play → HOME → screen off → wait → wake →
re-send intent → report.

**D-6. CPU profile.** `simpleperf record -p $PID --call-graph fp -e cpu-clock -f 1000
--duration 30` — the release build is `<profileable android:shell="true"/>`, so this works on the
build that actually ships.

**D-7. Azahar's own perf stats.** `perf_log_interval` (Settings > Debug, 0-60 s) writes one
`perf: game_fps=… speed=… frametime=… gpu=… swap=… ipc=… svc=… rem=…` line per interval to logcat
and the log file. `record_frame_times` flushes its CSV every ten seconds.

### 2.3 Baseline table

| Metric | Pre-fork `b8aa5f893` | rc1 | rc2 | Target | On the Thor |
|---|---|---|---|---|---|
| Paused CPU after HOME, 30 s | 8.19 % | **0.33 %** | 0.36 % | < 0.5 % | **never measured** |
| Paused CPU, screen off | — | — | — | < 0.5 % | **never measured on any device** |
| Hot threads paused | `AAudio_1` 7.82 % | none > 0.4 % | none > 0.4 % | none > 0.1 % | **never measured** |
| TOTAL PSS @45 s | 1 702 MB | 1 243 MB | 1 355 MB† | < 800 MB paused | **never measured** |
| GL mtrack @45 s | 770 MB | 312 MB | 449 MB† | < 400 MB | **never measured** |
| EGL mtrack @45 s | 126 MB | 126 MB | **77 MB** | — | **never measured** |
| GL mtrack after HOME | 769 MB | 450 MB† | 447 MB† | ≪ foreground (M-2b) | **never measured** |
| Threads @45 s | 55 | 57† | **54**† | — | **never measured** |
| Boot, autosave ignored | 2162 ms | 1866 ms† | 1743 ms† | < 1400 ms | **never measured** |
| Boot, autosave resume | 3535 ms | 3527 ms† | **2805 ms**† | < 3 s | **never measured** |
| p50 / p95 / p99 present | 16/25/33 | 16/25/25† | **16/16/16**† | — | **never measured** |
| Janky frames | 0 / 1797 | 0 / 1798 | 0 / 1796 | 0 | **never measured** |
| Battery, 8 h paused | — | — | — | **no target exists** | **never measured** |
| Kill history | `EXCESSIVE CPU 9.27 %` (2026-09-02) | none in 5 cycles | none in 5 cycles | none | **read 2026-09-09:** one `APP CRASH (NATIVE)` (rc1, texture filter, stack confirmed); nothing since rc2; **no `LOW_MEMORY`, no excessive-CPU record on the device at all** |

† taken with scrcpy running, at `resolution_factor = 3`; comparable rc1-vs-rc2 but **not**
comparable with the scrcpy-free pre-fork/rc1 columns. This is why the table has two measurement
regimes in it, and why D-0 should re-take the whole thing in one regime on the Thor.

Additional, from the graphics-preset pass (Fold5, scrcpy off, single window, ACNL at each scale):
1x-4x all hold a 16 ms present interval with zero janky frames and 96-101 % speed; the emulator's
own GPU counter goes 1.9 → 3.9 ms from 1x to 4x; resident memory is flat across scales; **one
texture filter at 3x costs +86 MiB of GPU memory** and **4x + Bicubic crashes natively** inside
`vkCreateGraphicsPipelines` in the Adreno driver.

---

## 3. Ranked backlog

Effort: S < 1 h, M ≈ half day, L ≈ days (agent time incl. build+verify). Risk: L/M/H.
Every item names a metric from §2 and a verification. "Par" = can run in its own worktree.

### Tier 0 — the two things everything else is waiting on

| ID | Item | Payoff (metric) | Effort | Risk | Files | Verify | Par |
|---|---|---|---|---|---|---|---|
| **D-0** | **Take the baseline on the Thor.** A full `measure.sh` pass on Marty's device at his settings (4x, both panels, Thor defaults applied): D-1 in all three states **including screen off**, D-2 at 45 s / 45 s after HOME / 3 min after HOME, D-3 both boot scenarios, D-4 **per panel** via the Perfetto frametimeline config (`--timestats` cannot separate the two layers usefully), D-5 with a real 30-minute lid-closed dwell, and a battery reading across an overnight paused session. Record: `MemTotal`, the panel mode lists for both displays, `dumpsys display` for both panels with the lid open and shut, the AYN fan mode held constant and noted, and whether the fan keeps running while emulation is paused. **The identifiers, the OS level and the Vulkan driver version are already taken** — see `baselines/2026-09-09-thor-device-snapshot.md` — so do not re-derive them; the memory SKU, the panels, the thermals and every running measurement are still owed. | Every conclusion in §0.1 is currently a Fold5 conclusion. This is the only item that can make them Thor conclusions. | M | L | `tools/thor/measure.sh`, `tools/thor/frametimeline.pbtxt` | A `docs/fork/baselines/<date>-thor-first-baseline.md` in the same shape as the existing two, with the §2.3 table's last column filled in | device-serial |
| **T-7** | **Lifecycle policy: autosave, then get out of the way.** Implement `onUserLeaveHint` (Home) and treat display-state changes (lid/screen off) separately. On lid close / screen off: write the autosave (already happens), flush the pipeline cache (already happens), run the trim **unconditionally** (M-2b), then **stop the foreground service** so the process falls to cached, receives the real trim callbacks, and is frozen by the cached-app freezer. Restart the FGS from `onStart`. On Home with the screen on, keep the FGS (fast return is worth it and the screen is on anyway). Setting-gated (`fgs_policy`: always / screen-on-only / never) so both halves are measurable. | **D-1 in the screen-off state** (expect 0 % once frozen, vs 0.33 % now); **D-2 paused PSS** (expect −450 MB once the trim fires); **battery across an 8 h paused session**; **D-5** (expect kills to appear, and to cost ~3 s each). | M | M — this deliberately allows the kill that rc1 prevented. It is only defensible because the autosave works; see §4.1. | `EmulationActivity.kt`, `ForegroundService.kt`, `EmulationFragment.kt`, `SecondaryDisplay.kt`, `native.cpp` paused branch | Three overnight soaks per policy on the Thor; battery Δ, D-1, D-2, D-5, and a resume-from-autosave that lands in the right place | Needs D-0 |

### Tier 1 — worth doing next

| ID | Item | Payoff (metric) | Effort | Risk | Files | Verify | Par |
|---|---|---|---|---|---|---|---|
| **M-2b** | **Make the trim actually fire.** Two halves. (a) Run the release from our own code path — after the autosave and the pipeline-cache flush in the paused branch — rather than waiting for Android to ask, so it happens whatever the process state is. (b) Keep the `onTrimMemory` hook for the cached case T-7 creates. Add a log line either way so "it did not fire" is never again a five-hour discovery. | **D-2 GL mtrack after HOME.** Target: from 447 MB to under 100 MB. This is the largest unclaimed memory win in the plan and it is already 90 % written. | S | M — `UnregisterAll` calls `FlushAll`, which downloads dirty surfaces; it must run on the emulation thread with a live scheduler, before the thread parks. Costs one upload hitch on resume. | `CitraApplication.kt`, `NativeLibrary.kt`, `native.cpp` paused branch, `core.cpp:334`, `rasterizer_cache.h` | D-2 at 45 s and 3 min after HOME, before/after; the log line present in both; resume hitch timed with D-4 | Sequence with T-7 |
| **X-1** | **Find out why the fork widened the texture-filter crash.** 12/20 on `b8aa5f893` → **19/20 on rc1**. Bisect the rc1 merges against the 20-attempt protocol (the crash is cheap to provoke and the count is the metric), then explain the mechanism. `fix/texture-filter-crash` already contains four genuine lifetime fixes that did not cure it, parked unmerged because they are unverified renderer changes; the next thread there is descriptor sets and attachment image views in `BlitHelper::FilterPass`. **The point of this item is the bisect, not the fix**: something we did made an upstream race substantially more likely and we do not know what, which means we do not know what else it made more likely. | Crash count out of 20 cold attempts, per merge. A mechanism, written down. | M-L | M | `fix/texture-filter-crash`, `research/texture-filter-crash.md` (on that branch), `vk_blit_helper.cpp`, `vk_texture_runtime.cpp` | The bisect identifies a merge; the mechanism explains both the 12/20 baseline and the 19/20 delta; a fix reduces the count on the Thor | Par, device-serial |
| **R-11** | **Establish how Marty actually launches a game, and whether our resume-in-place work is on that path.** He uses Argosy and Cocoon on the Thor, not the AYN launcher. Argosy's RetroArch path uses an explicit `ComponentName` + `ACTION_MAIN` + **`FLAG_ACTIVITY_NEW_TASK \| CLEAR_TASK \| NO_HISTORY`**, with the ROM as a plain absolute path and a `content://` URI only in `clipData`. If it launches Azahar the same way, `CLEAR_TASK` **destroys the running activity instead of delivering `onNewIntent`**, so R-5 never fires, and `NO_HISTORY` finishes the activity when it leaves the foreground — i.e. every lid close would be a full teardown. Measure it (`logcat` + `dumpsys activity activities` across a launch and a lid cycle from each frontend), then make `onNewIntent` handle a path extra, a `content://` data URI and `clipData`, comparing the *resolved title*, never raw URIs. | **D-5**: pid stable across a frontend launch and a lid cycle; no fresh boot markers. This is the difference between "resumes in place" working and being decorative. | S to measure, M to fix | M | `EmulationActivity.kt` `onNewIntent`, `AndroidManifest.xml` launch mode | Launch from Argosy, from Cocoon and from the AYN launcher; lid-cycle each; pid and boot markers | Needs D-0's device time |
| **FGS-1** | **Foreground-service robustness.** `onStartCommand` returns `START_STICKY`, so Android can recreate the process **with no activity and no emulator**; `onCreate` then calls `ServiceCompat.startForeground` with no try/catch, and on 12+ that can throw `ForegroundServiceStartNotAllowedException` — uncaught in `Service.onCreate` — which is a background crash loop with backoff on a device the user just woke. Fix: `START_NOT_STICKY`; try/catch around `startForeground` itself → `stopSelf()`; start the service from `onStart` as well as `onCreate` (idempotent, and `onStart` is guaranteed foreground, so a single refused start self-heals instead of silently costing the session its protection); move the stop to the *end* of `onDestroy` so teardown does not run at cached priority. | D-5: no `CRASH_NATIVE`/exception records attributable to the service; the FGS present at every D-1 sample rather than assumed. | S | L | `ForegroundService.kt`, `EmulationActivity.kt` | `dumpsys activity services` shows the service across a kill-and-restore; no exception records over a 20-cycle soak | Par; sequence before T-7 |
| **T-9** | **Bottom-screen-off must actually stop the second render.** rc2's design assumes a panel that is not showing reports `Display.STATE_OFF`, which is what `getSecondaryDisplays` filters on. Two paths have never been checked on real hardware: a lid close, and AYN's long-press-to-turn-off-the-bottom-screen shortcut *while the game is in the foreground and running*. If AYN blanks the panel without changing `Display.state`, we keep a Presentation and keep rendering, blitting, submitting and presenting a second full frame every vblank into a dark panel. | D-4 per-layer present counts with the bottom panel on vs off; `dumpsys display` state for both panels in both conditions; D-2 EGL mtrack. | S to measure; S-M to fix (fall back to `Display.getState()` plus a `DisplayManager` brightness/`isValid` check, or drive it from the app's own layout state) | L | `SecondaryDisplay.kt`, `native.cpp` secondary surface JNI | With the bottom panel off, zero present events on the Presentation layer and no secondary swapchain in `dumpsys meminfo` | Needs D-0 |
| **AS-2** | **Get the autosave off the main thread's critical path.** `EmulationFragment.onStop` calls `awaitAutoSave()`, which blocks the main thread for up to `AUTOSAVE_WAIT_MS = 4000`. An 18 MB zstd write over SAF is not fast, and ANR is itself one of the kill reasons the autosave exists to survive. Measure the actual wait first (it may be 200 ms and this is a non-issue); if it is not, move the wait into a short bounded window with the rest done under a `goAsync`-style grace, or start the save earlier (at `onUserLeaveHint`, which T-7 adds) so `onStop` only has to confirm it. | Wall time of `onStop`, measured; D-5 ANR records over a 20-cycle background soak. | S to measure, M to fix | M | `EmulationFragment.kt:639,654,1861`, `NativeLibrary.kt:682`, `native.cpp` autosave request | Timed `onStop` on the Thor at 4x with a real 18 MB state; no ANR in 20 cycles | Par |
| **R-10** | `doFrame()` reads `window` while `TryShutdown()` resets it — a use-after-free on a guest-initiated shutdown ("power off" from the HOME menu). The other half of this pair landed in rc1; this half did not. | Correctness. No metric; a crash that has not been seen. | S | L | `native.cpp` | Guest "power off" 20× | Par |
| **R-2b** | `vk_master_semaphore`'s own `UNREACHABLE()` on a lost device in the submit/wait path is not covered by rc1's recovery work; a real device loss reached from the emulation thread still aborts the process. Route it into the same classify-and-rebuild path rc1 added. | D-5: no `CRASH_NATIVE` under fault injection. | M | M | `vk_master_semaphore.cpp`, `vk_swapchain.cpp` | Fault injection via `renderer_debug`; soak | Par |

### Tier 2 — real, but waiting on Tier 0/1

| ID | Item | Payoff | Effort | Risk | Files | Verify |
|---|---|---|---|---|---|---|
| **LC-1** | Read `ActivityManager.getHistoricalProcessExitReasons` **in the app at launch**, log it, and use it to choose the resume policy (a `REASON_USER_REQUESTED` swipe should not be treated the same as an LMK kill). Turns D-5's out-of-band `dumpsys` into a first-class signal, and is what will tell us whether T-7's policy is costing kills in the field. Warning from the melonDS review: the most recent record can be a user swipe masking the LMK kill underneath — read more than one. | Every future kill has a reason in our own log, without adb. | S | L | `EmulationActivity.kt`/`CitraApplication.kt` | Force-stop, LMK-kill and swipe each produce the right classification in the log |
| **S-1** | **A savestate format fingerprint.** §4.3 establishes that our states now load across revisions — and therefore that a build which *actually changes the serialized format* will load an old state and deserialize garbage, because nothing distinguishes the two cases. `CSTHeader` has 168 reserved bytes. Generate a `SAVESTATE_FORMAT_VERSION` hash at build time exactly as `SHADER_CACHE_VERSION` already is (`CMakeModules/GenerateSCMRev.cmake:8-50` MD5s a curated file list), over the files that define the serialized graph, write it into the reserved bytes, and reject on mismatch while continuing to accept a differing git revision. Treat all-zero (every state written before this) as "unknown — accept with a warning" so rc1/rc2 states keep loading. | Removes a silent-corruption class, and **unblocks M-3/L-4 and M-4**, both of which change the serialized layout and are unsafe today. | M | M — the file list is a judgement call; too narrow is worse than too broad | `CMakeModules/GenerateSCMRev.cmake`, `scm_rev.cpp.in`, `savestate.cpp:26-91,213-232,386-392` | A state from a build with a deliberately changed serialize function is refused; an rc2 state still loads; host tests |
| **M-8** | **An unload path for `CustomTexture::data`.** There is none anywhere in `src/video_core/custom_textures/`: every decoded custom texture stays RGBA8-resident for the life of the process, so memory is monotonic in distinct textures ever seen and converges on the pack's full decoded size (7.2 GiB for a 1080p pack, 29 GiB for 4K). This, not the upload ring and not the preload budget, is what makes texture packs unusable on a handheld. Pairs with M-7 (a byte budget with LRU eviction for the surface cache). | D-2 over a 30-minute walk through a game with a pack installed: the growth curve stops being monotonic. | L | M | `custom_textures/material.{h,cpp}`, `custom_tex_manager.cpp` | Growth curve on the Thor with the 1080p tier installed |
| **M-6** | Custom-texture preload budget: take it from `largeMemoryClass` rather than total physical RAM (`custom_tex_manager.cpp:204-234` computes `total − 2 GiB`, ≈5.4 GiB on an 8 GB device), **and check before adding rather than after** — the current order overshoots by up to one texture, which in the worst pack seen is 256 MiB. Was "latent"; enabling preload un-latents it, and the pack author's own FAQ tells Android users to keep preload off, which is a workaround for exactly this. | Unit test of the budget function; no abort with preload on and a small pack. | S | L | `custom_tex_manager.cpp:204-234` | Unit test; a pack that fits stays fitting |
| **M-9** | Custom textures under Vulkan: upstream #1308 (Henriko's MM3D pack, black screen then crash on Vulkan, fine on OpenGL) and #2118 (from an **AYN Thor Max, 16 GB**, crash when a custom texture stops being used). The OpenGL half of the fix is in this tree (`c07f2cc96`, upstream PR #2481); the Vulkan half is not addressed anywhere. | A texture pack that does not crash on the renderer we actually use. | M | M | `vk_texture_runtime.cpp` custom-surface paths, `rasterizer_cache.h` | Reproduce #1308 on the Thor with the 1080p tier, then fix |
| **M-3 / L-4** | FCRAM/VRAM/extra/DSP via `mmap(MAP_ANONYMOUS)` instead of a value-initialised block; FCRAM sized to 128 MiB when `!is_new_3ds`; build `PageTable` once instead of twice. | D-2 VmRSS −128 MiB in O3DS mode plus whatever FCRAM the game never touches; D-3 boot loses a ~300 MiB memset, paid again on every savestate load. | S-M | M | `memory.cpp:47-51,105-108`, `memory.h`, `vm_manager.cpp:56` | H-B `boot_ms`/`peak_rss_mb`; savestate tests; D-2. **Gated on S-1** |
| **BUILD-1** | Release signing and upgrade safety. Every build so far is signed with the Android debug key. The moment a properly-signed build exists it cannot upgrade in place, which forces an uninstall — and an uninstall drops the private SharedPreferences holding the SAF grant and the user-directory path, dumping Marty into the first-run wizard. Decide the signing story **before** the next rc, and add a documented export/restore for the user directory either way. Related: a CI tag build sets `BUILD_VERSION` to the tag, which reintroduces the savestate break (§4.3) — C-1 must pin `BUILD_VERSION` or accept that. | No forced uninstall, ever. | S-M | L | `build.gradle.kts:96-116`, `.github/workflows/`, `CMakeModules/GenerateBuildInfo.cmake:56-71` | A signed build installs over a debug-signed one, or the plan says plainly that it cannot and what to do instead |
| **G-6** | Re-test the timeline-semaphore blacklist on **Turnip** (`vk_instance.cpp:456-458` blames "Qualcomm drivers" generally) and consider a driver-version gate for the EDS / custom-border-color blacklists on the 512.676+ driver. On the proprietary driver, no extended dynamic state means rasterization and depth-stencil state fold into the pipeline key, so a title needs many more pipelines than it would elsewhere — this is the largest remaining structural stutter cause. | D-4 p99 on a cold cache; pipeline cache size; 30-minute soak per driver. | S (code) + device time | H — the blacklist exists because of driver bugs | `vk_instance.cpp:444-477` | Both drivers, 30 min each, revert on any artifact |
| **C-1** | `apk-on-tag.yml`: a copy of the `android` job with `on: push: tags`, thor flavour, no SBOM/attest, no secrets, keeping `actions/cache` + ccache. | Reproducible APKs without tying up the workstation. | S | L | `.github/workflows/` | A tag produces the APK artifact. **Read BUILD-1 first** |
| **H-1** | Build hygiene: install `ccache`, reconfigure the tests build with `-DENABLE_LTO=OFF` and compiler launchers, raise `org.gradle.jvmargs` to 3g. | Iteration time. Demoted from Tier 0: two waves shipped without it. | S | L | `CMakeCache.txt`, `src/android/gradle.properties:11` | H-C before/after |

### Tier 3 — parked, with the reason

Everything here is real. None of it is worth an agent-day until D-0 says something in §0.1 is
wrong on the Thor. The plan's original Tier 3 was almost entirely performance work, and the
measurements say the fork has performance headroom it is not using: ACNL at 4x uses 3.9 ms of a
16.7 ms frame on the GPU, holds 96-101 % speed at every scale, and janks zero frames on every
build ever measured, including the one from before this fork existed.

| ID | Item | Why it is parked |
|---|---|---|
| **G-7** | Cache `StaticPipelineInfo::Hash()` per pipeline; hash only non-padding fields. Estimated 1-3 % of frame time at high draw counts. | **Demoted.** 1-3 % of a frame that is already 40 % idle buys nothing anyone can perceive. Revisit only if D-0 or a heavier title shows the emulation thread as the bottleneck. |
| **G-9** | Dynarmic `page_table_pointer_mask_bits` / `absolute_offset_page_table`; a fastmem arena on desktop; drop the throwaway null-page-table JIT (−512 MiB VSZ, not RSS). | **Demoted.** High risk (JIT correctness) against a CPU budget nothing is straining. The VSZ saving is not resident memory and does not affect any kill. |
| **M-4** | `MemoryRef` 40 B → ≤16 B in `PageTable::refs`, −24..32 MiB dirty anon. | **Demoted.** 24-32 MiB against a 1.3 GB process is 2 %, for a change that touches every `GetPointer` path *and* the savestate layout. **Also now gated on S-1** — without a format fingerprint this change would silently load and misinterpret every existing state. |
| **M-7** | Texture-cache byte budget with LRU eviction of unreferenced surfaces. | **Kept but re-scoped.** Not needed for the default configuration (GL mtrack is flat at ~448 MB across every resolution). It is a prerequisite for texture packs, so it now belongs *with* M-8 rather than as a general memory item. |
| **T-5** | Scheduling: emu-thread priority, optional affinity, an ADPF hint session. | **Demoted, not deleted.** The premise was "60 fps but stuttery"; the device does not stutter. It is worth one experiment *after* D-0, and only if the Thor's thermals or fan behaviour turn out worse than the Fold5's — the one place where different hardware could plausibly change the answer. |
| **T-8** | Lid-cycle and dual-layer measurement kit. | **Folded into D-0**, which needs the per-layer Perfetto query and the lid soak anyway. |
| **R-4** | Release `surface_mutex` before `System::Load`; stop token for `recreate_surface_cv`; the recursive-mutex `adopt_lock` wait in the init callback. | **Kept in Tier 3.** Genuinely an ANR/deadlock class, genuinely high-risk threading work, and zero ANRs have been recorded in any soak. Promote the moment D-5 shows one. |

### Tombstones

- ~~**G-1 estimated at −1..−3 ms/frame**~~ — shipped; the frame-time estimate was wrong, the memory
  and thread savings were real. Kept in §1.2 with the correct outcome.
- ~~**M-2 as a delivered item**~~ — reclassified as an open bug (M-2b).
- ~~**"Paused CPU is the kill risk"**~~ — solved by B1; the CPU-kill tier no longer applies at
  0.33 %. What remains is the LMK, which is a memory and policy question (T-7, M-2b).
- ~~**Audio sharing / exclusive mode**~~ — dropped in revision 1 and still dropped: already
  LOW_LATENCY, no measured problem, and paused CPU is now 0.33 %.
- ~~**`use_vsync` runtime handling on Android**~~ — dropped; present mode is already IMMEDIATE/MAILBOX
  and T-4 solved the pacing question a different way.
- ~~**DLP busy-wait**~~ — dropped; only reachable in Download Play.
- ~~**Input-layer latency work**~~ — dropped in revision 1 on the grounds that the path is fully
  event-driven; nothing since has contradicted it, and Marty reported rc1 play as feeling fine.
- ~~**Relax savestate build-locking**~~ — **not needed: it is already relaxed** by our own autosave
  work, and confirmed in the field. The remaining work points the other way (S-1). See §4.3.
- ~~**Warm the shader cache in the background**~~ — investigated and rejected. `ShaderDiskCache::Init`
  already loads and builds *everything on disk* eagerly at boot; what is left to compile is
  configurations the game has not yet asked for, which cannot be known ahead of time. The adjacent
  idea that *is* live is overlapping that eager load with the first frames rather than warming it —
  see **L-3** below, which is a boot item, not a warming item.
- ~~**Second-screen rendering while the panel is off**~~ — investigated; rc2 already handles the
  `STATE_OFF` case, so there is no code item here. What survives is the *verification* that the
  Thor's panels actually report `STATE_OFF` in both real off-paths — **T-9**.
- ~~**Fan profile while paused**~~ — investigated and rejected as a code item. The AYN fan is
  firmware-controlled with no app API; we can neither read nor set it. It survives as an
  observation to record during D-0's battery soak, because a fan that keeps spinning at
  foreground-service state is evidence for T-7.

### One new item that came out of the reading

| ID | Item | Payoff | Effort | Risk | Files | Verify |
|---|---|---|---|---|---|---|
| **L-3** | **Overlap the disk shader/pipeline cache load with the first frames.** After L-1, the ~740 ms cache load is serialized before the first frame on the emulation thread. Async shader compilation is now on by default, so the game can render while the cache streams in on a worker — the pipelines it needs before the cache lands simply compile as they would on a cold cache. Requires establishing that `ShaderDiskCache` tolerates concurrent `BindPipeline`, which it may well not (`disk_caches` is rebuilt wholesale in `LoadDiskCache`). | D-3 boot-with-resume: the remaining ~740 ms is the largest single block left in a 2805 ms boot. | M | M-H — a data race here corrupts the pipeline cache | `vk_pipeline_cache.cpp:338-347`, `native.cpp:308,668`, `vk_shader_disk_cache.cpp:59-79` | D-3 both scenarios; D-4 first 60 s p99 must not get worse; 10 boots with no cache corruption |

---

## 4. Strategic conclusions

### 4.1 The autosave inverts the strategy, and the foreground service should follow it

The first revision of this plan assumed the goal was to **prevent the kill**. That was right when
the kill cost the session. It is not right any more.

**What changed.** The autosave works: a state is written whenever emulation is backgrounded, it is
offered or loaded on the next boot, and the resume costs **2805 ms** on the Fold5 at 3x (3333 ms at
4x). Marty has resumed from one in the field. So the price of a kill is now about three seconds and
a loading screen, not a lost evening.

**What the foreground service costs.** It was introduced to take the process out of the cached
tiers. It does that, and four other things:

1. It is the reason **M-2 has never fired** — ~448 MB of GL mtrack that we wrote code to release
   stays resident for the whole time the game is backgrounded (§1.3).
2. It exempts the process from the **cached-app freezer**, so the 0.33 % of paused CPU keeps being
   spent for hours instead of going to zero the moment the process is frozen.
3. It keeps ~1.3 GB resident on a device where, on the 8 GB SKU, the emulator is the largest
   process — so it makes the app both un-killable *and* the reason something else gets killed.
4. It has its own failure modes (`START_STICKY` with no activity; an uncaught `startForeground`
   throw; a single refused start that is never retried) — **FGS-1**.

Meanwhile the thing it was originally justified by — the `EXCESSIVE CPU USAGE` kill at 9.27 %
against a 2 % limit, **a Fold5 record; the Thor has never produced one** — was solved by B1
instead, and solved better: the process is now quiet whether or not it is exempt from the check.

**Conclusion.** The FGS should stop being a permanent property of a running game and become a
property of the *foreground*. The policy to implement and measure is **T-7**: on Home with the
screen on, keep it (a fast return is worth 448 MB for a minute); on screen-off or lid close, write
the state, flush the cache, run the trim, and then **let go** — drop to cached, take the trim
callbacks, get frozen, and accept the kill if it comes.

**What could make this wrong**, and must be measured before it is adopted as the default:

- **Battery.** No battery figure exists on any device (D-0 owes one). If it turns out that a
  frozen process and an FGS-held process drain indistinguishably over eight hours, then the FGS
  costs nothing except the trim, and the cheaper answer is M-2b alone with the FGS kept. If the
  frozen process is materially better, T-7 is the answer. **This is the single measurement that
  decides the item.** As of writing, `docs/fork/baselines/` contains no battery file.
- **Resume quality.** Three seconds is cheap only if the resume lands correctly. rc1 shipped with a
  restored-open drawer that made a resumed session unusable until rc2 fixed it, and neither fix has
  been verified on hardware. A kill we deliberately allow is a kill whose resume path had better be
  right (R-9, R-10, the rc2 drawer fixes, R-11).
- **The lid may not be a screen-off.** T-7 keys off display state; T-9 says we do not know what the
  Thor's panels report on a lid close.

Rank: **T-7 becomes a Tier 0 item.** It was a Tier T afterthought written before any of this was
known.

### 4.2 Every number is from a Fold5, and here is what is most likely to be wrong on the Thor

Still true of every *number*. Since 2026-09-09 the Thor has given up three things that are not
numbers — its identifiers, its Android level and its Vulkan driver (512.676.53 against the Fold5's
512.676.1) — plus its `exit-info` history. None of that changes the ranking below; the driver
difference sharpens item 1 and adds a caveat to anything driver-gated, and the exit history is what
§4.2a is about.

Ranked by how much a Thor measurement could change the plan:

1. **"4x is free" and "frame pacing is clean."** Both were measured on **one** panel — the Fold5's
   folded 904x2316 cover screen — with **no real secondary display**, and the graphics-preset pass
   explicitly turned the Presentation off so the numbers described the graphics settings alone. On
   the Thor, *both* panels present every frame: two swapchains, two render passes, two blits, two
   submits, two presents, and the bottom one is 1080x1240 rather than nothing. G-1 removed a *third*
   present; it did not remove the second. **The single most at-risk conclusion in the document.**
2. **Thermals and sustained clocks.** Every measurement window was 20-45 seconds, on a phone, on
   charge, at 30.8 °C. Nobody has run this emulator for twenty minutes anywhere. The Thor has an
   active fan and a different envelope in both directions.
3. **Memory headroom.** The Fold5 is a 12 GB phone. We do not know Marty's SKU. On 8 GB, 1.3 GB
   resident and no trim is a different story, and it is the story T-7 and M-2b are written against.
4. **Paused CPU and the kill.** 0.33 % was measured behind HOME **with the screen on**. No
   measurement pass on any device has ever sent `KEYCODE_SLEEP`. Deep suspend, the AYN lid switch
   and Android 13 (rather than the Fold5's 16) are all untested. The Thor's `exit-info` has now
   been read and contains **no** excessive-CPU or `LOW_MEMORY` record for any package, so the
   one `EXCESSIVE CPU USAGE` record we own remains a Fold5 record and the kill this item is
   about has never been observed on the target.
5. **The 60 Hz mode.** T-4 picks a mode by refresh rate with a tolerance and by matching resolution.
   Whether the Thor's top panel offers a mode our matcher accepts, and whether the 60 Hz bottom
   panel has anything to pick at all, is unknown.
6. **Everything about the lid.** A Fold5 fold is not a Thor lid. All of T-2 is unverified.
7. **The dual-screen path itself.** The Fold5 has no second display, so `SecondaryDisplay` has
   only ever been exercised against no display and against a hidden virtual one.

D-0 is written to settle 1-6 in one session.

### 4.2a What the Thor's `exit-info` says, and what it does to §4.1

`baselines/2026-09-09-thor-device-snapshot.md` read Android's own record of why each emulator on
the device last died. Three things in it matter to this plan.

- **Azahar has exactly one death on record**: 2026-09-08 21:11:37, `reason=5 APP CRASH (NATIVE)`,
  status 11 — the rc1 session, matching the mid-game texture-filter change Marty reported. **The
  stack was recovered** and is an exact match for the research note's signature #1 — `VulkanWorker`,
  `qglinternal::vkCmdEndRenderPass`, `Vulkan::Scheduler::WorkerThread`, fault address `0xb4` — with
  `texture_filter = xBRZ` at `resolution_factor = 4` persisted at crash time. **So the crash is
  confirmed on the Thor's own driver, 512.676.53; the newer driver does not fix it.** And
  **nothing at all since rc2 was installed**. That is the first evidence of any kind that rc2 is
  stable in the field. It is weak evidence — part of one day, no controlled conditions, and an
  absence rather than a measurement — but it is on the right device.
- **There is no `LOW_MEMORY` record and no `EXCESSIVE RESOURCE USAGE` record for any package on the
  Thor**, at any date. §4.1's premise — that the FGS was justified by an excessive-CPU kill — was
  always a Fold5 premise, and it stays one. Nothing here contradicts §4.1's conclusion (the burn
  was real, B1 fixed it, and the FGS's costs are unchanged), but the argument may not be quoted as
  if the Thor had ever been killed for CPU. It has not been.
- **melonDS died eight times in the foreground to native crashes**, across three different fatal
  signals, not to reclaim. That is not an Azahar fact, but it is the same class of mistake this
  plan is prone to: a mechanism demonstrated on the lab phone was assumed to be the mechanism on
  the target. The follow-up diagnosis then ruled out the replacement theory (#1624) as well. See
  `upstream-candidates.md` and `baselines/2026-09-09-thor-diagnostics.md`.

Consequence for T-7: the item is unchanged in shape — it is still decided by the battery
measurement — but its risk section should no longer imply the Thor is a device that kills for
CPU. It is a device that, on the record available, has never done so.

Three device facts from the same session that the plan had listed as unknown:

- **`MemTotal` is 11 535 400 kB (~11.0 GiB)** — the 12 GB SKU, not 8 GB, with ~4.2 GiB available
  and ~2 GB of swap already in use. §0b.1's "we do not know which SKU" is answered, and §4.2's
  item 3 softens: 1.3 GB resident on a 12 GB device is not the story it would be on 8 GB. This
  weakens, but does not remove, the LMK premise in §4.1 — it makes T-7 less urgent, not wrong.
- **The cached-app freezer is enabled** (`use_freezer=true`, `freeze_debounce_timeout=600000`), so
  T-7's "let it get frozen" half has a freezer to be frozen by.
- **No thermal history is available.** `Thermal Status: 0`, but `HAL Ready: false` and the cached
  temperature list is empty, so the absence of throttling records is not evidence of absence.
  §4.2's item 2 stands entirely.

### 4.3 Savestate build-locking is already relaxed — and is now slightly too lax

**The question was whether the build check could be relaxed. It already has been, by our own work,
and Marty has exercised it in the field: an rc1 autosave loaded in rc2.**

The mechanism, re-read at `553e5deb2`. `ValidateSaveState` (`savestate.cpp:63-91`) has three
outcomes, not two:

- revision matches → `OK`;
- revision differs but `Common::g_build_version` matches → `RevisionMismatch`, **which still loads**;
- build version differs → `BuildMismatch`, which is the only status `System::LoadState`
  (`:343-348`) and the autosave resume path (`:196`) refuse.

Before the autosave work, `MakeHeader` left `build_version` empty in file headers, so every
cross-revision state fell into `BuildMismatch` and was refused. `MakeHeader` now writes it
(`:230-232`), which is what makes cross-build loading work. `LoadStateBuffer` still hard-rejects on
a revision difference (`:386-392`), but that path is libretro-only and does not matter here.

**One correction to the correction.** `qa-notes.md` says states "would only break on a rebase onto a
new upstream release, where `g_build_version` itself changes". That is not what the build produces.
`BUILD_VERSION` is `"0"` unless a git tag is available (`GenerateBuildInfo.cmake:56-71`: only
`CITRA_USE_TAG_AS_VERSION`, a GitHub Actions tag build, or a `GIT-TAG` file from
`.ci/source.sh` sets it). Confirmed in a real APK build: `#define BUILD_VERSION "0"`. So:

- every locally-built fork APK writes `"0"`, and states are interchangeable between **all** of
  them — including across a rebase onto a new upstream, which is precisely the case where the
  format is most likely to have changed underneath us;
- a **CI tag build** (plan item C-1) would set `BUILD_VERSION` to the tag and silently reintroduce
  the break between every release candidate. This is a trap C-1 must address (BUILD-1).

**Conclusion.** No relaxation item is needed; the opposite item is. Because the check now passes on
any revision difference, a build that genuinely changes the serialized layout will load an old
state and deserialize it wrong — boost's binary archives only catch this where a class carries an
explicit `BOOST_CLASS_VERSION`, and just **12** classes in the tree do. That is **S-1**, and it is
why M-4 and M-3/L-4 are gated on it.

**And both release notes are wrong.** rc1 and rc2 each tell the user savestates from another build
are rejected and to save in-game before installing. Fix it in the next release's notes and reduce
the warning to a footnote about upstream version bumps and CI-tagged builds.

### 4.4 Per-game settings on Android: not worth an item

**Verdict: no.** Do not build it.

The facts. Per-game configuration is Qt-only (`src/citra_qt/configuration/configure_per_game.*`);
`Settings.kt:91` carries a literal `// TODO: Implement per game settings`; upstream #38 is the open
request. So the presets we shipped genuinely are the only lever.

Why that is fine:

- **Azahar already applies per-title fixes itself.** `common/hacks/hack_list.cpp` is a title-ID
  table applied at boot on Android (`native.cpp:307` → `GPU::ApplyPerProgramSettings`), covering
  Ocarina of Time 3D, Mario & Luigi: Paper Jam, Super Mario 3D Land and Luigi's Mansion: Dark Moon
  in this library. Nothing to do.
- **A survey of Marty's library found essentially no per-game requirements.** The one real finding
  — Pokémon X/Y needing accurate multiplication on Adreno — was solved globally by turning the
  setting on in the presets, which is the correct fix because the cost is a small shader one.
- **The two remaining cases are single settings on single games** (Fire Emblem Fates' 2D sprites at
  above 1x, upstream #1989; Monster Hunter 4 Ultimate's crash at 6x, #1852, which our 4x cap already
  avoids), both handled by changing one control by hand for the duration.
- The cost is a whole settings-scoping layer — per-title config files, a UI, precedence rules,
  interaction with the preset picker and with `ThorDefaults` — for a library with two known
  exceptions.

**What is worth doing instead**, if this ever itches again: extend the existing `hack_list.cpp`
table with the two known titles. That is a data change on a mechanism that already exists on
Android, and it is the shape upstream already uses. It is not currently ranked because neither game
is being played.

---

## 5. Execution and open questions

### 5.1 Resource limits (16 cores, 62 GB)

At most **2 concurrent C++ builds at `-j6`**, tests-only config
(`-DENABLE_QT=OFF -DENABLE_SDL2=OFF -DENABLE_WEB_SERVICE=OFF -DENABLE_OPENAL=OFF
-DENABLE_SCRIPTING=OFF -DENABLE_LTO=OFF`); the boost.serialization hub TUs peak at ~2 GB each.
**Android APK builds are serialized and never overlap a C++ host build.** **Device access is
serialized** behind `device.lock`, with the identity check mandatory at acquire; only the lock
holder may send `KEYCODE_SLEEP`/`WAKEUP`, and it must wake the device before releasing. Worktrees:
`git worktree add`, then `git submodule update --init --recursive` for anything that will commit.

### 5.2 How an item proves itself

Unchanged, and it is the rule that has held up best:

1. Before touching code, run the relevant metric on `thor/main` and paste the numbers into the
   commit body.
2. After, run the same command in the same conditions and report the median of three where the
   device budget allows; say plainly when it does not.
3. Full `tests` binary passes; clang-format on changed files.
4. Hand back: branch, before/after table, exact commands, caveats.
5. **Any item whose metric does not move is reverted, not argued** — and any item that ships
   without its metric being taken is marked SHIPPED-UNVERIFIED in the release notes, not claimed
   as a win. rc2 got this right for M-2; §1 exists so it stays right.

### 5.3 Recommended order

1. **D-0** — everything below is guessing until this exists.
2. **FGS-1**, **M-2b** — small, safe, and they are the two halves T-7 needs underneath it.
3. **T-7**, with the battery numbers from D-0 in hand.
4. **R-11** and **T-9** — both are "measure first, then maybe fix", and both can share D-0's
   device session.
5. **X-1** — the only regression the fork has introduced.
6. Tier 1 remainder, then Tier 2.

### 5.4 Open questions

- **What does eight hours paused cost in battery, with the FGS and without?** Decides T-7. No
  measurement exists on any device.
- **Does the Thor's bottom panel report `STATE_OFF` on a lid close, and on AYN's bottom-screen-off
  shortcut?** Decides whether rc2's dual-screen design does what it was written to do (T-9).
- **Which RAM SKU is Marty's Thor?** Decides how much of §4.1's LMK argument applies.
- **How do Argosy and Cocoon launch Azahar?** Decides whether R-5 is doing anything (R-11).
- **What made the texture-filter crash go from 12/20 to 19/20?** (X-1.) Until this is answered we
  do not know what else the same change made more likely.
- **Does the Thor's mode list contain a 60 Hz mode our matcher accepts, on both panels?** (T-4 was
  measured on a Fold5 panel.)
- **Is the emulation thread still idle when both panels present every frame?** The premise of every
  Tier 3 demotion in §3 (D-0).

---

## 6. Non-goals and legal boundaries

- **No changes to encryption, key handling, AES/`HW::AES`, NCCH/CIA decryption, seed DB, system-file
  installation, or anything under `src/core/hw/aes/` and `src/core/file_sys/*ncch*`.** Boot-time
  work in this plan touches memory allocation and cache loading only.
- **No ROM, firmware, key, or save file is ever committed, uploaded to CI, or embedded in a test.**
  Host benchmarks take the ROM path from an environment variable; CI benchmarks use only
  public-domain homebrew (`3dsx`), and only after its license is confirmed.
- No netplay/room/Artic changes; no changes to upstream update-checker/telemetry beyond leaving them
  off; no Play-flavor work.
- Nothing is pushed to `upstream`; `origin` only when Marty asks. Azahar's `AI-POLICY.md` means
  none of this goes upstream as AI-written code, so no effort is spent on upstream compatibility.
  Upstream *issues* are a separate track with its own gates — see `docs/fork/upstream-issues/` and
  `docs/fork/upstream-candidates.md`; nothing there is filed without Marty reproducing it himself.
- Not in scope: new features unrelated to performance, memory, lifecycle or QoL (e.g. UI redesign,
  new applets, scripting).
