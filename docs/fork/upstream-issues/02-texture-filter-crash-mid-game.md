# Changing the texture filter while a game is running crashes on Adreno — 12 crashes in 20 cold boots

**Shape:** new issue. I did not fix this.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "texture filter crash"`, `"pipeline cache"`, `"custom textures memory"`, `"swapchain semaphore"`. The near misses are #1635 (texture *corruption* with Vulkan + filter, fixed by #1634), #1852 (Vulkan crashes at 6x internal resolution, closed, but Wunkolo's comment on it is about `RunGarbageCollector` deleting surfaces that are still in flight, which is the same family of problem) and #1693 (closed, cannot reproduce). None of them is this. Nothing filed for "change the filter mid-game and it dies".

---

## Environment

- Azahar: the crash reproduces on upstream `master` at `073110cb4` (2026-09-07). My measured baseline is my own commit `b8aa5f893`, which contains `073110cb4` and has **zero** changes under `src/video_core/` — I checked with `git log 073110cb4..b8aa5f893 -- src/video_core/`, which is empty. So the renderer under test is upstream's, unmodified, including the surface-recycling work that landed 2026-09-03 (`d216f961a`, `ab89916d1`, `aaa3d3032`, `c2783e110`, `2ef875ac1`).
- Test device: Samsung Galaxy Z Fold5 (SM-F946U1), Android 16 (API 36), Snapdragon 8 Gen 2, **Adreno 740, stock Qualcomm driver 512.676.1**.
- Intended target: AYN Thor (`ro.product.model=AYN Thor`), Android 13 (API 33), `ro.soc.model=QCS8550`, **Adreno 740 on driver 512.676.53** — same GPU, a newer driver build than the one the counts below were taken on. Worth stating in the issue body, since three of the four failure signatures are inside the driver.
- Game: Animal Crossing: New Leaf, Vulkan, `resolution_factor = 3`.

## Reproduction

1. Set `resolution_factor = 3` and `texture_filter = 0` (None) in `config.ini`. Vulkan renderer.
2. Cold boot the title. Wait until you are actually in gameplay — about 28 seconds on my device.
3. Open the in-game menu and set **Settings > Graphics > Texture Filter** to any filter.
4. Wait about 10 seconds.

I drove this over adb and uiautomator to get a clean count rather than eyeballing it.

## Expected

Changing the texture filter mid-game rescales the texture cache and carries on.

## Actual

The process dies. Two different signatures, from the same trigger, in the same set of runs:

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xb4
in tid N (VulkanWorker)
  #02 vulkan.adreno.so qglinternal::vkCmdEndRenderPass
  #03 libcitra-android.so Vulkan::RenderManager::EndRendering()::$_0
  #04 libcitra-android.so Vulkan::Scheduler::WorkerThread
```

```
Fatal signal 5 (SIGTRAP), TRAP_BRKPT in tid N (VulkanWorker)
Abort message: Debug <Critical>
  video_core/renderer_vulkan/vk_master_semaphore.cpp:operator():154:
  Unreachable code!
```

The second one is `UNREACHABLE_MSG("Device lost during submit")` in `MasterSemaphoreFence::SubmitWork` — `vkQueueSubmit` returning `VK_ERROR_DEVICE_LOST`. The first is a null active-render-pass dereference *inside the driver* while recording `vkCmdEndRenderPass`.

A third signature turned up in a separate sweep of resolution/filter combinations on the same device: at `resolution_factor = 4` with Bicubic, a native crash in `qglinternal::vkCreateGraphicsPipelines` reached from `Vulkan::GraphicsPipeline::Build`, taking a `pthread_mutex_lock` on what looks like a destroyed mutex.

Three driver entry points failing on one trigger is what driver-side corruption looks like, not one bad call.

### Counts

20 cold boots each, cycling the five filters:

| build | crashes |
|---|---|
| upstream renderer (`b8aa5f893`, video_core identical to `073110cb4`) | **12 / 20** |
| booting with the filter already selected, then playing | **0 / 5** |
| switching between two non-None filters mid-game | **0 / 5** |

The transition into a filtered, rescaled cache is what matters, not the filter. Only raising `res_scale` from 1 to 3 for the whole texture cache crashes — which is what `RasterizerCache::TickFrame` → `UnregisterAll` and then `CreateSurface`'s rescale path do when a filter is switched on at 3x.

`dumpsys activity exit-info` reports `APP CRASH(NATIVE) status=11`, not `LOW_MEMORY`, at the same footprint the process has on the runs that survive.

### It happens on the AYN Thor too, on a different driver

Everything above is the lab phone. A read-only snapshot of the Thor on 2026-09-09 (`docs/fork/baselines/2026-09-09-thor-device-snapshot.md`) read that device's own `dumpsys activity exit-info`. For `org.azahar_emu.azahar.thor` there is exactly one record:

```
2026-09-08 21:11:37  reason=5 APP CRASH (NATIVE)  status=11
```

That is the session in which Marty changed the texture filter mid-game and the app died — he reported it at the time, independently of any harness. Same failure class as the counts above, on the real target hardware, on Adreno driver **512.676.53** rather than the Fold5's 512.676.1.

**What this is not:** a backtrace. No tombstone or logcat was captured for it, so it cannot be shown to be the same *signature* — `vkCmdEndRenderPass`, the device-lost `UNREACHABLE`, or the pipeline-create crash — only the same reason code and status. It is corroboration that the crash happens on real hardware and is not an artifact of the lab phone or its driver; it is not a second data point in the 12/20 count, and it must not be quoted as one. Getting the stack for a Thor reproduction is the single most valuable thing still missing from this report.

## What I ruled out

I want to save whoever picks this up the time I spent:

