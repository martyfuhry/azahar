# Upstream submission candidates

Things this fork found that belong upstream, and where each one can actually go.

**The gate:** nothing is submitted until it has been validated on Marty's own AYN Thor. A
finding that only exists as code review, or that was only measured on the lab phone, is not
ready. Prefer the smallest patch that stands alone; prefer an issue over a patch where the
project's policy or the change's size makes a patch unwelcome.

**Driver-version warning (added 2026-09-09).** The Thor runs Qualcomm Adreno Vulkan
**512.676.53**; the Fold5 lab phone runs **512.676.1**. Same GPU, different driver build. Any
finding whose mechanism is driver behaviour — the texture-filter crash, the swapchain
lifetime bugs, the Qualcomm EDS / custom-border-color blacklists, anything gated on a driver
version — has to be re-checked on the Thor's driver before it is filed, and the filed report
must name the driver it was seen on. See `baselines/2026-09-09-thor-device-snapshot.md`.

---

## azahar-emu/azahar

**Policy:** `AI-POLICY.md`, effective 2026-03-20. AI used to *discover or understand* problems
is acceptable **provided a human independently verifies the issue**. AI-written code is
acceptable only for snippets of roughly five lines or fewer, **with disclosure in the PR**.
Anything larger is prohibited and will be closed, retroactively if discovered.

**So:** issues yes, if Marty reproduces them himself. Patches only where the fix is genuinely
a few lines, disclosed. Everything else stays in the fork.

| Finding | Evidence | Shape | Validated? |
|---|---|---|---|
| Changing the texture filter mid-game crashes | 12 crashes in 20 cold boots on the **pre-fork** baseline `b8aa5f893`, 0 in 5 when the filter is set before launch; `SIGSEGV` in `vkCmdEndRenderPass`, and `vkQueueSubmit` returning `VK_ERROR_DEVICE_LOST`. Full write-up in `research/texture-filter-crash.md`. **Corroborated on the Thor:** one `reason=5 APP CRASH (NATIVE)` status 11 at 2026-09-08 21:11:37 on `org.azahar_emu.azahar.thor`, the rc1 session, matching the crash Marty reported after changing the filter mid-game — stack not captured | **Issue.** Highest value here: reproducible, pre-dates the fork, and the report is most of the work | Counts and stacks are lab phone only. The Thor has one matching native crash but no backtrace and no count. Marty must still reproduce and count on the Thor, on an official build, and on the Thor's own driver (512.676.53, not the Fold5's 512.676.1) |
| Custom textures are never freed | No unload path anywhere in `src/video_core/custom_textures/`; memory converges on the pack's full decoded size (29 GiB for a 4K pack) | **Issue.** The fix is an eviction policy, far past five lines | Derived from source, not measured |
| Custom textures crash under Vulkan | Existing upstream issues #1308 and #2118, the latter from an AYN Thor Max with 16 GB. The OpenGL half is fixed (`c07f2cc96`); the Vulkan half is not | **Comment on the existing issues** with our analysis | Not reproduced by us |
| Preload budget overshoots by one texture | `custom_tex_manager.cpp:204-234` checks the budget after adding | **Issue**, or a small patch if the check can move | Source only |
| Touching the top panel injects touchscreen input | Upstream #2020. `IsWithinTouchscreen` ignores `bottom_screen_enabled` on Android; single-screen layouts publish a bottom rect for a hidden screen | **Patch, plausibly small enough** to qualify, with disclosure. Ours is larger because it also fixes `TouchMoved` and the layout | Fixed in rc2, needs Thor confirmation |
| `enforceRefreshRate` is a no-op | It mutates a copy of the window's `LayoutParams` and never assigns it back; also an exact `== 60f` match against panels reporting 60.000004 | **Patch, small.** Two lines plus the tolerance | Fixed in rc2, measured on the lab phone |
| A re-sent launch intent restarts the running game | Upstream #2329, reported by a Thor owner | **Issue** or small patch | Fixed in rc2, needs Thor confirmation |
| Controller mappings are never seeded | Upstream #1101 is the same problem from the other end: an unmapped pad's B falls through to Back and opens the drawer | **Issue.** Our fix is a new file, far too large | Confirmed working on the Thor |
| Vulkan swapchain lifetime bugs | Semaphores destroyed by a stale count after a surface-lost bail; `VkSurfaceKHR` leaked per recreation; `UNREACHABLE()` on any unexpected acquire/present result | **Issues, individually.** Each patch is small but there are several, and they are safety-critical | Fixed in rc1, no crash reproduced to prove them |

---

## rafaelvcaetano/melonDS-android

