# Android: a hidden 1920x1080 VirtualDisplay is always created, and a second full frame is rendered and presented into it every vblank

**Shape:** new issue.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "virtual display secondary"`, `"hidden display"`, `"VirtualDisplay"`. Nothing filed. #351 and #1918 are about external display support working, not about the cost of the display that is always there. #2455 is a crash on this code path (closed, fixed in 2126.1) and is worth linking as related, since it is the same "the secondary display path runs even with no secondary display" surprise seen from the crash end.

---

## Environment

- Azahar: upstream `master` at `073110cb4` (2026-09-07). Present since the Android secondary-display work (#617).
- Test device: Samsung Galaxy Z Fold5 (SM-F946U1), Android 16 (API 36), Snapdragon 8 Gen 2, Adreno 740. **A single-screen phone** — this is the point.
- Intended target: AYN Thor, Android 13, dual screen, where the second display is real and this cost is legitimate.
- Game: Animal Crossing: New Leaf, Vulkan, `resolution_factor = 3`.

## Reproduction

No special build needed. On any single-screen Android device:

1. Turn **Settings > Layout > Enable Secondary Display** off, or leave the secondary layout at NONE. It does not matter.
2. Launch any title.
3. Ask the system what displays exist:

```
adb shell dumpsys display | grep -i HiddenDisplay
adb shell dumpsys SurfaceFlinger --list | grep -i HiddenDisplay
```

You get a 1920x1080 virtual display named `HiddenDisplay`, owner `org.azahar_emu.azahar`, and a SurfaceFlinger layer for it.

4. Check the memory and thread cost:

```
adb shell dumpsys meminfo org.azahar_emu.azahar   # look at EGL mtrack
adb shell cat /proc/$(adb shell pidof org.azahar_emu.azahar)/status | grep Threads
```

## Expected

On a device with one screen, and with the secondary display setting off, there is no second display, no second swapchain, and no second render/present pass per frame.

## Actual

There is. `SecondaryDisplay`'s constructor creates the `HiddenDisplay` virtual display unconditionally, the `Presentation` is put on it whenever there is no real second display to use, and because that `Presentation` is a real surface, the core renders and presents a complete second frame into it every vblank.

Per frame, on a single-screen phone with the setting off, that is: one extra `GetRenderFrame()` fence wait on the emulation thread, one extra fullscreen render pass, one extra blit, one extra `vkQueueSubmit`, one extra `vkQueuePresentKHR`, and two extra `submit_mutex` acquisitions — plus a second 1080p swapchain resident in VRAM for the whole session. For pixels no panel ever composites.

Measured before and after removing it, same device, same title, same scene, back to back:

| | with the hidden display | without it |
|---|---|---|
| EGL mtrack @45 s | 126 316 kB | **77 140 kB** (−49 176, −38.9%) |
| EGL mtrack 45 s after Home | 83 160 kB | 42 180 kB (−40 980, −49.3%) |
| TOTAL PSS @45 s | 1 405 254 kB | 1 355 270 kB (−49 984, −3.6%) |
| threads @45 s | 57 | **54** (−3) |
| GL mtrack @45 s | 450 804 kB | 448 936 kB (−1 868, noise) |
| native heap @45 s | 631 615 kB | 633 960 kB (+2 345, noise) |
| `HiddenDisplay` in `dumpsys display` | display 8, 1920x1080 | 0 matches |
| foreground CPU top threads | `VulkanPresent` appears **twice** (2.98% + 2.48%) | one `VulkanPresent` row (4.02%) |

So: **about 49 MB of EGL memory and 3 threads**, on a device that has no second screen and has the second screen turned off. GL mtrack, native heap and VmRSS are all unchanged within noise, which is exactly the shape of removing a 1080p swapchain and its present path and nothing else. The `dumpsys display` rows confirm it directly rather than by inference.

I could not isolate a frame-time win from this on my phone — that pass is cheap on an Adreno 740 with headroom — but it is a whole extra submit and present per vblank on the emulation thread, and on weaker hardware it will not be free.

## Root cause

**The display is created in a constructor, unconditionally.** `src/android/app/src/main/java/org/citra/citra_emu/display/SecondaryDisplay.kt:33-43`:

```kotlin
init {
    vd = displayManager.createVirtualDisplay(
        "HiddenDisplay", 1920, 1080, 320, null,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    )
    displayManager.registerDisplayListener(this, null)
}
```

**The setting only picks a target, it does not turn anything off.** `SecondaryDisplay.kt:88-95`:

```kotlin
val displayToUse = if (availableDisplays.isEmpty() ||
    IntSetting.SECONDARY_DISPLAY_LAYOUT.int == SecondaryDisplayLayout.NONE.int ||
    !BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean
) {
    currentDisplayId = -1
    vd.display          // <- the hidden display
} else { ... }
```

Turning the setting off does not stop the `Presentation`. It routes it to `HiddenDisplay`. The `getSecondaryDisplays()` filter at `SecondaryDisplay.kt:76` even has to exclude `it.name != "HiddenDisplay"` so the app does not find its own phantom display and use it as the real second screen.

**The renderer then treats the window's existence as "there is a second display".** `src/video_core/renderer_vulkan/renderer_vulkan.cpp:1138-1144` and `:1165-1176`:

```cpp
#ifdef ANDROID
    if (secondary_window) {
        secondaryWindowEnabled = true;
    } else {
        secondaryWindowEnabled = false;
    }
