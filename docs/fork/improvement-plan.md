# Azahar fork (thor/main) — improvement plan

Planner output, 2026-09-08. Tree audited: `thor/main` @ `2b870bc70` in `/home/martyfuhry/Development/azahar`. All `file:line` references were read against that tree by five read-only sweep agents; the ones marked (verified) were re-read by the planner. Evidence appendix: `scratchpad/research/evidence-index.md`; raw device captures: `scratchpad/baseline/`.

Targets: AYN Thor (Android 13, SD 8 Gen 2 / Adreno 740, 8-12 GB, dual screen) first; desktop Linux second. The fork never goes upstream, so maintainability-vs-benefit is not a constraint; legal exposure is (see §6).

---

## 0. What the evidence says, in one page

**The kill that matters is a CPU kill, not (only) a memory kill.** `dumpsys activity exit-info` on the Fold5 (same SoC as the Thor) recorded on 2026-09-02:

```
reason=9 EXCESSIVE RESOURCE USAGE  subreason=7 EXCESSIVE CPU USAGE
description="excessive cpu 27810 during 300120 dur=1129762 limit=2"  state=empty  rss=1.1GB
```
= 27.8 s of CPU in a 5-minute window while cached/empty = **9.27 % against a 2 % limit** (`baseline/04-memory-and-kills.txt`). Android's check is over the whole process (all threads). The coordinator's own measurement on the old build: ~2 % while paused, from an `AudioTrack` thread plus two `NativeEmulation` threads.

**What burns CPU while paused (code, not guesswork):**
1. The audio output stream is never stopped. `CubebSink` starts its stream in the ctor (`src/audio_core/cubeb_sink.cpp:89-92`) and stops it only in the dtor (`:96-105`); neither `Sink` (`src/audio_core/sink.h:16-49`) nor `DspInterface` (`src/audio_core/dsp_interface.h:29-127`) has any pause/stop API. On pause, `native.cpp:517-520` merely sets `Settings::values.volume = 0`; every AAudio callback still runs `TimeStretcher::Process` (two heap `std::vector<float>` allocations + two conversion loops per callback, `src/audio_core/time_stretch.cpp:70-90`), the last-sample fill and the volume multiply (`dsp_interface.cpp:83-118`) 94-188 times per second. This is mitigation M11 from the root-cause doc, still unimplemented. **This is the `AudioTrack` thread.**
2. cubeb's AAudio backend spawns two helper threads from whichever thread calls `cubeb_init` — the emu thread (`externals/cubeb/src/cubeb_aaudio.cpp:1993,1999`, reached from `cubeb_sink.cpp:29` ← `dsp_interface.cpp:22` ← `core.cpp:596` ← `native.cpp:456`). Linux threads inherit the creator's `comm`, so they show up as **`NativeEmulation`**. `state_thread` polls at 5 ms whenever any stream is in a transitional state (`cubeb_aaudio.cpp:670`) (verified). The third unnamed inheritor is `MasterSemaphoreFence::WaitThread` (`src/video_core/renderer_vulkan/vk_master_semaphore.cpp:112`) — always present on the Thor because timeline semaphores are blacklisted for `is_qualcomm || is_turnip` (`vk_instance.cpp:456-458`, verified) — but it is condvar-idle while paused (`vk_master_semaphore.cpp:167`, verified). **The two hot `NativeEmulation` threads are almost certainly cubeb's.**
3. The real emu thread is properly parked on `running_cv` (`native.cpp:526-529`), the Vulkan present/worker threads are condvar-idle, the Choreographer callback is removed in `onPause` (`EmulationFragment.kt:565`), `PlayTimeManager` is stopped. One Kotlin leak: the perf-overlay `Handler` runnable re-posts itself forever and is never cancelled in `onPause/onStop/onDestroy` (`EmulationFragment.kt:1584`, `:558-597`); default off.

