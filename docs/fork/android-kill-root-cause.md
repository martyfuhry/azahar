# Why Azahar loses the game on the AYN Thor after sleep/backgrounding

Read-only investigation of `/home/martyfuhry/Development/azahar` (branch `fix/clock-resync-on-resume`, tip 45c7d2cdd), plus upstream issue tracker and other emulators. All file:line refs verified against the working tree. Nothing was modified.

Package IDs matter for every adb command below: the default `vanilla` flavor is **`org.azahar_emu.azahar`** (`.debug` suffix for debug builds); only the `googlePlay` flavor is `io.github.lime3ds.android` (`src/android/app/build.gradle.kts:66,183`).

---

## 1. Root cause, ranked

### TL;DR

The app does **not** deliberately stop emulation on sleep/background. It pauses the emu thread and keeps `Core::System` alive; even OS-initiated *activity* destruction keeps the game (the fragment only calls `stopEmulation()` when `isFinishing`). "The game is gone" therefore almost always means **the whole process was killed while cached**, after which the restored task silently reboots the title from scratch, which is exactly what users describe. There is a second, smaller class of "app kills it itself" paths (re-delivered launch intents, core errors during a detached window) and a third class of Vulkan surface bugs on resume that freeze or abort rather than lose the game.

### #1 (most likely): process killed by the OS while cached — "lose the game"

**Mechanism.** On screen-off / lid-close / Home:

