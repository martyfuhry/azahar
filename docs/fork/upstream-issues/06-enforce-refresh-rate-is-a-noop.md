# Android: `RefreshRateUtil.enforceRefreshRate` never finds the 60 Hz mode on panels that report 60.000004, and only applies at all by accident

**Shape:** new issue, and the fix here genuinely is a few lines, so I can offer them inline with disclosure under your AI policy. Quoted below and flagged.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "refresh rate 60"`, `"refresh rate android"`. Nothing filed. #1615 (input lag) and #865 (speed locked to monitor refresh) are different problems.

**Note to self:** my first draft of this said the function was a flat no-op because it mutates `window.attributes` without assigning back. That is only half right and I nearly filed it wrong. All three call sites happen to run before `super.onCreate`, i.e. before the window is attached, so the mutation *does* reach the window manager at attach time. The bug that actually bites is the exact float comparison. The assignment is a latent defect, not the cause. Keep it in that order in the report.

---

## Environment

- Azahar: upstream `master` at `073110cb4` (2026-09-07). `src/android/app/src/main/java/org/citra/citra_emu/utils/RefreshRateUtil.kt` is 52 lines.
- Test device: Samsung Galaxy Z Fold5 (SM-F946U1), Android 16 (API 36), folded on its 2316x904 cover panel, which advertises 60 Hz and 120 Hz modes and reports the 60 Hz one as **60.000004**.
- Intended target: AYN Thor, Android 13, whose top panel is 120 Hz.

## Reproduction

1. Launch a game on a device whose panel supports both 60 Hz and a higher rate, with the system display setting on the higher rate.
2. While the game is running:

```
adb shell dumpsys SurfaceFlinger | grep -i "displayRefreshRate"
```

3. It reads the panel's high rate, not 60, even though `enforceRefreshRate(this, sixtyHz = true)` is the first statement in `EmulationActivity.onCreate` (`EmulationActivity.kt:106`).

You can also see it in the present-interval histogram, which is the shape I measured:

```
adb shell dumpsys SurfaceFlinger --timestats -clear -dump
```

## Expected

The 3DS runs at 60 Hz. `enforceRefreshRate(sixtyHz = true)` exists to hold the panel there during emulation, so it should.

## Actual

The panel stays at its high rate. On my phone at 120 Hz, a 60 fps guest gives the classic two-mode present histogram: 814 intervals in the 8 ms bucket and 796 in the 25 ms bucket, p95 = p99 = 25 ms. Zero janky and zero dropped frames — this is not a throughput problem, it is that the emulator asked for 60 Hz and did not get it. With the request actually taking effect, 1784 of 1796 intervals land in one 16 ms bucket.

| | 120 Hz panel (as shipped) | with the request landing |
|---|---|---|
| `displayRefreshRate` during the window | 120 fps | 60 fps |
| p50 / p95 / p99 present interval, 30 s | 16 / 25 / 25 ms | 16 / **16** / **16** ms |
| present-to-present buckets | 8ms=814 16ms=175 25ms=796 33ms=13 | 16ms=1784 17ms=8 33ms=4 |
| janky / dropped frames, 30 s | 0 / 0 of 1798 | 0 / 0 of 1796 |

Read that carefully: this is smoothness, not extra frames. Neither configuration drops or janks anything. Putting the panel where the emulator asked for it removes the uneven frame duplication, which is what "60 fps on a 120 Hz panel" looks like on a present histogram.

## Root cause

`src/android/app/src/main/java/org/citra/citra_emu/utils/RefreshRateUtil.kt`.

**The bug that bites: an exact float comparison.** Line 39:

```kotlin
newModeId = supportedModes.firstOrNull { mode -> mode.refreshRate == 60f }?.modeId
```

Panels routinely advertise **60.000004 Hz**, and 59.94 Hz. My Fold5's cover panel reports 60.000004. `firstOrNull` returns null, `newModeId` is null, and line 45's `if (newModeId == null) return` bails out before anything is set. The 60 Hz request is silently dropped on any panel that does not report a bit-exact 60.0.

**The latent one: the mode id is never dispatched.** Line 49:

```kotlin
window.attributes.preferredDisplayModeId = newModeId
```

Nothing calls `window.attributes = lp` (`Window.setAttributes`), which is what dispatches a layout-params change to `ViewRootImpl`. This works today only because all three call sites — `EmulationActivity.kt:106`, `SettingsActivity.kt:55`, `MainActivity.kt:87` — run at the very top of `onCreate`, before `super.onCreate`, so the mutated params object is the one the window manager picks up at attach time. It is correct by coincidence of call ordering, not by construction. Move the call, or call it after attach, or call it on a window that is already showing, and it silently does nothing. I would fix it anyway, because the next person to add a call site will not know this.

**Third thing, while the file is open: a mode id carries a resolution too.** `supportedModes.firstOrNull { it.refreshRate == 60f }` searches the entire mode list. On a panel that advertises 60 Hz at more than one resolution, picking "the 60 Hz mode" out of the whole list can silently drop the display to a lower resolution. Both the 60 Hz and the max-rate branches should filter to modes whose resolution matches the one the display is already in.

## How I fixed it in my fork

The tolerance, which is the part that actually changes behaviour:

```kotlin
mode -> abs(mode.refreshRate - 60f) < 0.5f
```

and the dispatch, which makes the function correct rather than accidentally correct:

```kotlin
val lp = window.attributes
lp.preferredDisplayModeId = newModeId
window.attributes = lp
```

**Those four lines are AI-written and I am disclosing them as such**, per your policy's snippet allowance. They are about as generic as Android boilerplate gets, but they should be labelled.

Beyond that, my fork also filters candidate modes to the display's current resolution, extends the util to take a `Presentation` so the second panel gets the same treatment — the bottom screen on a dual-screen device lives in a `Presentation` with a window and a display of its own and is not covered by any of this today — and calls `Surface.setFrameRate(60, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` from `surfaceChanged` on both `SurfaceView`s. That last one is the per-layer hint `preferredDisplayModeId` cannot give: it tells SurfaceFlinger the buffer cadence is fixed at the source rather than irregular. That is more than five lines and I am describing it rather than pasting it.

https://github.com/martyfuhry/azahar/commit/6bcf7a807

## Marty must verify before filing

- [ ] **Dump the Thor's actual mode list before writing a word.** `adb shell dumpsys display | grep -A20 -i "mode "` or log `Display.getSupportedModes()`. If the Thor reports a bit-exact `60.0`, then the tolerance bug does **not** bite on the target hardware and the report has to say so — it would then be a Fold5 finding plus a latent defect, which is a much smaller report and still worth filing, but not the one written above.
- [ ] Reproduce on the **Thor** with an **official Azahar build**: launch a game, `dumpsys SurfaceFlinger | grep displayRefreshRate`, confirm the panel is at its high rate while the emulator asked for 60.
- [ ] Take my own present-interval histogram on the Thor. The table above is Fold5, from my own builds.
- [ ] Do **not** repeat the "it mutates a copy and never assigns back, so it is a no-op" line. It is wrong as stated — the call sites save it. Say what is actually true: it works by accident of call ordering.
- [ ] Verify that forcing the 60 Hz mode does not change the panel's resolution on the Thor, since I am raising that as the third point.
- [ ] If I offer the four lines as a PR rather than an issue, disclose the AI assistance in the PR description exactly as the policy requires, and keep the PR to those lines. The `Presentation` and `setFrameRate` work stays in my fork.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to read `RefreshRateUtil` and correlate it with the present-interval histograms, and the replacement lines quoted above are AI-written and flagged as such. I reproduced the behaviour and took the measurements myself.*
