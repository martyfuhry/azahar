# Three Vulkan swapchain lifetime bugs reachable from an Android suspend/resume: double-destroyed semaphores, a leaked `VkSurfaceKHR` per recreation, and `UNREACHABLE()` on any unexpected result

**Shape:** one issue with three parts, because they are all in `vk_swapchain.cpp`'s lifetime handling and all reachable from the same sleep/wake cycle. Happy to split into three if a maintainer prefers — say the word and I will.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "swapchain semaphore"`, `"surface leak vulkan"`, `"device lost"`. Nothing filed for any of the three as such. Related and worth linking: **#2455** (`ErrorNativeWindowInUseKHR` → `UNREACHABLE()`, closed, fixed in 2126.1 — but the fix was frontend guards in `3c6a44017`, and the `UNREACHABLE()` it tripped over is still there) and **#1693** (crash spamming fullscreen toggle, traced by the reporter to `UNREACHABLE()` in `vk_swapchain.cpp`, closed as cannot-reproduce, and PabloMK7's comment on it — "a known issue with quickly recreating vulkan surfaces… affects mostly Android" — is describing this same area).

---

## Environment

- Azahar: upstream `master` at `073110cb4` (2026-09-07). All three are present at the line numbers below; I read them out of `git show master:` to be sure.
- Test device: Samsung Galaxy Z Fold5 (SM-F946U1), Android 16 (API 36), Snapdragon 8 Gen 2, Adreno 740, stock Qualcomm driver **512.676.1**.
- Intended target: AYN Thor (`ro.product.model=AYN Thor`), Android 13 (API 33), `ro.soc.model=QCS8550`, Adreno 740 on driver **512.676.53** — a newer driver build than the one this was reasoned against, which matters because every claim here is about driver behaviour. Dual screen — where the surface is replaced on every lid cycle and every display change, so all three of these paths run constantly.

## Honesty up front

**I have not reproduced any of these three as a crash on demand.** They came out of reading the resume path after chasing native aborts on wake, and I fixed them because each is provably wrong on inspection. I am filing them as code defects with an explained trigger, not as "here is a crash you can reproduce in five minutes". If that is not the kind of report you want, close it and I will not be offended — but I would not want a maintainer to lose an afternoon to number 1 the way I nearly did.

---

## 1. `Swapchain::Destroy()` destroys semaphores by a stale count

`src/video_core/renderer_vulkan/vk_swapchain.cpp:261-273`:

```cpp
void Swapchain::Destroy() {
    vk::Device device = instance.GetDevice();
    if (swapchain) {
        device.destroySwapchainKHR(swapchain);
        swapchain = VK_NULL_HANDLE;
    }
    for (u32 i = 0; i < image_count; i++) {
        device.destroySemaphore(image_acquired[i]);
        device.destroySemaphore(present_ready[i]);
    }
    image_acquired.clear();
    present_ready.clear();
}
```

It loops to `image_count`, then clears the vectors, and never resets `image_count`.

`Swapchain::Create()` (`vk_swapchain.cpp:31-48`) begins with `Destroy()` and can then bail out early before `SetupImages()` refills those vectors:

```cpp
Destroy();

SetPresentMode();
if (needs_recreation) {
    return;
}

SetSurfaceProperties();
if (needs_recreation) {
    return;
}
```

`needs_recreation` is set by `VK_ERROR_SURFACE_LOST_KHR` from `SetPresentMode`, `SetSurfaceProperties` or `createSwapchainKHR`. Android produces exactly that when the app is slept or rotated while the previous surface is still being replaced.

After such a bail-out, `image_count` still holds the old generation's value while both vectors are empty. The next `Create()` calls `Destroy()` again, indexes empty vectors out of bounds, and passes the previous generation's **already destroyed** semaphore handles to `vkDestroySemaphore`. On Android that is an abort on resume, reported as `REASON_CRASH_NATIVE`.

The trigger is a second sleep or rotation arriving while the resumed surface is still being recreated. On a Thor that is a lid closed and reopened quickly; on a phone it is a rotation during a wake.

