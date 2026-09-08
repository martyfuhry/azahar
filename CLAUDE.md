# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About this fork

This is Marty's personal fork of Azahar (`origin` = martyfuhry/azahar, `upstream` = azahar-emu/azahar). Its purpose is AI-assisted fixes and features for Marty's own devices, primarily the AYN Thor (Android, Snapdragon 8 Gen 2). Upstream's `AI-POLICY.md` prohibits AI-written contributions, so **work in this fork is never sent upstream**. Never push to `upstream`, never open PRs against azahar-emu. Push to `origin` only when asked.

Commit format (mirrors upstream): `subsystem: Imperative summary`, e.g. `core: Add RequestClockResync to Core::System`, `qt: Resync guest clock when resuming emulation`, `tests: Cover clock anchoring at construction`. Subsystems in use: `core`, `qt`, `android`, `tests`, `common`, `video_core`, `audio_core`. Body explains the hardware behavior being matched and the design constraints, not just what changed. See `git log master..fix/clock-resync-on-resume` for the reference style, including how a core change is split into core / frontend / tests commits.

**Branches.** `master` tracks `upstream/master` and is only ever fast-forwarded. Each feature lives on its own branch off master (`fix/clock-resync-on-resume`, `spike/autosave-on-exit`, `spike/android-foreground-service`) and is rebased onto master when upstream moves. `thor/main` is the integration branch that merges them all and is what gets built for the device. Background on why Android kills the app, with device commands and a ranked mitigation list, is in `docs/fork/android-kill-root-cause.md`.

## Build and test

C++20, CMake + Ninja, 36 submodules under `externals/` (run `git submodule update --init --recursive` on a fresh clone, or `tools/reset-submodules.sh`). Output binaries land in `<build>/bin/<Config>/`.

A tests-only build already exists at `../azahar-build-tests` (Release, Qt/SDL2/web service/scripting off). Reuse it rather than configuring a new one; agents working in worktrees build in their own `build/` with the configure line below:

```bash
# Rebuild and run the whole Catch2 suite
cmake --build ../azahar-build-tests --target tests && ../azahar-build-tests/bin/Release/tests

# One test case, one tag, or one section
../azahar-build-tests/bin/Release/tests "SharedPage::Handler::ResyncWithHostClock"
../azahar-build-tests/bin/Release/tests "[shared_page]"
../azahar-build-tests/bin/Release/tests "[core]" -c "advances the clock by the host time that passed while paused"
../azahar-build-tests/bin/Release/tests --list-tests
```

To configure a new tests-only build (what `../azahar-build-tests` and every agent worktree use; `ENABLE_LTO=OFF` because the LTO link is a multi-GB memory spike and this machine has no ccache, so keep the link cheap and the rebuilds incremental):

```bash
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_QT=OFF -DENABLE_SDL2=OFF -DENABLE_WEB_SERVICE=OFF -DENABLE_OPENAL=OFF \
  -DENABLE_SCRIPTING=OFF -DENABLE_ROOM_STANDALONE=OFF -DENABLE_LTO=OFF   # add -DENABLE_BENCH=ON for azahar-bench
cmake --build build --target tests -j4 && build/bin/Release/tests
```

The full CI recipe (`.ci/linux.sh`) is the same without the `ENABLE_*=OFF` flags and with `-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache`; it ends with `ctest --test-dir build -C Release`. A tests build from scratch takes 20-30 minutes at `-j4`; run at most two host builds at once on this machine.

Useful options: `ENABLE_QT`, `ENABLE_TESTS`, `ENABLE_BENCH`, `ENABLE_WEB_SERVICE`, `ENABLE_OPENGL`/`ENABLE_VULKAN`/`ENABLE_SOFTWARE_RENDERER`, `ENABLE_LTO`, `CITRA_USE_PRECOMPILED_HEADERS`, `CITRA_WARNINGS_AS_ERRORS` (ON by default, so new warnings break the build), `CITRA_ADDRESS_SANITIZE`, `ENABLE_DEVELOPER_OPTIONS`.

### Benchmarks

Two host-side harnesses back the numbers in `docs/fork/improvement-plan.md` (metrics H-A and H-B). Both take before/after runs on the same machine; report the median of 3.