**How the CPU rule works (AOSP, confirmed):** `ActivityManagerConstants` — `POWER_CHECK_INTERVAL` 5 min; thresholds 25/25/10/**2 %** by time since the process became unimportant (>15 min → 2 %); **only processes at `procState >= PROCESS_STATE_HOME` (cached/background) are checked**; TOP and FOREGROUND_SERVICE processes are skipped, and FGS processes are also exempt from the cached-apps freezer (`research/external-research.md`). Our record (`dur=1129762` = 18.8 min in background, `limit=2`) matches tier 4 exactly.

**What thor/main's foreground service changes.** On the old build a cached instance was observed at `oomAdj 900, run cpu over +2m53s used 0 (0%)`, `earliestFreezableTimeMs=+4m59s`, 506 MB of its anon pushed to ZRAM — cached processes eventually go quiet, get frozen, and die of LMK instead. The FGS (`EmulationActivity.kt:181`, stopped only in `onDestroy` `:291`) holds the process at FGS importance: **exempt from the 2 % kill and from the freezer — but therefore the audio-thread CPU burn now runs for as long as the game is backgrounded** (the #519 battery drain, made permanent), and any path where the FGS is not running (notification permission flow, `stopWithTask`, OEM battery policy demoting it) drops straight back into the 2 % regime with the same ~2 % load. B1-B3 are therefore what make the FGS a net win rather than a battery sink; they must be verified with D-1 in the FGS state *and* with the service disabled.

**Memory:** `dumpsys meminfo` on a backgrounded instance: TOTAL PSS 1.48 GiB; **GL mtrack 747 MiB + EGL mtrack 123 MiB = 871 MiB of GPU memory** that never appears in `VmRSS` and cannot be swapped; Native Heap 551 MiB. Boot log confirms the 512 MiB Vulkan upload ring (`Upload buffer created, 524288 KiB`). The ring is `constexpr` (`vk_texture_runtime.cpp:161`), host-visible, persistently mapped, never shrinks (`vk_stream_buffer.cpp:232-236`), and with custom textures off the largest single mapping it ever serves is a 1024×1024 RGBA8/D24S8 guest texture ≈ 4-5 MiB (`rasterizer_cache.h:1046-1047`, `vk_texture_runtime.cpp:1160-1168`); only custom textures need hundreds of MiB (`vk_texture_runtime.cpp:971`). A request larger than the ring is a hard `ASSERT` (`vk_stream_buffer.cpp:119`), and the only cost of a smaller ring is an occasional `scheduler.Wait` on wrap (`:271-280`). FCRAM is 256 MiB value-initialised regardless of `is_new_3ds` (`memory.cpp:105`); the page table is ~49 MiB/process value-initialised twice (`memory.h:98-109`, `vm_manager.cpp:56`, `memory.cpp:47-51`); the texture cache has a deferred-deletion GC but no budget/LRU/eviction (`rasterizer_cache.h:130-146`, `rasterizer_cache_base.h:219-226`); `UnregisterAll()` (`rasterizer_cache.h:1443-1453`) exists and is already exercised at runtime by `TickFrame`. M4 (savestate triple copy) is fixed on thor/main (`savestate.cpp:242-272`, peak transient now ~256 KiB).

**Frame time, Android/Vulkan (the biggest surprises):**
- **A second present window is created unconditionally on Android** (`native.cpp:390-394`, no conditional) and rendered + presented **every frame** (`renderer_vulkan.cpp:1138-1144`, `:1165-1176`), targeting a **hidden 1920×1080 `VirtualDisplay`** that `SecondaryDisplay.kt:34-41` creates even when the secondary display is disabled or absent (`:88-95`). Per frame that is one extra `waitForFences` on the emu thread (`vk_present_window.cpp:261-279`), one extra fullscreen renderpass, one extra blit, one extra `vkQueueSubmit` + `vkQueuePresentKHR`, two extra `submit_mutex` acquisitions (`vk_present_window.cpp:503` vs `vk_scheduler.cpp:187`), and a second set of 1080p swapchain images in VRAM. The `enable_secondary_display` setting only changes *which* display the Presentation targets.
- Every **skipped duplicate frame costs a blocking `scheduler.Finish()`** (`renderer_vulkan.cpp:1177-1179`; `use_skip_duplicate_frames` default true). For a 30 fps title that is a full GPU drain on the emu thread every other vblank.
- The driver `VkPipelineCache` is written **only in `~PipelineCache`** and on title switch (`vk_pipeline_cache.cpp:148-151`, `:182`). Any process kill throws away the session's pipeline compilation. Measured: the pipeline+shader disk cache load is ~806 ms of the 1992 ms intent-to-first-frame boot (`baseline/02-boot-timeline.txt`).
- **Autosave-resume boot pays for the shader cache and then discards it**: `LoadDefaultDiskResources` runs at `native.cpp:472-474`, then `OfferAutoSaveOnBoot` sends `Signal::Load` (`native.cpp:288`), which does a full `Shutdown`/`Init` (`core.cpp:925-928`) rebuilding the renderer with an empty cache; nothing reloads it (`GPU::RecreateRenderer`'s only callers are libretro).
- On the stock Qualcomm driver: no timeline semaphores, **no extended dynamic state** (`vk_instance.cpp:460-462`) so rasterization+depth-stencil fold into the pipeline key (`vk_graphics_pipeline.cpp:40-43`) → more pipelines, more stutter; extra mid-frame flushes every >20 draws (`vk_render_manager.cpp:134`, `vk_instance.h:275-278`). Turnip lifts all but the timeline-semaphore blacklist.
- `async_shader_compilation` defaults **false** on Android (`BooleanSetting.kt:16`), so a cold pipeline blocks the Vulkan worker (`vk_pipeline_cache.cpp:481-486`). `StaticPipelineInfo` is fully hashed **twice per draw** (`vk_shader_disk_cache.cpp:240-241`).
- Dynarmic: no fastmem, `page_table_pointer_mask_bits=0`, `absolute_offset_page_table=false` — all library defaults (`arm_dynarmic.cpp:360-374`).

**Lifecycle bugs (root-cause doc §3/§4) status on thor/main:** M5 fixed (`d130b05dd`), M9 partially fixed (`26f5a2797` handles only the FGS notification intent; `EmulationActivity.kt:215-217` still stops the game on any other re-delivered intent). **M7, M8, M10, M13, M14, M15 and §4 bugs 2-12 are all still present** at the lines listed in §4 below. No commit on thor/main touches `vk_swapchain.cpp`, `vk_present_window.cpp`, `vk_platform.cpp` or `emu_window.cpp`.

**Harness:** there is no headless frontend. `citra_cli` is Z3DS compression only (`src/citra_cli/citra_cli.cpp:39-43`); `citra_meta` hard-requires Qt (`src/citra_meta/main.cpp:11-13`). But the software renderer needs no GL/VK context (`renderer_software.cpp:13-25`), `WindowSystemType::Headless` already exists (`emu_window.h:24`), the libretro core's Software path skips HW-context setup entirely (`citra_libretro.cpp:619-625`), Catch2 3.8.0 ships BENCHMARK with no config flag (`externals/catch2/src/catch2/benchmark/`), and `record_frame_times` is wired on Android (`jni/config.cpp:315-316`) but only flushes in `~PerfStats` (`perf_stats.cpp:36-51`). No test ROM exists in the repo; `.3dsx`/`.elf` load without keys (`src/core/loader/loader.cpp:48-67`).

---

## 1. Categories (kept / dropped)

| Cat | Kept? | Why |
|---|---|---|
| (a) Background CPU / battery / CPU-kill | **Keep, top priority** | Kill signature is measured; cause is located to the line; the FGS makes it worse until fixed. |
| (b) Resident memory | **Keep** | 871 MiB GPU-tracked + 551 MiB native heap measured; three of the four largest terms are single-constant fixes. |
| (c) Frame time / emulation speed | **Keep** | Unconditional second swapchain and the skip-frame GPU drain are structural, Android-only, and cheap. |
| (d) Boot / load / shader warmup | **Keep** | 806 ms of a 1992 ms boot is cache load; the pipeline cache is lost on every kill; autosave-resume discards the warm cache. |
| (e) Resume / lifecycle robustness | **Keep** | 6 of 8 mitigations and 11 of 12 bugs still present; several are one-liners. |
| (f) Audio latency / stutter | **Keep, narrowed** | Config is already reasonable (AAudio LOW_LATENCY, SHARED, 3 bursts). Keep only the per-callback allocation fix and the "stretcher always on" TODO; stutter is downstream of the emu-thread GPU stalls in (c). |
| (g) Build / CI ergonomics | **Keep** | ccache is not installed; tests build has LTO on; APK-on-tag is a copy of an existing job. |
| (h) Other | **Keep two items** | Dual-screen handling (folded into c/e); dynarmic page-table flags (c). Dropped: network/room (off on Android), custom-texture preload budget (latent, not on the Android boot path, one-line fix listed as low priority), DLP busy-wait (`dlp_base.cpp:110`, only reachable in Download Play). |

---

## 2. Metrics and exactly how to measure them

### 2.1 Host-side

**H-A. Catch2 micro-benchmarks (no ROM needed).** Catch2 3.8.0 in `externals/catch2` has `BENCHMARK` built in (v3 dropped `CATCH_CONFIG_ENABLE_BENCHMARKING`). Add `src/tests/bench/*.cpp` to `src/tests/CMakeLists.txt:36-44`, tag `[bench]`, exclude from the default `ctest` run with `~[bench]`. Run:
```
../azahar-build-tests/bin/Release/tests "[bench]" --benchmark-samples 20 --benchmark-no-analysis -r xml -o bench.xml
```
Diff the `<mean>` values between before/after XML (JSON reporter in 3.8 is still marked experimental; use XML). Candidate benchmarks: `System::SaveState`/`LoadState` on a synthetic populated `MemorySystem` (serialise ~266 MiB through `ZSTDOutputStreamBuf`; measures M-3/M-4 changes), `StaticPipelineInfo::Hash` + `OptimizedHash` (G-7), `PageTable` construction/`Clear` (M-4), `MemorySystem` construction (L-4), `TimeStretcher::Process` (B2).

**H-B. Headless full-boot harness (`citra_bench`).** New executable target `src/citra_bench/` gated on `ENABLE_BENCH` (builds without Qt; add next to `src/CMakeLists.txt:205-208`). It implements `EmuWindow_Null` (no-op `PollEvents`, stub `MakeCurrent/DoneCurrent`, `WindowSystemType::Headless`), forces `graphics_api = Software`, `frame_limit = 0`, `use_disk_shader_cache = false`, `audio output_type = Null`, loads the ROM given on the command line, runs `System::RunLoop()` until N system frames (count via `PerfStats::EndSystemFrame`, or via `GetAndResetPerfStats().system_fps`), then prints: wall time, frames, CPU time (`getrusage`), peak RSS (`ru_maxrss`), current `VmRSS`/`RssAnon` from `/proc/self/status`, and `PerfStats::Results` fields. Deterministic input via `-p movie.ctm` (`Movie` already supports playback with a completion callback, `src/core/movie.cpp:229-231`). ROM path comes from `AZAHAR_BENCH_ROM` (Marty's own dump; **never committed**, see §6). Fallback ROMs for CI/smoke: devkitPro `3ds-examples` `.3dsx` (Public Domain Mark; `graphics/gpu/textured_cube` for a continuous GPU load) — `loader/3dsx.cpp` has no key dependency, but keyless boot in Azahar is unverified: the harness agent runs one before relying on it.
   Metrics: `frames/s` (emulation speed with SW renderer = CPU/HLE cost), `cpu_ms/frame`, `peak_rss_mb`, `boot_ms` (time from `Load` to first `EndSystemFrame`), `savestate_ms` and `savestate_bytes` (add `--save-after N --load` mode).
   Cheapest alternative until H-B exists: build the libretro core (`-DENABLE_LIBRETRO=ON`, forces Qt/SDL off, `CMakeLists.txt:109-111`) and run `retroarch -L citra_libretro.so --max-frames=N --video-driver=null` with the core option set to Software (`citra_libretro.cpp:619-625`). Zero code, but no RSS/CPU counters — wrap with `/usr/bin/time -v`.

**H-C. Build-time metric** (for the swarm itself): `ninja -d stats` / wall time of `cmake --build ../azahar-build-tests --target tests` after touching `src/core/core.h`.

### 2.2 On-device (adb recipes; `PKG=org.azahar_emu.azahar`, `S="adb -s R3CW705DSTF"`)

Identity first, every time — the baseline mix-up today came from trusting an install claim:
```
$S shell dumpsys package $PKG | grep -E "versionName|versionCode|lastUpdateTime|codePath"
$S logcat -d -s CitraNative:I | grep "Azahar Version"      # app prints branch + hash at boot
```
Note: the coordinator reports the new APK as `45c7d2cdd-vanilla`; `45c7d2cdd` is the tip of `fix/clock-resync-on-resume`, not `thor/main` (`2b870bc70`). The version banner prints the branch name — check it before attributing any number to thor/main.

**D-1. Paused CPU % (the kill metric).** Total and per-thread, 60 s window, three states (foreground running / HOME with screen on / screen off):
```
PID=$($S shell pidof $PKG)
snap() { $S shell "for t in /proc/$PID/task/*; do printf '%s\t' \$(basename \$t); tr -d '\n' < \$t/comm; printf '\t'; sed 's/.*) //' \$t/stat | awk '{print \$12+\$13}'; done"; }
snap > t0.txt; $S shell sleep 60; snap > t1.txt
# join on tid: CPU% = Δ(utime+stime) / (60 s * CLK_TCK 100) * 100 ; fields are counted after the ')' so names with spaces are safe
join -t $'\t' <(sort t0.txt) <(sort t1.txt) | awk -F'\t' '{d=$4-$3; printf "%6.2f%%  %s\n", d/60, $2}' | sort -rn | head -15
$S shell dumpsys activity processes | grep -B2 -A10 $PKG   # oomAdj, procState, isFrozen, "run cpu over"
```
Target: **< 0.5 % total while paused** (limit is 2 %; the 2 % tier applies after >15 min in background and only while the process is at cached/HOME importance — see §0). Also count threads named `NativeEmulation`: after B1 the cubeb threads should be gone or idle; after B4 they should be named `cubeb:state`/`cubeb:notify` so the inventory is unambiguous.

**D-2. Memory.** `dumpsys meminfo $PKG` → TOTAL PSS, GL mtrack, EGL mtrack, Native Heap; `cat /proc/$PID/status | grep -E "VmRSS|VmSwap|RssAnon|RssFile"`; `cat /proc/$PID/oom_score_adj`. Take at: 60 s after first frame (running), 30 s after HOME, 5 min after HOME. Targets: GL+EGL mtrack from 871 MiB to **< 400 MiB** at 1× (M-1 alone should remove up to ~480 MiB of the upload ring once it has been fully walked; the ring is lazily faulted so early-session numbers understate it — take the 5-min sample after some gameplay); PSS paused **< 800 MiB**.

**D-3. Boot time.** `logcat -c`; `date +%s%3N` on device; `am start …`; then from `logcat -b events` + `logcat -s CitraNative:I ActivityTaskManager:I`: `am_proc_start`, `Displayed … +NNNms`, `VK_DRIVER`, `LoadDriverPipelineDiskCache`, `Service.SRV RegisterClient`, first `BLASTBufferQueue onFrameAvailable` for the EmulationActivity surface. Baseline (old build, Fold5, scrcpy running): intent→first frame **1992 ms**, cache phase **806 ms**. Add a second scenario "boot with autosave resume" once L-1 lands (expect the current path to be boot + cache + load + recompile).

**D-4. Frame timing.** `dumpsys gfxinfo` is useless here (HWUI only). Use either
```
$S shell dumpsys SurfaceFlinger --timestats -enable; (play 60 s); $S shell dumpsys SurfaceFlinger --timestats -dump; ... -clear
```
(per-layer present-to-present histogram, jank counts) or a Perfetto trace with `android.surfaceflinger.frametimeline` + `linux.ftrace` (sched) and query `actual_frame_timeline_slice` in `trace_processor` for present intervals of the Azahar layer. In-app: enable `[Debugging] record_frame_times=true` in `config.ini` (Android has no UI toggle; wired at `jni/config.cpp:315-316`) → `<userdir>/log/<date>_<TITLEID>.csv`, one frametime per line, flushed only on clean shutdown; item H-D below makes it periodic. Report mean, p95, p99, max frame interval and `PerfStats` `time_swap` share.

**D-5. Kill history.** `dumpsys activity exit-info $PKG` before and after every soak; the description string carries the CPU numbers. Soak protocol: play 5 min → HOME → screen off → wait 25 min → wake → re-send the launch intent → check `exit-info` and whether the game resumed in place (no `Azahar starting…` / `Cleaning up process` in `CitraNative`).

**D-6. CPU profile (for G-items).** `simpleperf record -p $PID --call-graph fp -e cpu-clock -f 1000 --duration 30` needs `<profileable android:shell="true"/>` in the manifest (add it under the fork — no security downside for a personal build) or a `relWithDebInfo` build (`build.gradle.kts:127-141`, debuggable). Report top symbols under `NativeEmulation` and `VulkanWorker`.

**D-7. Azahar's own perf stats.** `Core::System::GetAndResetPerfStats()` (`core.cpp:517`) already crosses JNI as 9 doubles for the overlay. Item H-D adds a `LOG_INFO(Frontend, "perf: …")` line every N seconds gated by a setting so `logcat -s CitraNative` carries `game_fps`, `emulation_speed`, `time_gpu`, `time_swap` without the overlay. Microprofile (`ENABLE_MICROPROFILE`, `CMakeLists.txt:147`) is off and has no Android UI — not worth wiring.

### 2.3 Baseline table (what we have, what is pending)

| Metric | Old build 4f462f7 (Fold5, scrcpy active) | thor/main | Target |
|---|---|---|---|
| Paused CPU total | ~2 % (coordinator's measurement); cached instance 0 % after 13 min | **pending** (phone off adb) | < 0.5 % |
| Hot threads paused | AudioTrack + 2× NativeEmulation | pending | none > 0.1 % |
| Kill reason history | EXCESSIVE CPU 9.27 % (2026-09-02) | pending | no CPU/LMK kills in 3 soaks |
| PSS backgrounded | 1.48 GiB (GL 747 + EGL 123 + heap 551 MiB) | pending | < 800 MiB |
| VmRSS foreground | ~906 MB | pending | < 600 MB |
| Boot intent→first frame | 1992 ms (806 ms cache) | pending | < 1400 ms; autosave-resume boot < 3 s |
| Frame interval p95 | not collected | pending | game-dependent; report Δ |
| Threads | 53 | pending | -3 (cubeb ×2 idle/stopped, fence thread renamed) |

The old-build boot run is valid but confounded (scrcpy virtual display + encoder, battery 16 %, folded cover panel at 24 Hz idle, an external re-launch intent at 10:29:08). First task of the harness agent is to redo D-1..D-5 on thor/main with scrcpy off.

---

## 3. Ranked backlog

Effort: S < 1 h, M ≈ half day, L ≈ days (agent time incl. build+verify). Risk: L/M/H. "Par" = can run in its own worktree in parallel; "Needs H" = needs the harness item named. Verification names the metric from §2.

### Tier 0 — enablers (do first, in parallel)

| ID | Item | Payoff | Effort | Risk | Files | Verify |
|---|---|---|---|---|---|---|
| **H-1** | Host build hygiene: install `ccache`, reconfigure `../azahar-build-tests` with `-DCMAKE_C(XX)_COMPILER_LAUNCHER=ccache -DENABLE_LTO=OFF`, keep Qt/SDL2/web/OpenAL off, `ENABLE_SCRIPTING=OFF`; document `NDK_CCACHE` for gradle (`.ci/android.sh:3`). | Every other agent's iteration time; avoids the OOM-kills (LTO link is a multi-GB spike). | S | L | `CMakeCache.txt` of the tests build; `src/android/gradle.properties:11` (`-Xmx1536m` → 3g) | H-C before/after |
| **H-2** | `citra_bench` headless target (§2.1 H-B) + Catch2 `[bench]` skeleton (H-A). | Unblocks host verification of M-3/M-4/L-4/G-7/B2 and the savestate path. | M-L | M | new `src/citra_bench/`, `src/CMakeLists.txt:205-208`, `src/tests/CMakeLists.txt` | Runs ACNL (local ROM) for 600 frames and prints the metric block; reproducible ±3 % across 3 runs |
| **H-3** | Device measurement kit: `tools/thor/measure.sh` implementing D-1..D-5 (per-thread CPU deltas, meminfo extraction, boot timeline from logcat, exit-info diff) + `record_frame_times` flushed periodically and a `perf_log_interval` setting that `LOG_INFO`s `PerfStats::Results` (D-7, "H-D"). Also add `<profileable android:shell="true"/>` for simpleperf. | Every device-side claim in this plan. | M | L | new `tools/thor/`, `src/core/perf_stats.cpp:36-51` (periodic flush), `native.cpp` main loop (`:493-532`), `AndroidManifest.xml` | Re-take the §2.3 table on thor/main |
| **C-1** | GitHub Actions: `apk-on-tag.yml` = copy of the `android` job (`.github/workflows/build.yml:309-389`) with `on: push: tags`, vanilla only, no SBOM/attest, no secrets (falls back to debug signing, `build.gradle.kts:96-116`), keep `actions/cache` + ccache; optional `workflow_dispatch` input to build `assembleVanillaRelWithDebInfo`. Second job (optional): `bench.yml` running H-A on ubuntu-latest and uploading `bench.xml`. | Reproducible APKs for the Thor without tying up the workstation. | S | L | `.github/workflows/` | Tag a test commit; artifact `azahar-android-vanilla-<tag>.apk` appears |

### Tier 1 — highest payoff / effort

| ID | Item | Payoff (metric, magnitude) | Effort | Risk | Files / functions | Verify | Par |
|---|---|---|---|---|---|---|---|
| **B1** | **Stop the audio stream on pause.** Add `virtual void Pause(bool)` (or `Stop/Start`) to `Sink`, implement in `CubebSink` (`cubeb_stream_stop/start`), `OpenALSink`, `SDL2Sink`, `NullSink`; add `DspInterface::PauseOutput(bool)`; call it from `pauseEmulation`/`unPauseEmulation` (`native.cpp:1005-1011`, `:986-1002`) and from the paused branch (`:517-520`) instead of the volume hack; Qt: call from `OnPauseGame`/`OnStartGame`. Keep the volume=0 as belt-and-braces during the stop latency. | **D-1 paused CPU: removes the AudioTrack/AAudio callback thread entirely** — the largest term of the ~2 %; expected paused total → well under 0.5 % (estimate). Removes the load behind the 9.27 % kill in the no-FGS case and the permanent drain in the FGS case (upstream #519). | M | M (resume glitch if stream restart races the first callback; AAudio stream may be disconnected on route change while stopped — handle `CUBEB_STATE_ERROR` by reopening) | `src/audio_core/sink.h`, `cubeb_sink.cpp:89-105`, `openal_sink.cpp`, `sdl2_sink.cpp`, `null_sink.h`, `dsp_interface.h/.cpp`, `native.cpp`, `citra_qt.cpp:2644-2656` | D-1 before/after in states B and C; audio resumes cleanly 10× pause/unpause; thread inventory | Par |
| **B2** | Audio callback cheapness: early-out in `DspInterface::OutputCallback` when `Settings::Volume()==0` (memset instead of stretch+fill+multiply, `dsp_interface.cpp:71-120`); reuse scratch buffers in `TimeStretcher::Process` (`time_stretch.cpp:70-90`); implement the TODO at `dsp_interface.cpp:74-76` (bypass SoundTouch when speed ≥ 95 %). | D-1 (covers the window before B1's stop takes effect and any sink without stop); running-state CPU of the audio thread (estimate 1-2 % of a core); fewer allocations = fewer audio hitches (D-4). | S | L | `dsp_interface.cpp`, `time_stretch.cpp/.h` | H-A `TimeStretcher` bench; D-1 | Par |
| **B3** | Cancel the perf-overlay `Handler` in `onPause`/`onStop` and re-post in `onResume` (`EmulationFragment.kt:1584`, `:558-576`); make `pauseEmulation` also cover the boot window (M10): add a `pause_requested` atomic consumed when the loop starts, and call `pauseEmulation()` from `clearSurface()` when RUNNING (`EmulationFragment.kt:558-567`, `:1817-1820`, `native.cpp:465`). | D-1 (1 Hz JNI + binder wake-ups when overlay on); D-1 during boot (guest currently runs headless with audio if backgrounded during load). | S | L | `EmulationFragment.kt`, `native.cpp` | D-1 with overlay on; background during the loading screen → CPU 0 % | Par |
| **B4** | Name the unnamed threads: `SetCurrentThreadName` in `MasterSemaphoreFence::WaitThread`, DSP LLE thread, and set cubeb's two threads' names by wrapping `cubeb_init` in a helper thread named `cubeb:init` (children inherit it) or by patching `externals/cubeb` (fork, allowed). | Diagnostic only — makes D-1's thread table unambiguous and every future `top -H` readable. | S | L | `vk_master_semaphore.cpp:112`, `lle.cpp:334`, `cubeb_sink.cpp:29` | Thread inventory shows no unexpected `NativeEmulation` | Par |
| **M-1** | **Shrink the Vulkan upload ring**: `UPLOAD_BUFFER_SIZE` 512 MiB → 64 MiB when `custom_textures` is off (keep 512 MiB when on, or better: in `TextureRuntime::FindStaging`/`UploadCustomSurface`, fall back to a one-shot dedicated staging buffer when `size > stream_buffer_size` instead of asserting). Optionally scale by `ActivityManager.largeMemoryClass` on Android. | **D-2 GL/EGL mtrack −400..−480 MiB at steady state** (the ring is walked monotonically; PSS shows 747 MiB GL mtrack on a 1× session). Biggest single memory win; also boot: 512 MiB fewer host-visible bytes to map (D-3, small). | S-M | M (the `ASSERT` at `vk_stream_buffer.cpp:119`; wrap-wait frequency — measure D-4 to make sure a 64 MiB ring does not add stalls; try 32/64/128) | `vk_texture_runtime.cpp:161`, `:289-294`, `:299-307`, `:956-975`; `vk_stream_buffer.cpp:114-141`, `:186-191`, `:271-280` | D-2 5-min sample; D-4 p95 unchanged; ACNL + one texture-heavy title 10 min no assert | Par |
| **G-1** | **Do not create/present the secondary window unless a real secondary display is in use.** Kotlin: create the `Presentation` only when `enable_secondary_display` and a non-virtual display exists (`SecondaryDisplay.kt:34-41`, `:88-95`, `:120-122`); native: allow `secondary_window == nullptr` (`native.cpp:390-394`, `:402-409`) and make `secondarySurfaceChanged` create/destroy the `PresentWindow` on demand; renderer: `renderer_vulkan.cpp:1138-1144`, `:1165-1176` already branch on `secondary_window`. Must keep the Thor's real dual-screen path working and hot-plug safe. | **D-4: removes one `waitForFences` on the emu thread, one fullscreen renderpass, one blit, one submit, one present and two mutex acquisitions per frame**, plus a 1080p swapchain (D-2, ~15-30 MiB). Estimate: −1..−3 ms/frame on the emu thread at 1×, larger at higher res (needs measurement). | M | M (the secondary path is exercised by every Thor lid/display change; `secondarySurfaceDestroyed` bug #11 must be fixed at the same time; `game_frames_updated` is currently cleared by the secondary pass — `renderer_vulkan.cpp:257` — so the primary pass must take over that duty) | `SecondaryDisplay.kt`, `EmulationActivity.kt:130,153-154`, `native.cpp:390-409`, `:601-648`, `renderer_vulkan.cpp:132-135`, `:1138-1176`, `:257` | D-4 p50/p95 before/after on ACNL; D-2; dual-screen still renders on the Thor and on the Fold5's inner+cover trick; R-7 regression test | Par (but shares `native.cpp` surface code with R-3/R-4 — sequence after them or rebase) |
| **G-2** | **Remove the blocking `scheduler.Finish()` on skipped duplicate frames** (`renderer_vulkan.cpp:1177-1179`). Replace with `scheduler.Flush()` (non-blocking submit) or nothing; audit why it was added (git blame) and whether `PrepareRendertarget` needs a submit boundary. | D-4: for 30 fps titles, one full GPU drain every other vblank is removed; expect fewer p95 spikes and better `time_swap`. Estimate: measurable on ACNL (30 fps in many scenes). | S | M (regression risk: stale display without the drain — verify with the frame-skip setting on/off) | `renderer_vulkan.cpp:1177-1179`, `vk_scheduler.cpp:67-72` | D-4 with `use_skip_duplicate_frames` on; visual check | Par |
| **G-4** | **Persist the driver `VkPipelineCache` periodically and on pause**: call `SaveDriverPipelineDiskCache` every N minutes from the emu thread and on `pauseEmulation`/autosave; write to `.tmp` + rename. | D-3: after any kill, next boot keeps the session's pipelines (currently lost). Boot cache phase stays ~800 ms instead of a cold recompile storm. Also stutter in the first minutes after a kill (D-4). | S-M | L | `vk_pipeline_cache.cpp:148-151`, `:182`, `:269-296`; hook via a `System::Signal` or `RequestPipelineCacheFlush()` polled in `RunLoop` (the `RequestClockResync` pattern) | Kill with `am force-stop` after 5 min play, reboot: `LoadDriverPipelineDiskCache` size grew; D-3 | Par |
| **L-1** | **Autosave-resume boot must not throw away the shader cache.** After a successful `Signal::Load`, call `LoadDefaultDiskResources` again (or move the autosave load before the first `LoadDefaultDiskResources`, or make `System::serialize`'s `Init` reuse the existing GPU/renderer instead of destroying it). | D-3 "boot with autosave resume": removes a full recompile-from-empty phase; the visible stutter storm after resume goes away (D-4 first 60 s). | M | M (touches the savestate `Shutdown/Init` path; needs the savestate tests) | `native.cpp:226-297`, `:472-474`, `core.cpp:895-935`, `gpu.cpp:519-521` | D-3 autosave scenario; `perf` first-60 s p95 | Needs L-1's own tests; Par |
| **R-1** | M7: reset `image_count` in `Swapchain::Destroy()` (or loop over `image_acquired.size()`). | Lifecycle: prevents `vkDestroySemaphore` on dead handles after a surface-lost bail — a resume crash. | S | L | `vk_swapchain.cpp:261-272`, bails at `:82,:110,:134,:137,:179,:230` | D-5 soak with rotate/sleep spam; no CRASH_NATIVE | Par |
| **R-3** | M14 (both halves together): destroy the previous `VkSurfaceKHR` in `Swapchain::Create` (`vk_swapchain.cpp:34` / `:28`) and reset `last_render_surface` in `surfaceDestroyed` (`native.cpp:650-661`, `vk_present_window.cpp:356-359`). Also bug #11: notify the secondary `EmuWindow` in `secondarySurfaceDestroyed` (`native.cpp:638-648`). | Lifecycle: one leaked surface + pinned `ANativeWindow` per background/rotate cycle; removes the address-reuse black-screen hazard. | S | M (must land both halves in one commit) | as listed | D-5 soak: 20 HOME/return cycles, `dumpsys SurfaceFlinger` layer count stable | Par |

### Tier 2

| ID | Item | Payoff | Effort | Risk | Files | Verify | Par |
|---|---|---|---|---|---|---|---|
| **M-2** | `onTrimMemory(TRIM_MEMORY_UI_HIDDEN/BACKGROUND)` → JNI `NativeLibrary.trimMemory()` → `System::RequestTrim()` polled in the paused branch of `RunCitra` → `RasterizerCache::UnregisterAll()` + `mallopt(M_PURGE, 0)`/`malloc_trim`; also `SaveDriverPipelineDiskCache` (G-4) at the same point. Textures re-upload on resume (one hitch). | D-2 paused PSS: −100..−450 MiB GPU at 3-4×, −tens of MiB heap (M1 in the doc). Now the process is FGS-held it never gets ZRAM'd, so this matters more than before. | M | M (must run on the emu thread; `UnregisterAll` calls `FlushAll` which downloads dirty surfaces — do it *before* audio/emu park so it has a live scheduler) | `CitraApplication.kt` (implement `ComponentCallbacks2`), `NativeLibrary.kt`, `native.cpp:516-531`, `core.h/.cpp` (request flag), `rasterizer_cache.h:1443-1453` | D-2 at 5 min after HOME, before/after | Needs B1 sequencing (same `native.cpp` paused branch) |
| **L-4 / M-3** | FCRAM/page table without the memset: allocate FCRAM/VRAM/extra/DSP with `mmap(MAP_ANONYMOUS)` (or `calloc`) so untouched pages are not resident; size FCRAM to 128 MiB when `!is_new_3ds` (assertions at `memory.cpp:903,1271-1286` must follow); construct `PageTable` once (drop the double write `memory.cpp:47-51`). Savestate: `memory.cpp:350-361` already sizes by `is_new_3ds`. | D-2 VmRSS: −128 MiB in O3DS mode, and in N3DS mode whatever FCRAM the game never touches stays non-resident (ACNL is an O3DS title; estimate 50-150 MiB); D-3 boot: removes a ~300 MiB memset (tens of ms) — paid again on every savestate load (`core.cpp:927`). | S-M | M (FCRAM size assumptions; savestate compatibility — states from a different build already fail by design) | `memory.cpp:105-108`, `memory.h:160-182`, `vm_manager.cpp:56` | H-B `boot_ms`, `peak_rss_mb`; savestate tests; D-2 | Needs H-2; Par otherwise |
| **G-3** | Default `async_shader_compilation=true` on Android (`BooleanSetting.kt:16`) and expose it; keep `wait_built` for `num_vertices<=6` as upstream. | D-4 p99 during first minutes / new areas: cold pipelines no longer block the Vulkan worker (`vk_pipeline_cache.cpp:481-486`). Cost: momentary pop-in. | S | L | `BooleanSetting.kt`, `settings.h:549` | D-4 first-60 s p99 on a fresh cache | Par |
| **G-6** | Re-test the timeline-semaphore blacklist on **Turnip** (`vk_instance.cpp:456-458` blames "Qualcomm drivers" only) — enable for `is_turnip` behind a setting; if stable, drop the fence path and its extra thread on Turnip. Also consider a driver-version gate for the Qualcomm blacklists (EDS / custom border color) on the 512.676+ driver measured on the Fold5. | D-4 on Turnip: fewer thread hops per submit; on proprietary, if EDS works on the newer driver, far fewer pipelines (`vk_graphics_pipeline.cpp:40-43`). | S (code) + device testing | H (driver bugs are why the blacklist exists) | `vk_instance.cpp:444-477` | D-4 + 30 min soak on each driver; revert on any artifact | Par, device-serial |
| **R-5** | M9 rest: in `onNewIntent`, compare incoming title id with the running one; only stop/reload when different (`EmulationActivity.kt:202-227`). | Lifecycle: launcher re-intent (upstream #2329, Thor's real path) no longer destroys the session. | S | L | `EmulationActivity.kt` | D-5 step "re-send intent" → resumed in place | Par |
| **R-6** | M13: `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density"` on `EmulationActivity` + `onConfigurationChanged` → `applyOrientationSettings` (`AndroidManifest.xml:70-84`). Also stops `initMultiplayer()` re-running on the UI thread on every recreation (`EmulationActivity.kt:152`). | Lifecycle: no activity recreation on lid/display change → fewer surface races; boot (D-3) −a few ms UI-thread work. | S | M (rotation layout must still update) | as listed | Rotate/fold 20×; D-5 | Par |
| **R-9/R-10** | Bug #9 (stale `isEmulationReady` from the bundle, `EmulationActivity.kt:241-246,280`) and #10 (`doFrame` reads `window` while `TryShutdown` resets it, `native.cpp:663-672` vs `:196-199`). | Lifecycle correctness after a kill-and-restore; UAF on guest shutdown. | S | L | as listed | Kill-and-restore test; guest "power off" test | Par |
| **L-2** | Emit a `Prepare` progress stage before `System::Load` (`native.cpp:468`) so the loading UI shows Vulkan/Init progress; move `initMultiplayer()` off the UI thread. | Perceived boot (D-3 `Displayed`→first progress). | S | L | `native.cpp:346-476`, `EmulationActivity.kt:152` | Visual; D-3 | Par |
| **C-2** | `android:profileable` + `relWithDebInfoLite` symbols for simpleperf; wire `NDK_CCACHE` into `build.gradle.kts` so local APK builds use ccache. | Enables D-6; APK rebuild time. | S | L | `AndroidManifest.xml`, `build.gradle.kts` | simpleperf report has symbols | Par |

### Tier 3 — larger or riskier

| ID | Item | Payoff | Effort | Risk | Files | Verify |
|---|---|---|---|---|---|---|
| **R-4** | M15 + bug #5/#6: release `surface_mutex` before `System::Load` (hold it only around window construction), give `recreate_surface_cv.wait` a stop token (`vk_present_window.cpp:376-380`), fix the recursive-mutex `adopt_lock` wait in the init callback (`native.cpp:358-374`). | Lifecycle: removes the ANR-on-boot and the shutdown deadlock; both are `REASON_ANR` kills. | M | H (threading) | `native.cpp:346-459`, `:179-201`, `vk_present_window.cpp` | D-5 soak with sleep during boot 20×; no ANR |
| **R-2** | M8: handle `VK_ERROR_DEVICE_LOST` / unexpected acquire-present results by tearing down and recreating the renderer (or routing to `HandleCoreError` with an autosave first) instead of `UNREACHABLE` (`vk_swapchain.cpp:112-115,139-142,147-171`, `vk_platform.cpp:195,202`, `vk_master_semaphore.cpp:101,154,178`); fix the unbounded retry loop in `GetRenderFrame` (`vk_present_window.cpp:267-278`) which spins a core at 100 % on any other error. | Lifecycle: no `CRASH_NATIVE` after GPU suspend; no 100 %-CPU hang (which would itself trip the CPU kill). | M-L | M | as listed | Fault injection (`renderer_debug`), soak |
| **M-4** | `MemoryRef` 40 B → ≤16 B (region tag + offset) in `PageTable::refs` (`memory.h:99`, `common/memory_ref.h:135-140`). | D-2: −24..−32 MiB dirty anon per process. | L | H (touches every `GetPointer` path; savestate format) | `memory_ref.h`, `memory.cpp` | H-A PageTable bench, full tests, D-2 |
| **M-7** | Texture-cache byte budget with LRU eviction of unreferenced surfaces (`rasterizer_cache_base.h:219-226`, `rasterizer_cache.h:130-146`). | D-2 at 3-4× with texture filter (`rasterizer_cache.h:583` multiplies sampled textures by res²); bounded long-session growth. | L | H | rasterizer cache | D-2 60-min soak at 3× |
| **G-7** | Per-draw hashing: cache `info.Hash()` per pipeline object; hash only non-padding fields (`vk_shader_disk_cache.cpp:238-241`, `vk_graphics_pipeline.cpp:35-46`). | D-6 emu-thread CPU per draw (estimate 1-3 % of frame at high draw counts). | M | M | as listed | H-A hash bench; D-6 |
| **G-9** | Dynarmic: set `page_table_pointer_mask_bits` / `absolute_offset_page_table` where valid; consider a `fastmem_pointer` arena on Linux desktop only (needs a PROT_NONE 4 GiB reservation — `arm_dynarmic.cpp:360-374`). Drop the throwaway null-page-table JIT (M16, `arm_dynarmic.cpp:185`). | H-B frames/s on the software renderer (pure CPU metric); VSZ −512 MiB (M16). | M | H (JIT correctness) | `arm_dynarmic.cpp` | H-B before/after; full tests |
| **M-6** | Custom-texture preload budget from `largeMemoryClass` instead of total RAM (`custom_tex_manager.cpp:211-217`). | Latent OOM only if preload is ever enabled on Android. | S | L | as listed | Unit test of the budget function |

Dropped after evidence: audio sharing/perf mode changes (already LOW_LATENCY; exclusive mode `externals/cubeb/CMakeLists.txt:256` is a device-compat gamble with no measured problem); `use_vsync` runtime handling on Android (`vk_present_window.cpp:387-396` is `#ifndef ANDROID`) — real, but present mode is already IMMEDIATE/MAILBOX at boot and no complaint exists; DLP busy-wait.

---

## 4. Swarm execution plan

### 4.1 Resource limits (this machine: 16 cores, 62 GB; today ~47 GB was in use while a Gradle build ran — one JVM at 10.5 GB RSS plus ~5 GB of helpers)

- **At most 2 C++ (ninja) builds concurrently, each `-j6`**, tests-only config (`-DENABLE_QT=OFF -DENABLE_SDL2=OFF -DENABLE_WEB_SERVICE=OFF -DENABLE_OPENAL=OFF -DENABLE_SCRIPTING=OFF -DENABLE_LTO=OFF`, ccache on). Heavy TUs (`http_c.cpp`, `archive_ncch.cpp`, `am.cpp`, `core.cpp` — the boost.serialization hubs — and dynarmic's `emit_x64_vector_floating_point.cpp`) peak at ~2 GB each; budget 1 job per 2 GB → 12 jobs total across all builds, which is what 2×`-j6` gives. A single lock file `scratchpad/build.lock` (flock) is the arbiter; agents queue on it.
- **Android APK builds are serialized (exactly one at a time) and never overlap a C++ host build** — Gradle+Kotlin daemons alone took ~15 GB today. Set `org.gradle.jvmargs=-Xmx3g` (currently `-Xmx1536m`, `gradle.properties:11`), `NDK_CCACHE=/usr/bin/ccache`, and pass `-Pandroid.native.buildOutput=verbose` only when debugging. Prefer C-1's GitHub runner for "give me an APK" and reserve local APK builds for iteration on device-only items.
- **Device access is serialized**: one agent holds `scratchpad/device.lock`; identity check (§2.2) is mandatory at acquire; no `am force-stop` unless the task says so; kill scrcpy first (`ps -A | grep scrcpy`).
- Worktrees: create with `git worktree add ../azahar-wt-<id> -b <branch> thor/main`. `thor/main` already carries `f0d319dc5` (`CMakeLists.txt:227` guards the pre-commit hook copy on `IS_DIRECTORY .git`), without which no worktree configures. Submodules are **not** checked out by `worktree add`. Two options: (a) `git submodule update --init --recursive` in the worktree — ~400 MB and a few minutes per worktree, fully independent, `git status` clean; (b) symlink the 44 `externals/<name>` directories (not `externals/` itself — `externals/CMakeLists.txt` is a tracked file) from the main checkout: CMake is read-only under `externals/` (no `configure_file`/`file(WRITE)`; `externals/CMakeLists.txt:37` only globs), so it works, but `git status` shows the symlinks as typechanges and any agent that runs `git add -A` will commit junk. **Use (a)** for any worktree that will commit; (b) only for throwaway measurement builds. One shared ccache dir makes (a)'s cost mostly disk.

### 4.2 Roster and order

**Wave 0 (enablers, parallel, ~half a day):**
- `agent-harness-host`: H-1 then H-2 (owns the tests build dir; the only agent allowed to reconfigure it).
- `agent-harness-device`: H-3 + C-2, then re-takes the §2.3 baseline on thor/main (needs the phone back on adb; until then it builds the scripts against the emulator for syntax only). Also confirms the installed build's branch via the version banner.
- `agent-ci`: C-1 (no device, no local build; verifies on GitHub).

**Wave 1 (independent worktrees, start as soon as H-1 is in; each proves its result with the metric named in §3):**
- `agent-audio`: B1 + B2 + B4 (one branch `fix/audio-pause`; commits split core/audio_core/android/qt per CLAUDE.md). Proof: D-1 in states A/B/C, thread inventory, 10× pause/resume audio check.
- `agent-lifecycle-kotlin`: B3 + R-5 + R-6 + R-9 (branch `fix/android-lifecycle`). Proof: D-1 during boot, re-intent test, rotate/fold test.
- `agent-vk-memory`: M-1 (branch `perf/vk-upload-ring`). Proof: D-2 5-min, D-4 unchanged, custom-textures-on smoke.
- `agent-vk-frame`: G-2 + G-4 (branch `perf/vk-frame`). Proof: D-4 before/after, kill-and-reboot cache-size check.
- `agent-vk-lifecycle`: R-1 + R-3 (+ bug #11) (branch `fix/vk-surface`). Proof: D-5 rotate/sleep soak.

**Wave 2 (depend on Wave 0/1):**
- `agent-secondary-window`: G-1 — rebase onto `fix/vk-surface` (shares `native.cpp` surface code). Proof: D-4, D-2, real dual-screen check on the Thor.
- `agent-trim`: M-2 — rebase onto `fix/audio-pause` (shares the paused branch of `RunCitra`). Proof: D-2 at 5 min after HOME.
- `agent-boot`: L-1 + L-2 + G-3 (branch `perf/boot`). Proof: D-3 both scenarios; savestate tests.
- `agent-memory-core`: L-4/M-3 (needs H-2). Proof: H-B `peak_rss_mb`/`boot_ms`, D-2.

**Wave 3 (after the above are merged into thor/main and the §2.3 table is re-taken):** R-4, R-2, G-6 (device-serial), G-7, G-9, M-4, M-7 — each gated on a fresh baseline and its own H-A/H-B benchmark.

Integration: each agent rebases on `thor/main` before handing over; the coordinator merges in wave order, rebuilds one APK per wave (serialized), and `agent-harness-device` re-takes the table after each wave. Any item whose metric does not move (or regresses D-4) is reverted, not argued.

### 4.3 How each agent proves its result
1. Before touching code: run the relevant metric on `thor/main` (host: H-A/H-B; device: D-x on the current APK) and paste the numbers into the commit body.
2. After: same command, same conditions (scrcpy off, screen state, same ROM/scene, 3 runs, report median).
3. Full `tests` binary passes; clang-format on changed files; license headers extended to 2026.
4. Hand back: branch name, before/after table, exact commands, any caveats.

---

## 5. Open questions the swarm should settle early
- ~~AOSP tier timing~~ settled (see §0 and `research/external-research.md`). Remaining: does thor/main's process actually sit at FGS importance through screen-off for 30+ min on the Thor (Samsung/AYN policies can demote), and what the D-1 total is in that state — the first soak on the new build is the decisive measurement. Also decide whether the FGS should be *stopped* while paused once B1 makes the paused process quiet (then the freezer protects battery and LMK is the only risk, mitigated by M-1/M-2 and autosave), or kept (no kill, but no freeze). Measure both with D-1 + D-5.
- Whether the coordinator's "45c7d2cdd-vanilla" APK is actually thor/main (`2b870bc70`) — check the version banner.
- Whether the Fold5's 0 %-CPU cached instance had its audio stream closed by the OS (audio focus loss) — if so, the FGS build will behave differently and B1 becomes even more important.

---

## 6. Non-goals and legal boundaries
- **No changes to encryption, key handling, AES/`HW::AES`, NCCH/CIA decryption, seed DB, system-file installation, or anything under `src/core/hw/aes/` and `src/core/file_sys/*ncch*`.** Boot-time work in this plan touches memory allocation and cache loading only.
- **No ROM, firmware, key, or save file is ever committed, uploaded to CI, or embedded in a test.** Host benchmarks take the ROM path from an environment variable; CI benchmarks use only public-domain homebrew (`3dsx`), and only after its license is confirmed.
- No netplay/room/Artic changes; no changes to upstream update-checker/telemetry beyond leaving them off; no Play-flavor work.
- Nothing is pushed to `upstream`; `origin` only when Marty asks. AI-POLICY.md means none of this goes upstream, so no effort is spent on upstream-compatibility.
- Not in scope: new features unrelated to performance, memory, lifecycle or QoL (e.g. UI redesign, new applets, scripting).