This one was introduced by upstream `7e58ac5bc`.

**How I fixed it:** iterate the vectors themselves rather than a count, so `Destroy()` is idempotent no matter how far the previous `Create()` got. I also reset `frame_index` and `image_index` to 0 on every generation, because the new swapchain's image count is not guaranteed to match the old one's and those indices select the semaphore for the next acquire before any image has been acquired.
https://github.com/martyfuhry/azahar/commit/e869a5411

---

## 2. `VkSurfaceKHR` is leaked on every swapchain recreation, and the present window never forgets a destroyed window

`src/video_core/renderer_vulkan/vk_swapchain.cpp:31-35`:

```cpp
void Swapchain::Create(u32 width_, u32 height_, vk::SurfaceKHR surface_, bool low_refresh_rate_) {
    width = width_;
    height = height_;
    surface = surface_;
    ...
```

The handle is overwritten. The only `destroySurfaceKHR` is in the destructor, `vk_swapchain.cpp:26-29`, so only the last surface a `Swapchain` ever held is destroyed.

Desktop frontends keep one surface for the life of the window and never notice. Android delivers a new `Surface` — and a new `ANativeWindow` — on every rotation and on every return from the background, so each of those cycles leaks one `VkSurfaceKHR` along with the reference it holds on a window the OS has already torn down.

There is a second half to this, and it is the reason I am reporting them together. `PresentWindow` remembers the last `ANativeWindow` it wrapped so that repeated `surfaceChanged()` calls for the same window do not create a second surface and hit `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` — `vk_present_window.cpp:112` initialises `last_render_surface`, and `:356-359` is the dedupe:

```cpp
if (render_surface == last_render_surface) {
    return;
}
last_render_surface = render_surface;
```

`surfaceDestroyed()` never clears that memory. Today the leak is what keeps the allocator from ever handing back the same address, so the dedupe accidentally works. **Fix the leak on its own and you get a black screen after resume**: a recycled address is mistaken for the old window, its surface is never created, and the present thread waits for it forever.

So they have to be fixed together, and I would want that written down in the issue so nobody takes only the obvious half.

**How I fixed it:** destroy the old surface after the swapchain that used it is gone and right before adopting the new one; and make `surfaceDestroyed()` take the same path as `surfaceChanged()` — the `EmuWindow` forgets its `render_surface` and the renderer is notified with a null window, which makes the present window forget it and keep waiting for the next real one. The Android secondary path had the same omission (`secondarySurfaceDestroyed()` released the `ANativeWindow` but left the secondary `EmuWindow` with a dangling `render_window`), so it got the same treatment; on a Thor that path runs on every lid or display change.
https://github.com/martyfuhry/azahar/commit/cedc2f9f1

---

## 3. `UNREACHABLE()` on any unexpected acquire or present result, including `VK_ERROR_DEVICE_LOST`

Acquire, `vk_swapchain.cpp:104-116`:

```cpp
switch (result) {
case vk::Result::eSuccess:
    break;
case vk::Result::eSuboptimalKHR:
case vk::Result::eErrorSurfaceLostKHR:
case vk::Result::eErrorOutOfDateKHR:
    needs_recreation = true;
    break;
default:
    LOG_CRITICAL(Render_Vulkan, "Swapchain acquire returned unknown result {}", result);
    UNREACHABLE();
    break;
}
```

Present, `vk_swapchain.cpp:131-142` — same shape: `OutOfDateKHRError` and `SurfaceLostKHRError` are caught, everything else is `UNREACHABLE()`.

