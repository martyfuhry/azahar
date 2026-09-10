# thor-v1-rc6

Release candidate 6 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## What this is

**rc5 rebased onto upstream Azahar `2126.1` plus the seven commits that have landed since**, with
one of upstream's decisions adopted deliberately rather than by accident. No new fork work.

Previous releases were based on `073110cb4`, one commit short of the `2126.1` release tag.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc6.apk` |
| Built from | `fac2fe6b2` (clean tree) |
| versionName | `thor-v1-rc6-thor` |
| versionCode | `33745609` |
| sha256 | `d02db93b6a2f5f7447fd0fe532fce52fc2227abb12d463d21956ad02ea00ea00` |
| Size | 50 330 215 bytes |
| ABIs | arm64-v8a, x86_64 |
| Upstream base | `97d867d66`, i.e. `2126.1` plus seven commits |

`fac2fe6b2` is this commit before the artifact rows were filled in; the tag is on the amended
commit, as with rc1 through rc5. Unstripped symbols are at
`~/Development/azahar-builds/azahar-symbols-rc6/`, build id
`22ab13fda2468c7e24d1127267b54a608f44806b`, matching the shipped library.

## What upstream changed, and why

### "Skip present duplicate frames" now defaults to **off**, and this build follows

Upstream #2530. This is a **correctness fix, not a performance retreat**, and the feature's name
is misleading: nothing compares frame contents. The skip is gated on
`Core::PerfStats::game_frames_updated`, which `GPU::SetBufferSwap` sets only when the guest swaps
the **top** screen's framebuffer. A title that updates the screen without a top-screen swap never
sets the flag, and Azahar presents nothing at all.

The evidence is upstream #2495: Inazuma Eleven 3's cutscenes played their audio over a black
screen, on both renderers and on both Steam Deck/RADV and Windows/NVIDIA. Unchecking the option
fixed it. The maintainer's own summary was that the feature "causes issues in some games", plural,
and he demoted the option rather than repair the heuristic.

**This device is fully exposed.** The failure is guest-side, so the Adreno is no safer than any
other GPU — and on a dual-panel setup both windows are gated by the same flag, so a skipped frame
skips both.

**The fork's settings repair pass now agrees with upstream** rather than re-enabling it. Until
this merge the fork's opinion (`true`) matched upstream's default, so the entry was inert. Taking
#2530 would have made it *active*, silently re-enabling on every launch something upstream had
just deliberately turned off. The fork's case for `true` is a real saving — a render and a present
every second vblank in a 30 fps title, roughly doubled here because both panels are driven — but
it has never been measured on this device; the fork's own optimisation of that path still ships
with "numbers filled in below once measured" in its commit body. An unmeasured saving does not
outweigh a black cutscene, which reads as a broken game rather than as a setting.

You can still turn it on for a specific title. Tier 2 then cedes the key to you permanently.

### Vulkan instance creation no longer fails when a driver reports no layers

Upstream #2528. `CreateInstance` treated an empty `vkEnumerateInstanceLayerProperties` result as
fatal, so Vulkan never came up at all on such a driver — even though the code immediately after
filters the candidate layer list and would proceed happily with none.

**Not needed today on this device**: the stock Qualcomm driver reports layers, which is why Azahar
already runs. It becomes load-bearing the moment a custom driver is loaded through the fork's
libadrenotools path, where an empty layer list is plausible. Insurance, not a fix.

### Zero-area renderpasses are culled

Upstream #2510, found in 3D Thunder Blade. `DrawRect()` is the intersection of the viewport with
the surface rect, and sometimes they do not intersect at all, leaving the backend to begin a
Vulkan renderpass with a degenerate `renderArea`.

**Two things worth recording.** Upstream's predicate reads
`draw_rect.GetHeight() * draw_rect.GetHeight() == 0` — it tests height twice, so a zero-*width*
rect still gets through. Drafted for upstream as `docs/fork/upstream-issues/13-*.md`.

And this is a live lead on the fork's **unsolved texture-filter crash**. That investigation proved
begin/end are balanced and the framebuffer named at begin was alive, leaving "the driver has no
active renderpass at end time despite a recorded begin" — and a tiler with no bins to allocate is
one of the few things that can legitimately no-op a `vkCmdBeginRenderPass`. Neither probe recorded
the render area, so a degenerate rect would have passed both unseen. **Not a claimed fix**; the
crash predates #2510 and nobody has shown the filter transition produces a degenerate rect.
Recorded as a hypothesis in the plan and in the crash notes.

### HTTP response bodies stream instead of buffering

Upstream #2515. The HLE HTTP module buffered an entire response in RAM before the guest saw any of
it, so a 4 GB eShop redownload needed 4 GB of host memory and the emulated app timed out waiting.
Now streamed through a 16 MB backpressure buffer. **Directly good for a memory-constrained
handheld**, and inert unless a title makes HTTP requests.

### Reinterpretation warnings name the underlying host format

Upstream #2508. Diagnostics only. Whether a GPU supports `RGB565`/`RGB5A1` as a render target
changes which host format Azahar silently falls back to, so two users with identical log lines
could have had different actual behaviour. Useful here, since this fork diagnoses from device logs
and Adreno's supported-format set differs from desktop.

## One trap found while merging, worth knowing about

**The Kotlin settings mirror does not read the C++ defaults — it restates them as hand-written
literals.** Upstream #2530 touched only `settings.h`, so `BooleanSetting.kt` and `default_ini.h`
still said `true`. Left alone, the repair pass would have compared against a stale default, **and
the settings screen would have shown the switch resting "on" while the emulator ran with it off.**

Both are synced here, and every tier-2 and tier-3 key was checked against its C++ declaration —
this was the only disagreement. Verifying that mirror is now a required step on every upstream
merge, recorded in `docs/fork/settings-audit.md` §5.

**One consequence on your device:** an rc4/rc5 install whose provenance record already claims
`use_skip_duplicate_frames = true` will see the next pass cede the key, logging that you "changed
it deliberately" — which you did not. The value is identical either way so nothing changes in
practice; "Apply Thor defaults" un-cedes it if the fork ever wants an opinion here again.

## Standard of evidence

**Nothing in this build has been verified on hardware.** The owner's device has an intermittent
display fault and is heading for warranty repair, and no lab device was available. Host
verification only: the Catch2 suite (87 cases, 1624 assertions), `compileThorReleaseKotlin`,
`ktlintCheck`, the JVM unit tests, and one `assembleThorRelease`.

Save in-game before installing.
