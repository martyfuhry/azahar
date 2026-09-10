# The new zero-area renderpass cull tests height twice, so a zero-**width** draw rect still gets through

**Shape:** comment on the merged PR, [#2510](https://github.com/azahar-emu/azahar/pull/2510) — or a
one-line PR, which is what it really wants to be. Wunkolo merged it two days ago; nobody has filed
anything about it yet.

**Searched first:** `gh pr view 2510 -R azahar-emu/azahar` (no review comments at all),
`gh api repos/azahar-emu/azahar/commits?path=src/video_core/renderer_vulkan/vk_rasterizer.cpp`
(`d260a99e2` is the newest commit on the file), and the open PR list for anything touching
`vk_rasterizer.cpp`. Nothing filed, nothing pending.

**Confidence: source-evident.** This is a typo, visible in the diff, with no device needed to see
it. What is *not* established is how often the escaping case actually occurs — see the last
section.

---

## The line

`d260a99e2` ("renderer_vulkan: Skip zero-area renderpasses (#2510)"), in
`RasterizerVulkan::Draw`:

```cpp
const auto draw_rect = fb_helper.DrawRect();
if (draw_rect.GetHeight() * draw_rect.GetHeight() == 0) {
    return true;
}
```

`GetHeight()` appears twice. `Common::Rectangle` has both accessors
(`src/common/math_util.h:55,58`), and the commit message says the intent is a zero *area*:

> The intersection between the viewport and the surface-rect can sometimes not intersect at all,
> leading to zero-area render-passes.

So the predicate should be `draw_rect.GetWidth() * draw_rect.GetHeight() == 0`.

## What it costs

As written the guard catches exactly the zero-height half of the case it was written for. A draw
rect that is zero pixels *wide* and non-zero tall — the viewport and the surface rect failing to
overlap horizontally rather than vertically — passes the check and goes on to
`renderpass_cache.BeginRendering(framebuffer, draw_rect)` with a `renderArea` of width 0, which is
the exact thing the PR set out to stop.

Whether that half occurs in 3D Thunder Blade, the title the PR was written against, I have not
tested. It is a coin-flip which axis degenerates first for any given title, and the fix costs one
token, so the question is probably not worth answering before fixing it.

## Why this fork cares more than most

We are chasing an unfixed Adreno crash (draft `02-texture-filter-crash-mid-game.md`) whose
signature is a SIGSEGV inside `qglinternal::vkCmdEndRenderPass`, on a command stream where we have
instrumented and *proved* that

- every `vkCmdEndRenderPass` had a matching `vkCmdBeginRenderPass` recorded into the same command
  buffer, and
- the `VkFramebuffer` named at begin time was alive at the moment it was recorded.

In other words the driver has no active render pass at end time even though we recorded a begin for
it. A degenerate `renderArea` is one of the few remaining ways that can happen: a tiler with no bins
to allocate may treat the begin as a no-op and never set its internal current-pass pointer, and then
faults on the end. **We do not know that this is our crash** — see draft 02 for how many hypotheses
have already died — but #2510 is the first upstream change in a year that plausibly touches it, and
the half-fix means a naive "did #2510 fix it?" experiment would only be half an experiment.

That is the practical ask: whoever re-tests #2510 against a crash of this shape should test the
corrected predicate, not the merged one.

## The fix

One token, in `src/video_core/renderer_vulkan/vk_rasterizer.cpp`:

```cpp
if (draw_rect.GetWidth() * draw_rect.GetHeight() == 0) {
```

Quoted inline under the AI policy's snippet allowance, as draft 06 does; it is a corrected copy of
Wunkolo's own line rather than new design.

## Marty must verify before filing

- [ ] Open `src/video_core/renderer_vulkan/vk_rasterizer.cpp` at `d260a99e2` and read the line
      yourself. That is the whole verification; everything above follows from it.
- [ ] Re-check that upstream has not already fixed it —
      `gh api "repos/azahar-emu/azahar/commits?path=src/video_core/renderer_vulkan/vk_rasterizer.cpp&per_page=5"`.
      As of 2026-09-10 `d260a99e2` was still the newest commit on the file.
- [ ] Decide the shape. A comment on #2510 is polite and zero-risk; a one-line PR is more useful and
      is small enough that the AI policy's disclosure line covers it. Either way the disclosure
      belongs in it.
- [ ] Do **not** repeat the texture-filter connection as a claim. It is a hypothesis, it is
      unverified, and draft 02 says so at length. One sentence of "we have a crash of this shape and
      would like the predicate to be right before we test against it" is the most that is honest.