**H-A, Catch2 micro-benchmarks** live in `src/tests/bench/` inside the `tests` binary, tagged `[bench]` and excluded from `ctest`/the default run. They need no ROM. Run them with the XML reporter and diff the `<mean>` values between builds:

```bash
build/bin/Release/tests "[bench]" --benchmark-samples 20 --benchmark-no-analysis -r xml -o bench.xml
build/bin/Release/tests "[bench][memory]"          # one subsystem, human-readable table
```

Add a benchmark as a `TEST_CASE("Namespace::Class::Method", "[bench][subsystem]")` containing `BENCHMARK("what") { ... }` blocks (`<catch2/benchmark/catch_benchmark.hpp>`), with a comment naming the plan item it is the baseline for.

**H-B, `azahar-bench`** (`src/citra_bench/`, built with `-DENABLE_BENCH=ON`) boots a title headless on the software renderer with a null audio sink, no frame limit and no disk shader cache, runs N system frames and prints one `key value` line per metric: `boot_ms` (Load to first vblank), `frames_per_s` and `cpu_ms_per_frame` (steady state, after the first frame), `cpu_ms`, `peak_rss_mb`/`vm_rss_mb`/`rss_anon_mb`, and the `PerfStats::Results` fields as `perf_*`. It uses a fresh temporary user directory per run (`--user-dir` to override, `--keep-user-dir` to inspect it). The ROM is Marty's own dump and is never committed; pass it on the command line or in `AZAHAR_BENCH_ROM`:

```bash
AZAHAR_BENCH_ROM=/path/to/acnl.3ds build/bin/Release/azahar-bench --frames 600
build/bin/Release/azahar-bench --frames 600 --save-after 300 /path/to/rom   # adds savestate_save_ms, savestate_load_ms, savestate_bytes
build/bin/Release/azahar-bench --movie input.ctm /path/to/rom                # deterministic input
```

Android (Gradle, from `src/android/`): `./gradlew assembleVanillaRelease` or `assembleGooglePlayRelease`; the NDK build reuses the same CMake tree. Kotlin formatting: `tools/check-kotlin-formatting.sh` / `tools/fix-kotlin-formatting.sh` (ktlint).

The full CI toolchain lives in the Docker image `opensauce04/azahar-build-environment`; `tools/enter-docker-dev-container.sh` mounts the repo at `/mnt` in it.

## Style checks that CI enforces

- `clang-format` (config at `src/.clang-format`, local version 19) on every changed `.cpp`/`.h` under `src/`. Run `clang-format -i <file>` before committing.
- No trailing whitespace anywhere, no tabs under `src/` (`hooks/pre-commit` enforces this; install with `ln -s ../../hooks/pre-commit .git/hooks/pre-commit`).
- Every changed `.cpp/.h/.kt/.kts/.m/.mm` must start with the license header, and its year range must include the current year. Extend an existing range (`2015` → `2015-2026`) rather than replacing it:

```cpp
// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.
```

## Architecture

**`Core::System` (`src/core/core.h`)** is a singleton (`Core::System::GetInstance()`; `Core::Global<T>()` in `src/core/global.h` reaches it from serialization code). It owns, as `unique_ptr`s, the `Timing`, `Kernel`, `Memory`, ARM cores, `GPU`, DSP, `Service::SM` manager, archive manager, `Movie`, cheats, and perf stats. `Load()` picks an `AppLoader` then `Init()` constructs subsystems in order Timing → Kernel → Memory → CPU → GPU → DSP → `Service::Init()`. Frontends call `RunLoop()` repeatedly from their own emu thread. Anything that must happen on the emu thread but is triggered from the UI goes through `SendSignal(Signal::…)` or a `Request*()` flag that `RunLoop()` polls (see `RequestClockResync`). Frontends register applets and permission callbacks (`RegisterSoftwareKeyboard`, `RegisterMiiSelector`, …) on the system.