Surface creation, `vk_platform.cpp:191-196` and `:200-203` — any failure of `createAndroidSurfaceKHR`, including `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`, is `UNREACHABLE()`. (This is the abort #2455 hit. The 2126.1 fix added frontend guards so the double-create stops happening; the `UNREACHABLE()` underneath it is unchanged, so anything else that makes surface creation fail still takes the process down.)

`FindPresentFormat`, `vk_swapchain.cpp:147` onwards, is called from the `Swapchain` constructor at `:21` and does not guard against a lost surface. A surface lost there throws `vk::SurfaceLostKHRError` out of `System::Init` — during boot or during the renderer rebuild a savestate load performs — and terminates.

Why this matters on Android specifically: the GPU is suspended along with the screen. The Adreno driver may answer the first acquire after a wake with `VK_ERROR_DEVICE_LOST` or `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`. (Stated as driver behaviour in general; neither has been observed on the Thor's 512.676.53, and the Thor's only recorded Azahar crash is the texture-filter race, not a swapchain fault.) Every one of those paths aborts the process, and the user's game goes with it, reported as `REASON_CRASH_NATIVE`. `VK_ERROR_DEVICE_LOST` after a GPU suspend is not an impossible state; it is a Tuesday.

Related, and worth stating even though I did not fix it: `vk_master_semaphore.cpp:154` has its own `UNREACHABLE_MSG("Device lost during submit")` in `MasterSemaphoreFence::SubmitWork`, reached from the emulation thread. A genuine device loss will still abort there regardless of what the swapchain does. That one is a bigger design question — what does the emulator do when the device is gone — and I have no answer for it.

**How I fixed it:** classify failures by what could fix them. Surface-class results (lost, out of date, suboptimal, native window in use, or no surface at all) mark the swapchain for recreation as before, and on Android the present thread waits for the next surface the frontend hands it. Device-class results (device lost, out of memory, anything unknown) also request recreation, but on the surface already held, since waiting for a rotation would never end; a `Swapchain` remembers it is in the device class until a creation succeeds, so a lost device is not mistaken for a surface change. Recreation attempts on one surface are capped at 8, and the fence wait in `GetRenderFrame()` — which used to spin forever on any result that was not a timeout or Mali's EINTR artefact — is capped too, so a dead device can no longer pin the emulation thread at 100% of a core. When the cap is reached the window is marked lost and the renderer raises a core error once; the frontends already turn that into the error dialog, so the user can stop the game and still write a save, because the guest state is intact. `CreateSurface()` returns a null handle instead of aborting, and a null surface is tolerated at construction so a window destroyed between `surfaceChanged()` and the renderer being built defers the first swapchain rather than throwing out of `System::Init`.
https://github.com/martyfuhry/azahar/commit/106994756

That last one is a substantial change across five files and is emphatically not something I can offer as code under your AI policy. The first two are small; even so I am not attaching diffs, because the semaphore fix and the surface-leak fix each have a detail (the index reset, and the `last_render_surface` pairing) that matters more than the lines do.

## Marty must verify before filing

- [ ] Decide honestly whether to file this at all in this form. Three code defects with no reproduction is a weaker report than the others in this batch. If I cannot stand behind at least one of them with a crash I caused on purpose, consider filing only #1 and #3 and dropping #2 to a comment on #1693.
- [ ] Try to force **#1** on the Thor: sleep or rotate during the recreation window right after a wake, repeatedly, on an official build, and see if I can get a `vkDestroySemaphore` abort in a tombstone. A lid-cycle soak with a short dwell is the shape.
- [ ] Try to force **#3** on the Thor: repeated screen-off/screen-on with the GPU suspended, looking for `REASON_CRASH_NATIVE` and an `UNREACHABLE` in `vk_swapchain.cpp` or `vk_platform.cpp` in the tombstone. My rc1 notes claim this happens; I have never captured it myself.
- [ ] Re-read `vk_swapchain.cpp` on whatever the current `master` is **on the day I file**, and re-confirm every line number. This file has moved recently and a stale line number makes the whole report look careless.
- [ ] For **#2**, be explicit in the issue that fixing the leak alone regresses to a black screen unless `last_render_surface` is cleared. That warning is the most useful sentence in this report.
- [ ] Check whether #1693 should be reopened rather than a new issue opened, given PabloMK7's comment there.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to read the swapchain and present-window lifetime and to reason about which Android events reach which bail-out path. I have not reproduced these three as crashes on demand, which I have said plainly above; anything I do reproduce before filing, I will reproduce myself.*
