# Comment, not a new issue: custom textures crashing under Vulkan (#1308 / #2118)

**What I found upstream.**

- **#1308** — "MM3D and OOT3D crash on boot/bad texture when using Vulkan with custom textures", **closed as stale** 2026-03-26 with the reporter's last word being "still broken, only works at native resolution". Desktop Windows, Henriko's 4K pack.
- **#2118** — "[Android] Custom Textures Crashing Azahar", **open**, labelled `bug`, filed from an **AYN Thor Max with 16 GB RAM and Turnip**. Eight comments, reports from Steam Deck, Windows desktop, and Android. tamder's comment pins the regression to 2125.1.1 and later, and notes the crash happens "when a texture is done being used". demetriospantaleao-del found and posted a root cause for the **OpenGL** half in #2116 (a surface losing its `Registered` flag when replaced by a custom texture) and said on 2026-08-29 they were going to PR it.
- **#2116** — the OpenGL sibling, closed, with that analysis in it.

So the OpenGL half has an owner and a diagnosis. The Vulkan half does not, and #2118 is the live thread. **Post on #2118**, and a short pointer on #1308 saying the Vulkan half is being tracked in #2118 (optional; only if #1308 gets reopened).

**Important:** I have **not** reproduced these crashes. Do not write the comment as if I have. What I can contribute is a memory-lifetime fact that I think reframes the Android reports, and an explicit statement that the OpenGL fix does not cover Vulkan.

---

## Draft comment for #2118

> I have not reproduced these crashes myself, so treat this as context rather than a diagnosis. Two things I found while auditing the custom-texture code for an unrelated reason that I think are relevant to the Android reports in particular.
>
> **A decoded custom texture is never freed.** There is no unload path anywhere in `src/video_core/custom_textures/`. `CustomTexture::data` is a `std::vector<u8>` (`material.h:64`) filled by `Material::LoadFromDisk` (`material.cpp:99-141`), which sets `state = DecodeState::Decoded` and never goes back. `Material` has `IsPending`/`IsFailed`/`IsDecoded`/`IsUnloaded` and no `Unload`; `IsUnloaded()` only ever answers true for something that was never loaded, and its one caller uses it to decide whether to *start* a load. Grep the directory for `clear()`, `reset()`, `free` or `shrink_to_fit` and you get nothing. So every custom texture the game touches stays resident for the life of the process and memory only ever climbs toward the pack's full **decoded** size — Azahar decodes to RGBA8, so for the 4K tier of Henriko's packs that is on the order of 29 GiB, and about 7.2 GiB for the 1080p tier. (Those are computed from the pack contents, not measured on a device, so weight them accordingly — but the "never freed" part is just what the code does.)
>
> That is why I would be cautious about reading the Android reports here as purely a renderer bug. On a 16 GB Thor Max, with the emulator already at 1.1–1.8 GiB at 3–4x internal resolution before any pack loads, an unbounded custom-texture cache reaches a wall eventually, and where it lands will look different depending on what the allocator does at that moment. It would be worth someone with a repro checking `adb shell dumpsys activity exit-info <package>` right after a crash: if the reason is `LOW_MEMORY` or `OTHER` with a cached-memory subreason rather than `CRASH_NATIVE`, that is a different bug from the one #2116 identified and it will not be fixed by fixing that one. tamder's observation that it crashes "when a texture is done being used" also points at lifetime rather than at decode.
>
> **The OpenGL fix does not cover Vulkan.** The analysis demetriospantaleao-del posted in #2116 is about `Registered` being lost when an OpenGL surface is replaced by a custom texture, and the fix lives in the OpenGL custom-surface path. Nothing equivalent exists on the Vulkan side, so the Vulkan reports in this thread — which is most of them, including the original Thor Max report and #1308 — should not be assumed to close when that PR lands. Worth keeping this issue open past that point.
>
> One more thing for anyone triaging: `CustomTexManager::PreloadTextures` (`custom_tex_manager.cpp:207-236`) computes its budget from `total_physical_memory - 2 GiB` — 6 GiB on an 8 GB device, decided without reference to available memory or to what the emulator already holds — and checks `size_sum > max_mem` at the *top* of the loop, before adding the material it is about to load, so it can overshoot by one whole material. Everyone's advice is already "turn Preload Custom Textures off", and that is why.
>
> I have not fixed any of this. The lifetime problem needs an eviction policy for the custom-texture cache, which is a design change rather than a patch, and I did not want to sit on the finding while I failed to write one.

---

## Marty must verify before posting

- [ ] Re-read `src/video_core/custom_textures/` on current `master` on the day I post and re-confirm every line number and every claim about what is and is not there. This comment is entirely a source-reading claim; if one detail is wrong the whole thing is worthless.
- [ ] Be explicit in the posted comment that I have **not** reproduced the crash. Do not let the comment read as a diagnosis of their crash.
- [ ] Decide whether to include the 29 GiB / 7.2 GiB figures at all. They are computed, not measured. If included, label them as computed, in the same sentence.
- [ ] Optionally: install a pack on the Thor once and confirm the monotonic memory growth with `dumpsys meminfo`, which would upgrade this comment from a source reading to an observation. That is the same work item as the standalone custom-textures issue, so do it once and use it for both.
- [ ] Check whether demetriospantaleao-del's PR has landed before posting — if it has, the "does not cover Vulkan" point becomes more useful, not less, but the wording should change.

---

*Disclosure line to include at the end of the posted comment:*

> Disclosure: I used an AI assistant to audit the custom-texture code for this. I have not reproduced the crashes in this thread and I have said so above; the source claims are ones I checked myself.
