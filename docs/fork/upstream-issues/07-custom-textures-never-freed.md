# A decoded custom texture is never freed, so memory converges on the pack's full decoded size — and the preload budget overshoots by one texture

**Shape:** new issue. The fix is an eviction policy, which is far past anything I can write for you under your AI policy, so this is a report and nothing else.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "custom textures memory"`, `"texture pack unload free memory"`, `"preload custom textures budget"`. Nothing filed for the lifetime problem. #563 ("improve the excessive RAM consumption", closed) is adjacent but general. The crash reports — #1308, #2118, #2116, #761, #1439 — are a different failure mode; I have a separate comment drafted for those.

---

## Environment

- Azahar: upstream `master` at `073110cb4` (2026-09-07). `src/video_core/custom_textures/` is six files and I read all of them.
- Devices: AYN Thor, Android 13, Snapdragon 8 Gen 2, 8 GB and 12 GB SKUs (the Thor Max in #2118 is 16 GB). Also relevant to any Android handheld.

## Reproduction

Not a crash, so the "reproduction" is a memory curve:

1. Install a large texture pack for a title — Henriko Magnifico's OoT3D/MM3D packs are the ones people actually use, and are what the crash reports above are about.
2. **Custom textures ON, preload OFF.**
3. Launch the title and play through several distinct areas, so the game touches many different textures.
4. Sample resident memory as you go:

```
adb shell dumpsys meminfo org.azahar_emu.azahar   # Native Heap
```

5. Note that it only ever goes up. Walk back to an area you have already left; nothing is released.

## Expected

Memory tracks the working set. Textures the game has stopped using are eventually released, the way the guest texture cache's garbage collector releases surfaces.

## Actual

Nothing is ever released. There is no unload path anywhere in `src/video_core/custom_textures/`. A decoded custom texture is resident for the life of the process, so on a long enough session memory climbs toward the pack's **entire decoded size**.

That number is the point of this report. Azahar decodes every custom texture to RGBA8, so the decoded size is not the size of the zip on disk:

- a 4K-tier pack: **about 29 GiB decoded**
- the 1080p tier of the same pack: **about 7.2 GiB decoded**

Against 8–16 GB of total system RAM on the handhelds this matters on, with an emulator that is already sitting at 1.1–1.8 GiB at 3–4x internal resolution before any pack is loaded. That is not "expensive". That is a ceiling the process cannot survive reaching, and it is why texture packs are effectively unusable on Android — the app does not have a memory problem, it has an unbounded-growth problem that happens to be slow enough that people describe it as random crashes after a while.

I want to be honest about the shape of my evidence: **the 29 GiB and 7.2 GiB figures are computed from the packs' contents and Azahar's RGBA8 decode, not measured on a device.** What I verified by reading the code is the part that makes them matter — that nothing ever gives any of it back.

## Root cause

`Material` and `CustomTexture` have a decode path and no counterpart.

`src/video_core/custom_textures/material.h:64` — the payload:

```cpp
std::vector<u8> data;
```

`material.h:68-100` — `Material` has `LoadFromDisk`, `AddMapTexture`, `Map`, and four state predicates (`IsPending`, `IsFailed`, `IsDecoded`, `IsUnloaded`). There is no `Unload`, no `Release`, no `Free`. `material.cpp:99-141` — `Material::LoadFromDisk` fills `data` and sets `state = DecodeState::Decoded`. Nothing ever moves a `Material` back to `DecodeState::None`; `IsUnloaded()` at `material.h:97-99` only ever answers true for a texture that was never loaded in the first place, and its single caller is `custom_tex_manager.cpp:304`, which uses it to decide whether to *start* a load.

Grepping the whole directory for `clear()`, `reset()`, `free`, `Unload` or `shrink_to_fit` returns those two lines and nothing else.

The guest texture cache has a deferred-deletion garbage collector. The custom texture cache has nothing — no budget, no LRU, no eviction, no high-water mark. It is a load-only cache.

## The preload budget overshoots, as part of the same problem

`src/video_core/custom_textures/custom_tex_manager.cpp:207-236`:

```cpp
const u64 sys_mem = Common::GetMemInfo().total_physical_memory;
const u64 recommended_min_mem = 2_GiB;
const u64 max_mem =
    (sys_mem / 2 < recommended_min_mem) ? (sys_mem / 2) : (sys_mem - recommended_min_mem);

workers->QueueWork([&]() {
    for (auto& [hash, material] : material_map) {
        if (size_sum > max_mem) {
            LOG_WARNING(Render, "Aborting texture preload due to insufficient memory");
            return;
        }
        ...
        material->LoadFromDisk(flip_png_files);
        size_sum += material->size;
        ...
    }
});
```

Two things:

1. **The check is after the add.** `size_sum > max_mem` is tested at the top of the iteration, then the material is loaded and its size added. So the budget can be exceeded by the size of one whole material before the loop notices — up to 256 MiB in the worst case I have seen in a real pack. On a device that is already near its limit, that one texture is the difference between an abort of the preload and an abort of the process.

2. **The budget is computed from `total_physical_memory`.** On an 8 GB device, `sys_mem - 2 GiB` is a 6 GiB preload budget, decided without reference to how much memory is actually available, how much the emulator already holds, or what else is running. On Android that is not a budget, it is a guarantee of a low-memory kill. Given the lifetime problem above, "Preload custom textures" on an Android handheld is a switch that walks the process off a cliff on purpose, and the community advice on every pack readme is already "turn preload off" — which is a workaround for this.

I would treat the overshoot as a footnote to the lifetime problem rather than a separate issue, because fixing the ordering without fixing the lifetime just moves where you run out.

## How I fixed it in my fork

**I did not.** The fix is an eviction policy for the custom-texture cache — a budget, an LRU, and a release path on `Material`, plus deciding what happens when a texture that has been evicted is needed again mid-frame. That is a design change, it is far past the five-line snippet allowance in your AI policy, and I am not going to hand you a large AI-assisted patch and ask you to take it.

My fork ships with custom textures off and documents "do not turn preload on", which is not a fix, it is a retreat.

The one thing that *is* a small change is the preload ordering: check the budget against `size_sum + material->size` before loading rather than `size_sum` after. That is a couple of lines and I would be happy to send it as a disclosed PR if a maintainer thinks it is worth having on its own.

## Marty must verify before filing

- [ ] **Measure the growth curve myself, on the Thor**, with an official Azahar build and a real pack. Play through several areas with custom textures on and preload off, sampling `dumpsys meminfo` at intervals, and produce an actual monotonic curve. Right now the strongest claim I have is "I read the code and there is no free path", and that is a much weaker report than "here is Native Heap climbing 200 MB per area and never coming back".
- [ ] Get a real decoded-size number for at least one pack rather than a computed one — decode a sample of the pack offline and extrapolate, or better, load enough of one on the device to show the trend and state the extrapolation as an extrapolation. **Do not present 29 GiB as measured.**
- [ ] Confirm the preload budget arithmetic on the Thor by turning preload on deliberately once, in a throwaway session, and reading the log for `Aborting texture preload due to insufficient memory` versus a kill. Then turn it back off.
- [ ] Check `dumpsys activity exit-info` after that session so the report can say which kill reason a preload run actually produces on an 8 GB device.
- [ ] Decide whether the preload overshoot goes in this issue or a separate small PR. Probably: report both here, offer the ordering fix as a disclosed PR only if asked.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to audit the custom-texture cache for a release path and to work out the decoded sizes. The lifetime finding is from reading the source; anything I present as a measurement, I will measure myself first.*
