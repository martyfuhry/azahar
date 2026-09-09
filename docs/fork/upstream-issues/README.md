# Upstream issue drafts for azahar-emu/azahar

Ready-to-file drafts for the things this fork found. **Nothing here has been filed.** Azahar's
`AI-POLICY.md` allows AI to discover or understand a problem *on the condition that a human
independently verifies it*, and it prohibits AI from submitting issues or pull requests
autonomously. So every one of these is a draft for Marty to reproduce himself, rewrite in his own
words if he wants to, and file under his own name — and every one ends with a
**"Marty must verify before filing"** checklist naming exactly what has to be reproduced first.

Two other rules I have followed throughout:

- **No diffs.** Each draft describes the fix in prose and links the public commit in
  `github.com/martyfuhry/azahar` for a maintainer who wants to look. The one exception is
  draft 06, where the whole fix genuinely is about five lines; those lines are quoted inline and
  flagged as AI-written, which is what the policy's snippet allowance requires.
- **Existing issues get comments, not new issues.** Drafts 08–11 are comments on threads that are
  already open, adding root cause and measurements to what is already there.

Every draft was checked against the tracker with `gh issue list -R azahar-emu/azahar --search …`
and `gh issue view` before it was written; each one records what that search found at the top.

---

## Filing order

Do them in this order. It front-loads the things that are cheapest to verify and hardest to argue
with, and it puts the four comments early because they cost almost nothing, they help people who
are already waiting in those threads, and a maintainer who has seen two useful comments from you
reads the third report differently.

| # | File | Shape | Where |
|---|---|---|---|
| 1 | `08-comment-1965-2020-top-screen-touch.md` | comment | #1965 (the live thread; #2020 is closed as its duplicate) |
| 2 | `09-comment-2329-resent-launch-intent.md` | comment | #2329 |
| 3 | `10-comment-1101-unmapped-controller-opens-drawer.md` | comment | #1101 (closed; reopening is a maintainer's call) |
| 4 | `01-audio-stream-never-stopped-while-paused.md` | **new issue** | new; then a one-line pointer on #519 |
| 5 | `06-enforce-refresh-rate-is-a-noop.md` | new issue (or a ~5-line disclosed PR) | new |
| 6 | `03-shader-cache-discarded-on-savestate-load.md` | new issue | new |
| 7 | `04-hidden-virtual-display-second-frame.md` | new issue | new; link #2455 as related |
| 8 | `02-texture-filter-crash-mid-game.md` | new issue, **unfixed** | new |
| 9 | `07-custom-textures-never-freed.md` | new issue | new |
| 10 | `11-comment-1308-2118-custom-textures-vulkan.md` | comment | #2118 (file after 9, so it can point at it) |
| 11 | `05-vulkan-swapchain-lifetime-bugs.md` | new issue, or drop | new, or a comment on #1693 |
| 12 | `12-android-accurate-multiplication-hardcoded-off.md` | new issue | new; supplies a cause for the Android half of #1445, without closing it |

Number 12 is appended rather than slotted in, to avoid renumbering, but on merit it belongs
**near the front** — arguably first. It has what none of the others combine: a source-evident
cause of two lines, a reproduced user-visible symptom with a before/after screenshot pair, and a
one-line fix. It is also independent of every draft above it, so it can be filed at any point.

It is the one draft whose central claim was **asserted, withdrawn, and then reinstated**, and the
sequence is worth carrying because it is a lesson about method. It was first asserted from a
title-level match with #1445 with no comment thread read; withdrawn after reading those comments,
which look like they refute it (`Haisom` reporting that accurate multiplication does not help and
that Vulkan alone fixes it; `ShinyMooTank` hitting the same symptom on desktop with the setting
already on); then reinstated when the thing was actually tested on hardware and the setting
plainly fixed it under Vulkan on an Adreno 740.

So: reading the thread was right and should have lowered confidence in an untested claim — but
**comments from strangers with unknown configurations are not evidence either**, and treating
them as decisive was the same mistake in the other direction. One controlled test settled in
minutes what neither inference nor testimony could. Where a claim is cheaply testable on hardware
we own, test it before asserting *or* retracting.

Two things to preserve when filing: **n=1** — one device, one driver, one game, Vulkan only — and
an explicit acknowledgement that `ShinyMooTank`'s desktop case is unexplained, so this does not
close #1445 and #1292 / #440 are not duplicates on this evidence.

Number 11 is last on purpose. It is three real code defects that I never managed to turn into a
crash on demand, and a report with no reproduction is a weaker thing to put in front of a
maintainer than the ten above it. If a lid-cycle soak on the Thor does not produce one of them,
consider posting only the `UNREACHABLE()` half as a comment on #1693 and keeping the rest in the
fork.

---

## What has to be reproduced, per draft

The checklists in each file are authoritative. This is the summary.