**Policy:** none. No `CONTRIBUTING`, no PR template, no AI statement anywhere in the repo or
tracker. An AI-assisted PR is therefore not prohibited; **disclose it in the body anyway**.
The maintainer is responsive on issues but the PR queue is slow and review-heavy, some open
since 2022, so expect latency and substantive review.

**What the Thor snapshot did to the motivation for this whole section (2026-09-09).** The
reason to do melonDS work at all was that Marty was losing sessions on the Thor, and the
working theory was a low-memory reclaim during sleep. His device's own `exit-info` says
otherwise: **two foreground `APP CRASH (NATIVE)` status 11 records on 2026-08-29, no
`LOW_MEMORY` record, and no excessive-CPU record.** The likely cause is upstream issue #1624
(external-display path on dual-screen devices), **already fixed upstream** in `cc6c3ba3`
(2026-05-13) and shipped to the nightly channel only; his installed build is the 2.0.1
release, whose release commit `5ec3648d` is dated 2026-05-06 and therefore cannot contain it.

Two consequences:

1. **His problem may need nothing from us.** A plain melonDS nightly is the first thing to
   try, and if it works, the fork is optional for him.
2. **The value of what we would offer upstream changes shape.** The foreground-service
   proposal loses its "this is why Thor owners lose sessions" motivation — that story is now
   #1624's, and it is closed. The findings that keep their full value are the ones that stand
   on their own source-level argument: the stale-checkpoint save corruption in PR #1666, the
   surface-generation bug (#1665), and the GL-teardown-off-context leak. Those are unaffected.

This does **not** mean the fork's work is wrong, only that its headline justification was
borrowed from a bug someone else already fixed. Do not carry the old framing into any issue
or PR body.

| Finding | Evidence | Shape | Validated? |
|---|---|---|---|
| **Restoring a stale checkpoint overwrites the player's `.sav`** | `RecoveryPolicy.finishDeviceSleep()` clears only the sleeping flags, so a checkpoint stays restorable forever; `NDSCart.cpp:487-488` writes a loaded savestate's SRAM back to the real save. Restoring a 10pm checkpoint at 11pm destroys an hour of progress *and* the save file. Also, a pending recovery pre-empts an explicit ROM launch | **Goes to the author of PR #1666 as a review comment, not a PR of ours.** It is their unmerged PR and they will want this before it lands. Highest-value thing we have for any project | Found by review; fix written in our fork, not device-tested |
| `SurfaceView` reuses one `Surface` across destroy/create, so neither renderer detects a window replacement | Explains the black-screen-with-working-sound reports (upstream #1665). Fix is a surface-generation counter | **PR, self-contained.** The strongest patch we have. Carries a fact the maintainer wants regardless of whether they take the code | Code review and a clean build only |
| A `file://` ROM search directory crashes the app | `FileSystemRomsRepository.scanForNewRoms` calls `DocumentFile.fromTreeUri` directly instead of via `CompositeUriHandler`, which explicitly supports the `file` scheme | **Issue or small PR.** Not reachable from the UI, so low priority for them | Reproduced incidentally |
| `loadCachedRoms` cannot hold `file://` entries | Filters through `DocumentFile.fromSingleUri(...).exists()` | Fold into the above | Reproduced incidentally |
| GL teardown runs on the UI thread with no current context | Every `glDelete*` is a silent no-op, leaking framebuffers, textures and HardwareBuffers per screen off/on cycle; plus a re-add race destroying freshly created resources | **PR, but split smaller for review** | Code review and a clean build only |
| A re-sent launch intent prompts to destroy the session | Upstream #1659, reported from a Thor | **Small PR** | Code review only; the review notes our fix may not match how the Thor's launcher builds its intent |
| Emulation should run under a foreground service | `oom_score_adj` 50 with a session against 700 without, measured | **Raise as an issue first**, and only if it can be argued on the `oom_score_adj` number alone. Opinionated: a permanent notification, and `specialUse` complicates their Play flavour. Overlaps PR #1666 in spirit | Measured on the lab phone. **Do not motivate it with Thor session loss** — the Thor has no `LOW_MEMORY` record, so that argument is not ours to make |

---

## Order of operations

0. For melonDS specifically: install a plain upstream **nightly** on the Thor and see whether
   the crashes stop. If they do, #1624 was the whole problem and most of the melonDS section
   above is optional work rather than needed work. This costs nothing and settles the biggest
   open question.
1. Marty validates on the Thor. Record the driver version with every measurement.
2. File issues where the policy or the size says issue. Reproduce independently first, in his
   own words, per Azahar's policy.
3. For melonDS, start with the review comment on PR #1666 — it costs nothing, helps another
   Thor owner, and needs no code from us.
4. Then the surface-generation PR, disclosed.
5. Reassess. If upstream takes things, the fork shrinks, which is the goal.
