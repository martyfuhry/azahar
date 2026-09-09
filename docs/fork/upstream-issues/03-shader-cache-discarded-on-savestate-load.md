# Loading a savestate destroys the renderer, and with it the disk shader/pipeline cache that was just read from disk — nothing reloads it

**Shape:** new issue. Cross-platform, not Android-specific, though Android is where I measured it.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "shader cache savestate"`, `"pipeline cache"`. Nothing filed. #1960 ("Game doesn't launch after too big of a shader cache", closed stale) and #1647 ("Transferable vulkan shader caches") are different things.

---

## Environment

- Azahar: upstream `master` at `073110cb4` (2026-09-07). The code path is old; nothing recent touched it.
- Test device: Samsung Galaxy Z Fold5 (SM-F946U1), Android 16 (API 36), Snapdragon 8 Gen 2, Adreno 740.
- Intended target: AYN Thor, Android 13, Snapdragon 8 Gen 2.
- Game: Animal Crossing: New Leaf, Vulkan, `resolution_factor = 3`, disk shader cache on.

## Reproduction

The clean version, on desktop or Android:

1. Play a title long enough to build a disk shader/pipeline cache (a few minutes is plenty).
2. Stop, relaunch, and let the boot load the cache. On Android you can watch it: `adb logcat -s CitraNative | grep -i "pipeline cache"` shows the size loaded.
3. Now load a savestate.
4. Play on.

Watch what happens after step 3: the game recompiles pipelines it already had compiled, and you get a stutter storm for the next minute or two. Check the log — nothing loads the cache back after the savestate load.

On Android I could time it directly, because the boot goes through the same path: with a state to resume on boot, the app loads the disk cache, and then immediately throws away the renderer that received it.

## Expected

Loading a savestate keeps the compiled shader/pipeline cache, or reloads it into whatever renderer replaces the old one. The cache is on disk precisely so it does not have to be recompiled.

## Actual

The cache is loaded, the renderer that holds it is destroyed a fraction of a second later, and nothing puts it back. On my boot timeline: the pipeline cache was loaded at t = 0.673 s, and the renderer holding it was destroyed at t = 1.411 s. **738 ms spent populating an object that no longer existed.**

Measured, on the same device and the same title, comparing a build that does the savestate load first against one that does not:

| | before | after |
|---|---|---|
| boot with savestate resume: intent → first emulated frame | 3527 ms | **2805 ms** (−722 ms, −20.5%) |
| time spent on the disk shader/pipeline pass that gets discarded | 738 ms | 0 ms |
| pipeline disk cache size loaded | 9587 KB | 9587 KB |
| pipeline cache loaded into the renderer that survives the load | **no** — t=0.673 s into a renderer destroyed at t=1.411 s | **yes** — t=1.854 s, after `Load completed` at t=1.847 s |

The removed 738 ms and the measured −722 ms match almost exactly, which is why I am confident this is a mechanism and not a lucky run. Both builds end up with a populated 9587 KB cache on disk; the difference is that one of them pays for the load twice and runs on the result of neither.

The stutter after a resume is the part users actually feel. The 738 ms is just the part I could put a number on.

## Root cause

`System::serialize`, `src/core/core.cpp:905-912`:

```cpp
if (Archive::is_loading::value) {
    // When loading, we want to make sure any lingering state gets cleared out before we begin.
    // Shutdown, but persist a few things between loads...
    Shutdown(true);

    [[maybe_unused]] const System::ResultStatus result =
        Init(*m_emu_window, m_secondary_window, mem_mode, num_cores);
}
```

`Shutdown` does `gpu.reset()` at `core.cpp:720` — unconditionally, `is_deserializing` does not protect it. `Init` does `gpu = std::make_unique<VideoCore::GPU>(...)` at `core.cpp:607`. So every savestate load builds a brand new `GPU`, a brand new renderer, a brand new rasterizer and a brand new pipeline cache.

The disk resources are only ever loaded from the boot path, once, before any of this:

- `src/citra_qt/bootmanager.cpp:87` — `EmuThread::run()` calls `LoadDefaultDiskResources` once, before the run loop.
- `src/android/app/src/main/jni/native.cpp:344` — the same, once, before the run loop.

The only code that reloads disk resources into a rebuilt renderer is `GPU::RecreateRenderer` (`src/video_core/gpu.cpp:500`, which calls `LoadDefaultDiskResources` at `:520`), and its only callers are in libretro (`src/citra_libretro/citra_libretro.cpp:478`). The savestate path does not go through it.

So: load a savestate on any platform, and the rest of that session runs with an empty in-memory pipeline cache, recompiling from scratch everything the game touches.

## How I fixed it in my fork

I fixed the Android boot half of it, which was the part I could measure, and left the general case handled by a reload:

I made the boot do the savestate resume **first** and load the disk cache afterwards, into the renderer that will actually run the game — the boot then pays for the cache once instead of twice, and runs on it. The load still goes through the same `Signal::Load` path rather than calling `LoadState()` directly, so the clock re-anchoring and the core error dialog behave exactly as they do for a user-picked slot. For the general case — a mid-session savestate load, where the loading screen is long gone — the init callback that the boot already registers now also records that `Init()` ran, and the run loop notices that flag and reloads the disk resources silently, with a null progress callback, the way `GPU::RecreateRenderer` does it. A smaller bug fell out in passing: `ApplyPerProgramSettings()` used to run against the renderer that the savestate load was about to destroy.

https://github.com/martyfuhry/azahar/commit/6b041f4cf

Both halves of mine are in the Android frontend; I did not touch the core. That was the right call for my fork but it is probably the wrong shape upstream — the bug is in `System::serialize`, it affects desktop identically, and the honest fix is for the core to reload the renderer's disk resources after it rebuilds the GPU (`GPU::RecreateRenderer` already knows how). That is a decision for a maintainer, not something I want to hand you as a patch. No diff attached.

## Marty must verify before filing

- [ ] Reproduce on **desktop**, where it is cleanest and platform-independent: build a shader cache, restart, load a savestate, and show from the log that nothing reloads the cache and that pipelines recompile afterwards. This is the version of the repro a maintainer can run in five minutes without an Android device, so it should lead the report.
- [ ] Reproduce on the **Thor** and confirm the post-resume stutter is real there and not a Fold5 artefact.
- [ ] Get my own boot timeline numbers on the Thor with an official build. The 738 ms / 3527 → 2805 ms figures are Fold5 numbers on my fork's builds and must be labelled as such if quoted at all.
- [ ] Confirm the `t=0.673 s load / t=1.411 s destroy` sequence in a log I pulled myself, from a build I did not modify.
- [ ] Decide before filing whether to frame this as one issue ("savestate load discards the shader cache") or two ("...and the Android boot path pays for it twice"). One issue, desktop-first, is probably right.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to trace the renderer lifetime through `System::serialize` and to instrument the boot timeline. I reproduced the behaviour and took the timings myself.*