**08 — top-screen touch (#1965).** On the Thor with an official build: default layout, touch the
top panel, confirm it registers in game. Also try the desktop Single Screen case, because #2020's
second comment claims it happens there too and a desktop repro raises this above "a Thor thing".

**09 — re-sent launch intent (#2329).** On the Thor with an official build: start a game, close
the lid, open it, confirm the game restarts from the title screen. Then prove it was the intent
and not a kill — pid unchanged across the cycle, fresh boot markers on the same pid.

**10 — unmapped controller (#1101).** Fresh install (or all bindings cleared) on the Thor,
official build: press B on the built-in pad, confirm the drawer opens and the pad is then stuck in
the menu. Verify the `FLAG_FALLBACK` claim on that hardware before posting it — it is what makes
the proposed fix safe. Also confirm the restored-open-drawer case.

**01 — audio stream never stopped.** The big one, and the one with the most to reproduce. On the
Thor, on an unmodified upstream build: play, Home, sample `/proc/<pid>/stat` over 30 s, confirm
the process total is well over 2% and that the audio device thread is where it goes. A Thor kill record is **not** a
prerequisite: `exit-info` on his device was read on 2026-09-09 and holds **no excessive-CPU
record for any package at any date**, so one may never appear. File on the burn plus the Fold5
kill record, labelled as a Fold5 record, and say in the body that the kill was seen on one
device and the burn on both. The draft says the same; this line used to contradict it.

**06 — `enforceRefreshRate`.** Dump the Thor's mode list *first*: if it reports a bit-exact
`60.0`, the tolerance bug does not bite there and the report has to change shape. Then, with an
official build, launch a game, `dumpsys SurfaceFlinger | grep displayRefreshRate`, and confirm the
panel is at its high rate while the emulator asked for 60. Do not repeat the "it is a flat no-op"
claim — see the note at the top of that draft.

**03 — shader cache discarded on savestate load.** Reproduce on **desktop** first: build a shader
cache, restart, load a savestate, show from the log that nothing reloads it and that pipelines
recompile. That is the version a maintainer can run in five minutes with no Android device, so it
should lead the report. Then confirm the post-resume stutter on the Thor.

**04 — hidden VirtualDisplay.** Cheapest verification in the batch:
`adb shell dumpsys display | grep HiddenDisplay` on a single-screen phone, with the secondary
display setting **off**, on an official build. Then a `dumpsys meminfo` EGL reading, and a check
that the Thor's real second panel is a real display and not this.

**02 — texture-filter crash.** Reproduce on the Thor with an official build: boot at 3x with no
filter, get into gameplay, change the filter. Get my own count and my own tombstone. Also confirm
the "set the filter before launching" workaround holds on Android 13, because the report offers it.

**07 — custom textures never freed.** This one currently rests on reading the source, which is the
weakest evidence in the batch. Measure the growth curve on the Thor with a real pack —
`dumpsys meminfo` sampled as you move through areas — and produce an actual monotonic curve before
filing. Do **not** present the 29 GiB figure as measured; it is computed.

**11 — custom textures under Vulkan (#2118).** Nothing to reproduce, because the comment says
plainly that I have not reproduced their crash. What has to be done is re-reading
`src/video_core/custom_textures/` on the day of posting and re-confirming every line and claim,
since the whole comment is a source-reading contribution.

**05 — Vulkan swapchain lifetime.** Try to force at least one of the three on the Thor: a
lid-cycle soak with a short dwell for the semaphore double-destroy, and repeated screen off/on for
the `UNREACHABLE()` on a device-lost acquire. Re-confirm every line number in `vk_swapchain.cpp` on
the day of filing — that file moved recently.

**12 — accurate multiplication hardcoded off on Android.** Mostly done, and cheap to finish. The
rendering half is **already reproduced** on the Thor: Pokémon X's starter scene with Froakie
white, then correct after enabling the setting and restarting, nothing else changed. What is
left is packaging. Attach **both screenshots** — without them the report is an assertion — and
commit them next to the draft so they survive. Re-run the before/after on an **official release
build** rather than my checkout, and capture the emulator's own
`Renderer_ShadersAccurateMul: false` boot line while doing it, because both device logs have
since rotated to `true` and no longer hold the original state. Confirm the two source lines in
upstream `master` at a named commit. Then two disciplines that decide whether this lands: say
**n=1** and mean it, and **acknowledge `ShinyMooTank`'s desktop case** rather than omitting it —
a desktop report with the setting already on cannot be caused by an Android-only default, so the
symptom likely has more than one cause and this is one of them. Optional and genuinely useful:
test Android + OpenGL, since `Haisom`'s claim that the setting does not help there is untested by
this run and may be true.

---

## Suggested device sessions

Most of the above can be batched. Roughly:

**Session A — Thor, official Azahar build, no fork build on the device at all.**
This is the session that produces almost everything, and it has to be on an official build so that
nothing in the report can be waved away as "that is your fork". Fresh install if possible.
Covers: 08 (touch), 09 (lid intent), 10 (controller, needs the fresh install), 06 (refresh rate),
02 (filter crash), 04 (confirm the Thor's second panel is real).

**Session B — Thor, official build, left alone.**
Play, Home, walk away for twenty-plus minutes, come back and read `exit-info`. Sample
`/proc/<pid>/stat` before walking away. Covers 01, which is the only item that needs elapsed time
rather than attention.

**Session C — Thor, official build, a texture pack installed.**
Covers 07, and produces the observation that upgrades 11 from source-reading to measurement.

**Session D — desktop, any platform.**
Covers 03, and the desktop half of 08.

**Session E — Thor, lid-cycle and screen-cycle soak.**
Covers 05, if it covers it at all.

---

## Not in this batch

Things in `docs/fork/upstream-candidates.md` that deliberately did not get a draft:

- **Controller mapping seeding.** My fix is a whole new file; upstream #1101 is the same problem
  from the other end and is covered by draft 10. Filing "please seed default bindings" as a
  separate feature request is a different conversation and I have not written it.
- **Everything for melonDS.** Different project, different policy, different drafts. See
  `docs/fork/upstream-candidates.md`.