#endif
...
#ifdef ANDROID
    if (secondary_window) {
        const auto& secondary_layout = secondary_window->GetFramebufferLayout();
        if (!secondary_present_window_ptr) {
            secondary_present_window_ptr = std::make_unique<PresentWindow>(...);
        }
        isSecondaryWindow = true;
        RenderToWindow(*secondary_present_window_ptr, secondary_layout, false);
        secondary_window->PollEvents();
    }
#endif
```

The Android frontend creates that `secondary_window` unconditionally, so the test is always true, so the second pass always runs. (The desktop path immediately above it, `:1129-1136` and `:1151-1163`, tests `layout_option == SeparateWindows` instead, which is the right question.)

`RendererOpenGL` has the same shape.

## How I fixed it in my fork

Three commits, in prose:

**Make "no surface" a supported state for the secondary window.** `EmuWindow_Android::window_info.render_surface` was only filled in by `OnSurfaceChanged()` — the GL window never set it at all — so I set it in the base constructor, making it an accurate answer to "does this window have a native surface behind it" from the moment the object exists. Along with that, the GL window had to stop creating its EGL context *after* querying a window surface for its size, because a surfaceless window ended up with a null `core_context` that `MakeCurrent()`/`DoneCurrent()` dereference unconditionally.
https://github.com/martyfuhry/azahar/commit/3a5aa17e7

**Test the surface, not the window pointer.** Both renderers now decide whether to run the secondary pass from whether the secondary window has a surface, and `RendererVulkan::SwapBuffers()` builds the secondary `PresentWindow` (swapchain, images, command pool, frames) when a surface appears and releases it when it goes away. The `EmuWindow` object itself has to stay alive — the emulation thread dereferences `RendererBase::secondary_window` every frame without a lock, so destroying and recreating it from the UI thread would be a use-after-free. What comes and goes is the surface behind it. One subtlety worth flagging if anyone implements this: `secondaryWindowEnabled` is also what decides which pass clears `Core::PerfStats::game_frames_updated`, so gating only the render call and not that flag would silently disable `use_skip_duplicate_frames`.
https://github.com/martyfuhry/azahar/commit/d66de38f4

**Stop creating the hidden display at all.** No `VirtualDisplay`, no `Presentation` when there is no real second display to put one on. Hot-plug still works in both directions with no restart: turning the setting on, or plugging a display in, runs `updateDisplay()` → `Presentation` → `surfaceChanged` → `secondarySurfaceChanged`, which hands the surface to the long-lived secondary `EmuWindow`, and the next `SwapBuffers()` sees it and builds the present window. The `"HiddenDisplay"` name filter in `getSecondaryDisplays()` goes away with the display it was filtering. The Thor's real dual-screen path is untouched — its built-in second panel is a real display, so the `Presentation` is created exactly as before.
https://github.com/martyfuhry/azahar/commit/d2dccef65

That is three commits across the frontend and both renderers, well past what I can offer as code under your AI policy. No diff attached.

## Marty must verify before filing

- [ ] Reproduce on a **single-screen phone** with an **official Azahar build** from the release page — that is the case that makes this a bug for everybody rather than a Thor curiosity. `dumpsys display | grep HiddenDisplay` with the secondary display setting off is the whole repro and takes a minute.
- [ ] Take my own `dumpsys meminfo` EGL mtrack reading on that official build, before and after toggling the setting, so I can say honestly whether the setting changes anything (my measurement says it does not).
- [ ] Reproduce on the **Thor** and confirm that the Thor's real second panel is *not* the hidden display — i.e. that on the Thor the cost is legitimate and this report is about single-screen devices.
- [ ] Confirm the two `VulkanPresent` threads myself in `/proc/<pid>/task/*/comm` on an unmodified build. That is the cheapest single piece of evidence in the whole report.
- [ ] Do not quote the 49 MB figure as a Thor number. It is a Fold5 number from my own builds.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to trace the secondary-display path from the Kotlin frontend into both renderers. I took the display dumps and the memory measurements myself.*