1. `EmulationFragment.onPause()` → `emulationState.pause()` → `NativeLibrary.pauseEmulation()` sets `pause_emulation = true` (`EmulationFragment.kt:558-563`, `native.cpp:855-861`). The emu thread parks on `running_cv` with volume forced to 0 (`native.cpp:376-384`). `Core::System` stays alive.
2. `EmulationActivity.onStop()` dismisses the secondary-display `Presentation` (`EmulationActivity.kt:220-223`) → `secondarySurfaceDestroyed`.
3. `surfaceDestroyed` → `clearSurface()` → `NativeLibrary.surfaceDestroyed()` releases the `ANativeWindow` and tells the `EmuWindow` its surface is null (`EmulationFragment.kt:1763-1785`, `native.cpp:504-515`, `emu_window.cpp:20-34`).
4. Nothing keeps the process out of the cached tier: no foreground service (removed upstream in 70221780e / 8efd95984 in 2024), no wake lock, no `onTrimMemory`, no PiP. `AndroidManifest.xml` declares no service and none of `FOREGROUND_SERVICE*`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. `keepScreenOn="true"` (`res/layout/activity_emulation.xml:8`, `fragment_emulation.xml:7`) only prevents idle sleep while foreground.
5. The process is now an ordinary cached process (oom_adj >= 900, frozen after 10 s on Android 14) holding **~0.6-0.9 GiB at 1x resolution and ~1.1-1.8 GiB at 3-4x**, of which ~330-360 MiB is dirty anonymous memory that lmkd counts in full (see §5). It is the heaviest cached process on the device by a wide margin; Android 13 (the Thor's OS) also caps cached processes at 32 (`DEFAULT_MAX_CACHED_PROCESSES`), and lmkd's `kill_heaviest_task` policy picks the biggest RSS first.
6. When the user comes back, Android restores the task: `EmulationActivity.onCreate(savedInstanceState != null)` → `isActivityRecreated = true` (`EmulationActivity.kt:147`); `onRestoreInstanceState` restores `isEmulationReady = true` from the bundle (`:243-248`); `EmulationFragment.onResume` finds `NativeLibrary.isRunning()` false (fresh process, `stop_run` initialised `true` at `native.cpp:99`) → `emulationState.run(true)` → state STOPPED → on `surfaceChanged` a new `RunCitra` **boots the game from the title screen** (`EmulationFragment.kt:1738-1752, 1787-1807`). No error, no dialog. From the user's point of view: "I woke the Thor, Azahar was in recents, I tapped it, my game restarted and I lost two hours."

**Why 8-12 GB RAM does not save you.** Available RAM after the Thor's launcher, dual-display stack, GMS and whatever else is cached is much less than nominal; the Android 13 cached-process cap and the "large cached" subreason (`LARGE_CACHED`: "process took large amount of cached memory") do not require true OOM; and the freezer kills a frozen process outright if it receives a sync binder call (`REASON_FREEZER`). Eden (yuzu fork) *has* a `specialUse` foreground service and Thor users still report the identical symptom on lid open (eden-emulator/Issue-Reports #562: "Eden was still running in the background it says and showed a preview of my game screen... it just opened the normal Eden home screen"). So on this device even the sibling agent's foreground service will reduce, not eliminate, the kills; footprint reduction and autosave are what make it survivable.

**Corroborating upstream evidence.** azahar-emu/azahar #519 (Thor/Odin 2/RP4: heavy battery drain while sleeping with Azahar open; maintainer: making sleep behave better "might also make it more likely the game will just die while sleeping"), #925 (RTC after sleep, Thor users, Marty's fix), #73 (user asking for the FGS notification back "to prevent the emulator from restarting when it's running in the background"). No upstream issue has ever been filed specifically as "game lost after sleep" - it lives inside those threads and the Reddit PSA.

### #2: the app stops emulation itself (a real but narrower class)

- **`onNewIntent` unconditionally stops the game** (`EmulationActivity.kt:178-197`): with `launchMode="singleTop"` any re-delivery of the launch intent - the Thor's dual-screen launcher, a pinned shortcut, ES-DE/Daijisho re-launching the same title - calls `NativeLibrary.stopEmulation()` and rebuilds the nav graph. Upstream #2329 is a Thor user hitting exactly this (black screen). This is the only lifecycle path that intentionally throws the game away without user confirmation.
- **Core error while the fragment is detached** → shutdown: `HandleCoreError` → `onCoreError` returns false when `sEmulationActivity` is null (between `onDetach` at `EmulationFragment.kt:566-569` and re-attach during a config change) → `RunCitra` returns `ShutdownRequested` → `TryShutdown()`. Needs an error (e.g. `ErrorArticDisconnected` after the Artic server dropped the paused client) to land in that window; unlikely but real.
- **Memory-pressure activity destruction is *not* in this class**: `EmulationFragment.onDestroy` only stops when `requireActivity().isFinishing` (`EmulationFragment.kt:571-574`), and the fragment has `retainInstance = true` (`:196`); the emu thread and `Core::System` are static globals in `native.cpp` that outlive the activity. Rotation likewise: `EmulationActivity` declares no `configChanges` (`AndroidManifest.xml:69-83`), so it is recreated, but the game survives. (Only a process kill loses it.)

### #3: crash / freeze on resume (Vulkan surface path) - loses the game via abort, or hangs

These do not explain the common "restart at title screen" report but they are the resume bugs worth fixing; they are triggered by exactly the sleep/wake and lid-close/open cycles that also drive the Thor's second screen through `SecondaryDisplay.onDisplayChanged` → new `Presentation` → `secondarySurfaceChanged`. Details with line numbers in §4. Summary:

1. Lost wake-up on unpause/stop (`native.cpp:382-383` vs `:841-848,864-877`) - frozen game after resume or an unstoppable emu thread.
2. `Swapchain::Destroy()` double-destroys semaphores after a surface-lost bail (`vk_swapchain.cpp:261-273` + `:31-48`).
3. `UNREACHABLE()` on any non-surface acquire/present error such as `VK_ERROR_DEVICE_LOST` after suspend (`vk_swapchain.cpp:112-115, 139-142`; `vk_platform.cpp:184-189`).
4. Shutdown deadlock if a stop races the resume window (present thread's un-cancellable surface wait joined while `surface_mutex` is held, `vk_present_window.cpp:376-380` + `native.cpp:169`).
5. Boot-time deadlock if the surface dies between Java `surfaceChanged` and `RunCitra` taking `surface_mutex` (`native.cpp:229-247`).

Upstream history confirms this class is real and recently active: #110, #1951, #2455 (`VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` → `UNREACHABLE()` on every pause/relayout via the secondary display path, fixed in 2126.1 and present in this tree as 3c6a44017), PR #2425 ("app suspension" listed as a trigger), PR #1946 (7e58ac5bc, which introduced bug 2).

### What is *not* the cause

- Not the JIT/`fastmem`: Azahar sets no `fastmem_pointer` and has no PROT_NONE arena (`arm_dynarmic.cpp:361-373`), so there is no multi-GB VSZ that an OEM "memory" heuristic might misread.
- Not `android:largeHeap`/Java heap: essentially all memory is native.
- No evidence of an AYN OEM app-killer. Thor OS is near-stock AOSP 13 with AYN's dual-screen launcher; no dontkillmyapp entry; firmware changelogs mention lid sleep/wake bugs, not background policy. There *is* an independent Thor dual-display resume bug (RobZombie9043/es-de-companion #57, blacksheepmvp/mjolnir #28: after Home or lid open, the activity on display id 4 comes back black and `performResumeActivity` never fires) that can make a surviving Azahar look dead on the bottom screen.

---

## 2. How to confirm on the device

Set `PKG=org.azahar_emu.azahar` (vanilla) or `io.github.lime3ds.android` (Play flavor), `.debug` suffix for debug builds. Reproduce (play, close lid / sleep 30+ min or open a few heavy apps, reopen), then:

```bash
# 1. The single most useful command: why did the last instance die?
adb shell dumpsys activity exit-info $PKG
#    reason=LOW_MEMORY(3)              -> lmkd kill        (root cause #1)
#    reason=OTHER(13) sub=TOO_MANY_CACHED/LARGE_CACHED/MEMORY_PRESSURE/TRIM_EMPTY -> ActivityManager cached-process purge (#1)
#    reason=FREEZER(14)                -> killed while frozen (#1, Android 14)
#    reason=SIGNALED(2) SIGKILL        -> lmkd on devices that don't report LOW_MEMORY (#1)
#    reason=CRASH_NATIVE(5)            -> abort/UNREACHABLE/segfault on resume (#3); pull the tombstone
#    reason=ANR(6)                     -> the surface_mutex/present-thread deadlocks (#3)
#    reason=USER_REQUESTED(10) sub=REMOVE_TASK -> user swiped it from recents
#    reason=EXIT_SELF(1)               -> the app called exit/finish itself (#2)
#    Also note pss/rss at death and "importance" (400=SERVICE,... 1000=CACHED).

# 2. Event log around the kill (survives reboot poorly; run soon after)
adb logcat -b events -d | grep -E "am_kill|am_proc_died|am_low_memory|am_proc_start|am_freeze|am_unfreeze" | grep -iE "azahar|lime3ds|am_low_memory"
#    am_kill fields: uid,pid,process,oom_adj,reason  ("too many cached", "cached #N", "Low on memory", "swipe to remove")

# 3. Main log
adb logcat -d | grep -E "lowmemorykiller|lmkd|ActivityManager: Killing|Process: Sending signal|libc.*Fatal signal|DEBUG.*(azahar|lime3ds)|Frontend|Render_Vulkan"
adb shell dmesg | grep -iE "lmk|lowmemorykiller|Out of memory|oom"     # needs root on some devices

# 4. Footprint while backgrounded (what the killer sees) - run while the game is paused in background
adb shell dumpsys meminfo $PKG          # look at TOTAL PSS, "Native Heap", "Gfx dev"/"GL mtrack", ".so mmap"
adb shell dumpsys activity processes | grep -B2 -A8 "$PKG"   # oomAdj / procState / "cached"
adb shell cat /proc/$(adb shell pidof $PKG)/oom_score_adj   # 0 fg, 100 visible, 200 perceptible(FGS), 900+ cached
adb shell cat /proc/$(adb shell pidof $PKG)/status | grep -E "VmRSS|RssAnon|RssFile|RssShmem"

# 5. Device policy knobs
adb shell getprop | grep ro.lmk                                # medium/critical thresholds, kill_heaviest_task, use_psi
adb shell device_config get activity_manager max_cached_processes
adb shell dumpsys activity settings | grep -iE "cached|phantom"
adb shell settings get global background_process_limit          # dev-options "Background process limit" (-1 = standard)
adb shell dumpsys activity exit-info                             # all packages: was it a device-wide purge?

# 6. Vulkan surface-path freeze/hang (no death, black screen or frozen game): grab a native stack
adb shell debuggerd -b $(adb shell pidof $PKG) | grep -A12 -E "NativeEmulation|PresentThread|RunCitra|recreate_surface"
```

Cheap in-app alternative (see mitigation M6): on launch call `ActivityManager.getHistoricalProcessExitReasons(null, 0, 5)` and write the reasons to `citra_log.txt` so Reddit-class reports carry the answer without adb.

---

## 3. Mitigations beyond foreground service and autosave (prioritised)

Legend: effort S (< 1 h), M (half day), L (days). "Kill" = reduces chance of process death; "Resume" = fixes a crash/freeze after wake; "Diag" = diagnosis.

| # | Mitigation | Class | Effort | Where |
|---|---|---|---|---|
| M1 | **Shrink the resident footprint while backgrounded** via `onTrimMemory(TRIM_MEMORY_UI_HIDDEN/BACKGROUND)` → new JNI `NativeLibrary.trimMemory()` that (a) `RasterizerCache::UnregisterAll()`/`ClearAll` on the emu thread (via a new `System::Signal` or request flag polled in `RunLoop`, the `RequestClockResync` pattern), (b) `malloc_trim(0)` / `mallopt(M_PURGE, 0)`, (c) drops the GL staging `std::vector` high-water mark. Textures come back on demand after resume (one-time hitch). Expected saving: 100-450 MiB gfx/anon at 3-4x. | Kill | M | `CitraApplication.kt` (implement `ComponentCallbacks2`), `EmulationActivity.kt`, `NativeLibrary.kt`, `jni/native.cpp`, `src/core/core.h` (signal), `src/video_core/rasterizer_cache/rasterizer_cache.h:107-146` (`UnregisterAll`, GC), `renderer_opengl/gl_texture_runtime.cpp:142-143` |
| M2 | **Cut the Vulkan upload staging ring from 512 MiB** to 64-128 MiB (it is a ring that faults in monotonically and never shrinks; on UMA Adreno the `heap_size/2` clamp is ~4 GiB so the full 512 MiB is mapped HOST_VISIBLE). Consider clamping against `ActivityManager.getLargeMemoryClass()` or a `Settings` knob on Android. | Kill | S | `src/video_core/renderer_vulkan/vk_texture_runtime.cpp:161`, `vk_stream_buffer.cpp:187-191,230-232` |
| M3 | **Allocate FCRAM at 128 MiB unless `is_new_3ds`** - it is unconditionally `make_unique<u8[]>(FCRAM_N3DS_SIZE)` and value-initialised, so 128 MiB of never-used memory is dirty anon from boot in Old-3DS mode. (Default `is_new_3ds=true`, so this only pays if Marty runs Old-3DS mode; still a free 128 MiB there.) Also the 40 MiB `PageTable::refs` array of null `shared_ptr`s could be 24 B/entry. | Kill | S-M | `src/core/memory.cpp:105`, `src/core/memory.h:37,98-109,181-182` |
| M4 | **Fix the savestate triple-copy before autosave-on-pause ships.** `SaveState` serialises to an `ostringstream`, copies it into a `std::string`, then zstd-compresses into a third buffer: **+800 MiB-1 GiB transient** for a N3DS title, at the exact moment (onPause under memory pressure) the OS is deciding whom to kill. An autosave at `onPause` can *cause* the kill it is meant to insure against. Serialise into a `std::vector<u8>` via a custom `streambuf` and stream to zstd. Tell the autosave agent. | Kill | M | `src/core/savestate.cpp:155-162` (and the mirror in `LoadState` `:238`, `SaveStateBuffer` `:252+`) |
| M5 | **Fix the lost wake-up**: `unPauseEmulation`/`stopEmulation` flip the atomics and `notify_all()` without holding `paused_mutex`, while the waiter evaluates the predicate under it. Take `std::scoped_lock l{paused_mutex}` around the flag write (or before `notify_all`). Symptom is "resumed but frozen forever" or "Exit does nothing, next game stuck on loading". | Resume | S | `src/android/app/src/main/jni/native.cpp:841-848, 864-877` vs `:382-383` |
| M6 | **Log the previous death reason at launch** (`getHistoricalProcessExitReasons`) and call `ActivityManager.setProcessStateSummary(bytes)` with the running title id + last-autosave timestamp so `exit-info` shows what was lost. Turns every user report into a diagnosis. | Diag | S | `CitraApplication.kt` or `MainActivity.kt`, `EmulationActivity.kt` (after `playTimeManagerStart`) |
| M7 | **Reset `image_count` in `Swapchain::Destroy()`** (or destroy only `image_acquired.size()` entries). After a surface-lost bail in `Create`, the next `Create` (next surface arrival) indexes cleared vectors and calls `vkDestroySemaphore` on dead handles. Triggered by sleeping/rotating again while the resumed surface is still being recreated. | Resume | S | `src/video_core/renderer_vulkan/vk_swapchain.cpp:261-273` (+ `:31-48`) |
| M8 | **Do not `UNREACHABLE()` on `VK_ERROR_DEVICE_LOST`** (or any unexpected acquire/present result): route it to `HandleCoreError` with a "GPU device lost, please restart" dialog (progress still lost, but autosave can catch it) or attempt a full renderer recreation. Also guard `FindPresentFormat` (unhandled `SurfaceLostKHRError` in the `Swapchain` ctor → `std::terminate` during boot/savestate-load renderer creation). | Resume | M-L | `vk_swapchain.cpp:112-115, 139-142, 147-171`, `vk_platform.cpp:184-189, 200-203` |
| M9 | **Make `onNewIntent` a no-op when the incoming title is the one already running** (upstream #2329, Thor launchers). Only stop and reload when the title id differs. | App-stop | S | `EmulationActivity.kt:178-197` |
| M10 | **Pause during boot too.** `EmulationFragment.onPause` skips `pause()` when `NativeLibrary.isRunning()` is false (`stop_run` only flips at `native.cpp:336` after `Load`), so sleeping the Thor during the loading/shader-cache screen leaves the guest running headless with audio in the background (contributes to #519 battery drain and to being noticed as a "busy" background process). Add a `pause_requested` atomic consumed when the loop starts, or have `clearSurface()` in RUNNING call `pauseEmulation()` (`EmulationFragment.kt:1770-1773`). | Kill/Battery | S | `EmulationFragment.kt:558-563, 1770-1773`, `native.cpp:336` |
| M11 | **Stop the audio stream on pause** (the emu thread parks, but the output sink keeps its callback stream alive delivering silence) - likely the main contributor to #519's "back is warm while sleeping" on the Thor. Not a kill cause, but a hot, CPU-active cached process is a worse LMK citizen than a quiet one. | Battery | M | `src/audio_core/sink/*` (`oboe`/`cubeb` sink pause), call from `pauseEmulation`/`unPauseEmulation` in `native.cpp` |
| M12 | **Picture-in-Picture on `onUserLeaveHint`** (Eden's approach): a PiP activity keeps the process at *visible* importance (adj 100) without any service or notification, and the game keeps running/visible. Helps Home-button backgrounding; does not help lid-close/screen-off (PiP is stopped too), and the Thor's dual-display stack is already flaky. Optional, behind a setting. | Kill | M | `EmulationActivity.kt` (`onUserLeaveHint`, `enterPictureInPictureMode`, `onPictureInPictureModeChanged`), `AndroidManifest.xml` (`supportsPictureInPicture`, add `configChanges`) |
| M13 | **Add `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density"` to `EmulationActivity`** so lid/display changes on the Thor don't recreate the activity and churn the surface (the fragment survives today, but every recreation exercises the surface race paths above). Low risk; the app already handles `surfaceChanged` for size. | Resume | S | `AndroidManifest.xml:69-83`, `EmulationActivity.kt` (`onConfigurationChanged` → `applyOrientationSettings`) |
| M14 | **Destroy the previous `VkSurfaceKHR` in `Swapchain::Create`** (currently leaked per recreation, each pinning an `ANativeWindow`), and **reset `last_render_surface` in `surfaceDestroyed`**. Today the leak is what keeps the pointer-equality dedupe at `vk_present_window.cpp:355-359` from matching a recycled address; if the leak is fixed without the reset, a same-address new window would be skipped and the present thread would wait forever (black screen). Do both together. | Resume | S | `vk_swapchain.cpp:34`, `vk_present_window.cpp:347-371`, `native.cpp:504-515` |
| M15 | **Don't hold `surface_mutex` through the whole `System::Load`** and give the present thread's surface wait a stop token; today a `surfaceDestroyed` during a slow boot blocks the UI thread (ANR → the OS kills the process with `REASON_ANR`), and a stop racing the resume window deadlocks in `TryShutdown`. | Resume | M | `native.cpp:222-330, 169`, `vk_present_window.cpp:376-380, 155-173` |
| M16 | **Drop the throw-away null-page-table JIT**: `ARM_Dynarmic`'s ctor calls `SetPageTable(nullptr)` and builds a JIT that is never used, per core (4x on N3DS), 128 MiB VA each. Only VA plus whatever the ctor dirties, so low payoff for lmkd, but free. | Kill (minor) | S | `src/core/arm/dynarmic/arm_dynarmic.cpp:185, 334-352` |
| M17 | **User-side knobs to document** (no code): Developer options → "Background process limit" must be "Standard limit"; Settings → Apps → Azahar → disable "Pause app activity if unused" (#1052) and set Battery to "Unrestricted"; `adb shell device_config put activity_manager max_cached_processes 1024`; never enable "Preload custom textures" (budget is computed from *total* RAM: up to 6 GiB on an 8 GB unit, `custom_tex_manager.cpp:211-216`); keep resolution at 1-2x on the Thor's 8 GB SKU. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in-app is not worth it: it affects Doze/App Standby, not lmkd or the cached-process cap. | Kill | - | docs / settings help text |

Not recommended: `android:persistent` (system apps only), `android:process` splitting (the native heap *is* the app), a wake lock while paused (opposite of #519's problem), `TextureView` instead of `SurfaceView` (worse: adds a copy and an extra buffer).

Ordering rationale: M1+M2 (+M3) attack the actual selection criterion (footprint of the cached process) and stack with the sibling FGS; M4 protects the sibling autosave from being self-defeating; M5/M7 are two-line fixes for real resume bugs; M6 makes every future report actionable.

---

## 4. Concrete bugs found in the code (file:line)

1. **Lost wake-up, `native.cpp:841-848` / `:864-877` vs `:382-383`.** `unPauseEmulation()` and `stopEmulation()` write `pause_emulation`/`stop_run` and `running_cv.notify_all()` without taking `paused_mutex`; the emu thread checks the predicate under `paused_mutex` and then blocks. A notify that lands between the predicate check and the block is lost: unpause → game stays frozen; stop → emu thread never exits, `running_mutex` (`:201`) is held forever and the next `run` spins on the loading screen. Narrow window, but it sits exactly on the resume path.

2. **`Swapchain::Destroy()` double-destroy, `vk_swapchain.cpp:261-273` with `:31-48`.** `Destroy()` loops `for (i < image_count)` over `image_acquired[i]`/`present_ready[i]` and then `clear()`s the vectors but never resets `image_count`. If `Create()` bails early with `needs_recreation` (surface lost at `:41`, `:46` or `:80`), the next `Create()` calls `Destroy()` again → out-of-bounds read of cleared vectors and `vkDestroySemaphore` on already-destroyed handles. Introduced by upstream 7e58ac5bc; triggered by a second sleep/rotation while the resumed surface is being recreated.

3. **`UNREACHABLE()` on non-surface Vulkan errors.** `vk_swapchain.cpp:112-115` (acquire: anything but success/suboptimal/surface-lost/out-of-date, e.g. `VK_ERROR_DEVICE_LOST` after a GPU suspend), `:139-142` (present), `vk_platform.cpp:184-189, 200-203` (surface creation failure). All abort the process → `REASON_CRASH_NATIVE`, game lost.

4. **Unguarded `FindPresentFormat`, `vk_swapchain.cpp:147-171`,** called from the `Swapchain` ctor (`:21`) during `PresentWindow` construction (boot or savestate-load renderer recreation). A surface lost there throws `vk::SurfaceLostKHRError` out of `System::Init` → `std::terminate`.

5. **Shutdown deadlock, `vk_present_window.cpp:376-380` + `native.cpp:169`.** `recreate_swapchain`'s wait on `recreate_surface_cv` has no `stop_token` (unlike `PresentThread`, `:323`); `~PresentWindow` joins the `jthread`. If shutdown happens while a present is waiting for a surface (a state entered on *every* resume between `unpause()` and `surfaceChanged`, because `Fragment.onResume` unpauses before the surface exists), `TryShutdown` blocks in `gpu.reset()` holding `surface_mutex`, and the UI thread's `surfaceChanged` that would deliver the surface blocks on the same mutex → ANR + zombie emu thread. Reachable via `onNewIntent` (`EmulationActivity.kt:182`), guest `ShutdownRequested`, `exitEmulationActivity`.

6. **Boot deadlock in the Init callback, `native.cpp:229-247`.** The callback locks the *recursive* `surface_mutex` and, if `s_surface` is null, waits on `surface_cv` with `adopt_lock`, which releases only one recursion level; `RunCitra` already holds it (`:222`). If `surfaceDestroyed` slips in between Java's `NativeLibrary.surfaceChanged` (`EmulationFragment.kt:1788`) and `:222` (screen off right at launch), the `EmuWindow` is built with a null surface (`emu_window.cpp:57-60`), the callback waits with the mutex still held, and no surface can ever be delivered.

7. **`VkSurfaceKHR` leak per recreation, `vk_swapchain.cpp:34`** (`surface = surface_` overwrites without destroying; only the last one is destroyed at `:28`). Each background/rotation cycle leaks a surface and pins its `ANativeWindow`. Masks the address-reuse hazard in the `last_render_surface` dedupe (`vk_present_window.cpp:355-359`), which `surfaceDestroyed` never resets.

8. **`clearSurface()` in RUNNING flips Java state to PAUSED without `pauseEmulation()`, `EmulationFragment.kt:1770-1773`,** and `onPause` skips the native pause while `isRunning()` is false during boot (`:558-563`, `native.cpp:336, 880-883`). Backgrounding during boot leaves the guest running headless with audio.

9. **Stale `isEmulationReady` after process death, `EmulationActivity.kt:207-211, 243-248`.** After a kill-and-restore, `onResume` calls `setEmulationStarted(true)` from the saved bundle before the fresh boot has started, hiding the loading UI and unlocking the drawer; an Exit tap during that boot is a no-op because `stop_run` is still `true` (`native.cpp:866`), so the emu thread finishes booting and runs in the background (the `TODO(xperia64)` at `native.cpp:840`).

10. **`doFrame` UAF on guest-initiated shutdown, `native.cpp:519-527` vs `:185-188`.** Choreographer's `doFrame` reads `window`/`secondary_window` on the UI thread guarded only by `stop_run`; when the *guest* requests shutdown (`ShutdownRequested`, `:360-361`) `stop_run` stays false while `TryShutdown` resets the windows on the emu thread. Not resume-related.

11. **`secondarySurfaceDestroyed` never tells the secondary `EmuWindow`, `native.cpp:492-502`** (contrast the primary at `:504-515`, which calls `window->OnSurfaceChanged(nullptr)`). The secondary window keeps a dangling `render_window` until the next `secondarySurfaceChanged`. On the Thor the secondary path is exercised by every lid/screen-state change (`SecondaryDisplay.onDisplayChanged` → `updateDisplay()` → new `Presentation`, `SecondaryDisplay.kt:82-131, 146-156`).

12. **Non-thread-safe `EmuWindow_Android::OnSurfaceChanged`, `emu_window.cpp:20-34`** (UI thread writes `render_window`, `window_width/height`, layout; emu/present threads read them; `presenting_state` is a plain enum shared across threads, `emu_window_gl.cpp:199-217`). Torn layout / `EGL_BAD_SURFACE` for a frame; self-healing. Also the Android contract "do not touch the Surface after `surfaceDestroyed` returns" is not honoured (`surfaceDestroyed` returns immediately while the swapchain/EGL surface still references the window); in practice it degrades to surface-lost errors that *are* handled.

---

## 5. Memory footprint (why a 1-2 GiB cached process is the first to go)

No fastmem, no `HostMemory`, no ashmem/memfd (except adrenotools' custom-driver dlopen, ~10-15 MiB memfd, counted). Guest memory is eagerly zeroed heap, so almost all of it is dirty anonymous RSS that lmkd counts in full.

| Contributor | Size | Kind | Ref |
|---|---|---|---|
| FCRAM (always N3DS size, value-initialised) | 256 MiB | anon, dirty at boot | `memory.cpp:105`, `memory.h:181-182` |
| VRAM + N3DS extra + DSP | 10.5 MiB | anon | `memory.cpp:106-108` |
| `PageTable` (`raw` 8 + `refs` 40 + `attributes` 1) | ~49 MiB | anon, dirty | `memory.h:37,98-109` |
| Dynarmic emitted code (of 4 cores x 2 JITs x 128 MiB **VA**) | 5-25 MiB | anon RWX | `externals/dynarmic/.../A32/config.h:239`, `arm_dynarmic.cpp:185,334-352` |
| Core/HLE/audio/misc | 10-20 MiB | anon | |
| **Anon subtotal** | **~330-360 MiB** | | |
| Vk stream + uniform + texel buffers | 74 MiB | gfx, mapped | `vk_rasterizer.cpp:35-37` |
| Vk upload ring (512 MiB, faults in monotonically) | 30-150 MiB → 512 | gfx | `vk_texture_runtime.cpp:161` |
| Vk download ring | 2-16 MiB | gfx | `vk_texture_runtime.cpp:162` |
| Texture/RT cache @1x | 20-60 MiB | gfx | `rasterizer_cache.h`, no budget/LRU (`:130-146`) |
| Swapchain/present frames | 15-30 MiB | gfx | |
| `.so` text/rodata (+ Turnip ~10-15 MiB if custom driver) | 60-110 MiB | file-backed | |
| ART heap + framework | 40-80 MiB | mixed | |
| **Total @1x** | **~570-880 MiB** | | |
| **Total @3-4x** (texture cache 150-450 MiB; x(1+N^2) per surface, and *sampled* textures scale too when any texture filter is on, `rasterizer_cache.h:583`) | **~1.1-1.8 GiB**, >2 GiB with filter + long session | | |
| Transient: savestate save | **+800 MiB-1 GiB** | anon | `savestate.cpp:155-162` |
| Transient: custom-texture preload | up to +6 GiB on 8 GB | anon | `custom_tex_manager.cpp:211-216` |

The graphics allocations show under "Gfx dev"/"GL mtrack" in `dumpsys meminfo`, are unreclaimable, and count in `VmRSS`. The 1 GiB dynarmic VA reservation and the untouched tail of the upload ring are VSZ only and do not count. There is no `onTrimMemory`/`onLowMemory`/`ComponentCallbacks2` anywhere under `src/android/` and no cache eviction on background, so the cached process keeps its full working set while the OS looks for a victim.

Verdict on plausibility: on an 8 GB Thor with the Android 13 cached-process cap, the freezer (if on 14), `kill_heaviest_task`, and a launcher + dual-display stack + GMS in front of it, a ~1-1.5 GiB cached process that has been paused for 30+ minutes is the expected victim - memory pressure/cache policy is a more plausible primary cause than an OEM killer (none found) or the app's own lifecycle handling (which is correct in the pause/keep-alive sense, just unprotected). Crash-on-resume is real but secondary, and it shows up as `CRASH_NATIVE`/ANR in `exit-info`, which is how to tell the classes apart.

---

## 6. Cross-references for the sibling agents

- **Foreground service agent:** upstream removed the FGS in 70221780e (Lime3DS PR #198, bundled with the Vulkan surface-recreation fixes) and 8efd95984 (leftovers); the original `ForegroundService.kt` was `specialUse` with subtype "Keep emulation running in background", `START_STICKY`, started in `EmulationActivity.onCreate`. Eden still ships that exact file and Thor users still lose games with it (#562), so pair it with M1/M2. `mediaPlayback` type avoids the Play `specialUse` review and has no timeout. Start it from the foreground activity (always allowed).
- **Autosave agent:** see M4 - the current `SaveState` transient (~0.8-1 GiB) is larger than the steady-state anon footprint; an autosave in `onPause` on a memory-tight device is likely to be the thing that gets the process killed unless the copy chain is fixed or the save is done before pause (e.g. periodic, or on `onUserLeaveHint`/`TRIM_MEMORY_UI_HIDDEN` with a smaller working set). Also note savestates from a different build fail with `ErrorSavestateBuildMismatch` (`core/savestate.cpp`), and upstream #2393/#2448/#2494 (savestate load SIGSEGV when launched from a frontend; `Network::Init` only ran in `MainActivity`) - check that the fix is in this tree before relying on load-after-kill from a launcher.
- **Clock-resync branch (Marty's):** the `RequestClockResync()` call at `native.cpp:843-846` is safe with respect to everything above; it sets an atomic consumed on the emu thread after the `IsPoweredOn` check and is reset on fresh boot.