- **Not unbalanced begin/end on our side.** I instrumented `EndRendering` to record `vkCmdEndRenderPass` only when a `vkCmdBeginRenderPass` had been recorded into the same command buffer. The guard never fired, in any run.
- **Not a stale `VkFramebuffer`.** A probe tracking every framebuffer the runtime creates and destroys, checked in `BeginRendering`, never fired — on a run that crashed on its first iteration. The framebuffer handed to `vkCmdBeginRenderPass` existed at the moment it was recorded.
- **Not memory.** See the `exit-info` line above.
- **Not filter-specific.** Anime4K (three textures) and Bicubic (one) both do it.
- **A full GPU drain does not help.** I tried ending the pass, submitting and waiting in `UnregisterAll`, `CreateSurface` and `ScaleUp`. It made the crash *more* frequent, 2/2. Reverted.

## Four real lifetime bugs I found on the way

None of these cured the crash, and I am not claiming any of them is the root cause. They are genuine defects that a maintainer should probably want regardless, and they are the best leads I have:

1. **`RemoveFramebuffers` destroys a `Framebuffer` that `RasterizerVulkan::Draw` is holding a raw pointer to.** `Draw` takes the framebuffer from `GetFramebufferSurfaces` (`FramebufferHelper::fb`), then calls `SyncTextureUnits`, which reaches `CreateSurface` → `RemoveFramebuffers`. `SlotVector::erase` runs the destructor and returns the slot to a free list, so `framebuffer->Handle()` at `vk_rasterizer.cpp:592` reads a destroyed `VkFramebuffer`.
2. **`CopySurface` and `ValidateByReinterpretation` call `Surface::ScaleUp` without `RemoveFramebuffers`.** Only `CreateSurface` does. Every cached framebuffer built from a surface rescaled through those two paths keeps pointing at an image view `ScaleUp` has just destroyed.
3. **`Surface::ScaleUp` destroys the old image, views and framebuffer inline** (`Handle::Create` starts with `Destroy`) and closes the render pass *after* doing so, even though `BlitHelper::FilterPass` leaves one open on the surface's own framebuffer.
4. **`RenderManager::EndRendering` clears only `pass.render_pass`,** leaving `pass.framebuffer` and `pass.render_area` set. Vulkan recycles handle values, so a framebuffer created after the old one was destroyed can compare equal and make `BeginRendering` take its "already open" fast path without recording a begin.

## Where I would look next

The framebuffer handle is live and the command stream is balanced, so the problem is in something a framebuffer handle does not cover:

- **Descriptor sets.** `BlitHelper::FilterPass` commits a descriptor set with `surface.ImageView(ViewType::Sample, Type::Base)` written into it, then leaves a render pass open on that surface's own framebuffer — immediately before the surface can be rescaled and that view destroyed. A stale image view sitting in a submitted descriptor set is exactly the sort of thing that corrupts a driver rather than faulting at the call that caused it. The crash logs carry `vk_resource_pool.cpp:Allocate:173 "Run out of pools, creating new one!"` right before the fault; the filter path churns descriptor pools hard.
- **Attachment image views and images**, as opposed to the framebuffer object. The probe from bug 4 above should be extended to track `VkImageView` and `VkImage` the same way and checked at descriptor-write time.
- **Allocation churn in the filter path.** One filter at a fixed 3x costs about 86 MiB of GPU-tracked memory with no change in render resolution, while going 1x → 4x with no filter is flat at 449 MiB. The 4x + Bicubic pipeline crash lives in the same place.

Whatever the fix turns out to be, confirm it at 4x + Bicubic as well as 3x.

## Cheap mitigation, if the real fix is far off

The crash is only reachable by changing the filter while emulation is running — booting with it already selected never crashed in 5 attempts. Adding `TEXTURE_FILTER` to `NOT_RUNTIME_EDITABLE` in `src/android/app/src/main/java/org/citra/citra_emu/features/settings/model/IntSetting.kt` would grey the setting out during emulation with the existing `setting_not_editable` dialog and make it take effect on the next boot. That is a one-line change and it turns a crash into a restart. I have not written it.

## How I fixed it in my fork

**I did not.** Two separate attempts failed. The four bugs above are on a branch (`fix/texture-filter-crash`) that is unmerged, because they are unverified renderer changes that do not fix the thing they were written for and I am not going to ship them on that basis. I am filing this because it reproduces on an unmodified upstream renderer at a 60% rate and the write-up is most of the work.

## Marty must verify before filing

- [ ] Reproduce it **on the Thor**, by hand, on an official Azahar build from the release page: boot at 3x with no filter, get into gameplay, change the filter, watch it die. Get my own count — even 3 out of 10 is enough to file, but it has to be mine and it has to be on upstream's binary.
- [ ] Pull my own tombstone/logcat from the Thor and paste those backtraces, not the Fold5's. The 2026-09-08 21:11:37 record shows the crash reached the Thor, but no stack was kept for it, so it proves the class and not the signature.
- [ ] State the Thor's driver version (512.676.53) in the body alongside the Fold5's (512.676.1). The counts are from the older driver; if the Thor rate differs sharply, that is itself worth reporting.
- [ ] Confirm the 0/5 "set the filter before launching" result on the Thor too, since that is what the report offers as a workaround and I do not want to promise it if it does not hold on Android 13.
- [ ] Check the affected-build field honestly: state which release I reproduced it on, not the commit my fork sits on.
- [ ] Decide whether to mention the four lifetime bugs in the issue body or hold them back. They are unverified and I did not fix the crash with them; presented as leads, they are useful, presented as findings, they are overclaiming.

---

*Disclosure: this investigation was AI-assisted — I used AI agents to drive the crash-count harness, read the renderer lifetime paths and eliminate hypotheses. I reproduced the crash myself, and the write-up is mine.*