**Savestates** are boost::serialization. Each serializable class has a `template <class Archive> void serialize(Archive&, unsigned)` in the header and a `SERIALIZE_IMPL(Class)` line at the bottom of its `.cpp` (`src/common/archives.h`). `src/core/savestate.cpp` wraps the archive in a zstd-compressed `.cst` file whose header carries the git revision; loading a state from a different build fails with `ErrorSavestateBuildMismatch`. When you add persistent state to a class, add it to `serialize()` and consider `load_construct_data` for anything that must be re-anchored to the host after load (the clock-resync branch is the worked example).

**Core timing (`src/core/core_timing.h`)**: one `Timing::Timer` per emulated core with slice/downcount scheduling, a min-heap event queue and a thread-safe MPSC queue for cross-thread `ScheduleEvent(…, thread_safe_mode=true)`. `GetGlobalTimeUs()` is emulated time including the randomized base ticks. Ticks are added by the CPU backend (dynarmic JIT by default, dyncom interpreter as fallback), so emulated time simply stops while the frontend is paused. Tests advance time by calling `Timer::AddTicks`/`Advance`/`SetNextSlice` in a loop (see `src/tests/core/hle/kernel/shared_page.cpp`).

**HLE services** live in `src/core/hle/service/<name>/`. Each is a `ServiceFramework<T>` with a `FunctionInfo` table `{command_id, &T::Handler, "Name"}`; handlers take a `Kernel::HLERequestContext` and use `IPC::RequestParser` / `IPC::RequestBuilder` from `src/core/hle/ipc_helpers.h`. Kernel objects, SVCs and the shared page are in `src/core/hle/kernel/`.

**Settings (`src/common/settings.h`)**: one global `Settings::values`. `Setting<T>` is global-only; `SwitchableSetting<T>` also carries a per-game override. Key strings are generated by `CMakeModules/GenerateSettingKeys.cmake` into `common/setting_keys.h` and the JNI/Kotlin mirrors. Adding a setting touches: GenerateSettingKeys.cmake, settings.h, `src/citra_qt/configuration/config.cpp` plus a `configure_*.cpp/.ui`, `src/android/app/src/main/jni/config.cpp` and `default_ini.h`, and the Kotlin side (`SettingKeys.kt`, `BooleanSetting.kt`/`IntSetting.kt`, `SettingsFragmentPresenter.kt`, `strings.xml`). Find a recent setting commit with `git log -S<key_name>` and copy its touchpoints.

**Frontends**:
- `src/citra_meta` is the shipped `azahar` binary; it dispatches to Qt, CLI (`src/citra_cli`) or the room server.
- `src/citra_qt`: `bootmanager.h` has `EmuThread` (a `QThread`) whose `run()` loops `System::RunLoop()`, paused via `SetRunning()` and a condition variable. Pause/resume hooks belong in `citra_qt.cpp` `OnStartGame`/`OnPauseGame`.
- `src/android`: Kotlin app in `app/src/main/java/org/citra/citra_emu/`; `NativeLibrary.kt` declares the JNI surface implemented in `app/src/main/jni/native.cpp`. The emu thread is `RunCitra()` in native.cpp, gated by `stop_run`/`pause_emulation` atomics. Pause/resume from the Activity lifecycle arrives via `NativeLibrary.pauseEmulation()`/`unPauseEmulation()`. Android has its own settings config (`jni/config.cpp`) and its own GPU driver loading (`libadrenotools`).
- `src/citra_libretro`: libretro core with its own window/input/audio glue.

**video_core**: PICA register and pipeline emulation in `src/video_core/pica/`, shared `rasterizer_cache/`, and three backends: `renderer_opengl/`, `renderer_vulkan/`, `renderer_software/`. **audio_core**: `DspInterface` with HLE (`hle/`) or Teakra LLE (`lle/`) DSP and pluggable output sinks.

**Logging**: `LOG_INFO(Kernel, "...")`, `LOG_DEBUG(Core_Timing, ...)`; the first argument is a log class from `src/common/logging/types.h`, add one there if none fits. Uses fmt formatting.

**Tests** are a single Catch2 binary. Add new files to `src/tests/CMakeLists.txt`. Convention: `TEST_CASE("Namespace::Class::Method", "[core][kernel][shared_page]")` with `SECTION`s named as behaviors, RAII structs to restore any `Settings::values` the test changes, and no sleeping unless the behavior under test genuinely depends on wall-clock time.
