# thor-v1-rc2

Release candidate 2 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## Do this after you install

**Settings > System > AYN Thor > "Apply Thor defaults".**

The Thor profile in this build — dual screen, Vulkan, 4x internal resolution, autosave
Always, no touch overlay while the pad is connected — is written **only on a fresh install
that has no `config.ini` yet**. You are upgrading over an existing configuration, so the
first-run pass will look at your config, see that every key already has a value, and change
nothing. The bottom panel will stay however it is now.

The menu action re-applies the whole profile, overwriting what is there. It asks first and
names what it changes (layout, graphics, autosave) and what it does not (controls). Run it
once after installing, or none of the dual-screen and display work below is actually
switched on for you.

## What this is

Everything in `thor-v1-rc1`, plus wave 2: the dual-screen and display work, the boot and
shader work, the background memory trim, the Thor defaults profile, and the input fixes
listed under **What changed**.

Upstream Azahar `master` at `073110cb4` ("tools: Added basic-mxe-config.sh script") plus
the fork's own work, merged on the `thor/main` integration branch — the same upstream base
as rc1. Nothing here is upstream Azahar code that has been version-bumped or re-branded:
the fork commits sit on top of an unmodified upstream tree.

Package `org.azahar_emu.azahar.thor`, label "Azahar Thor", `thor` flavour, release build,
signed with the Android debug key. That is the same package the rc1 asset ended up being
(the rc1 APK was replaced with a thor-flavour build after the tag), so this installs over
your rc1 and keeps its settings and user folder. It installs side by side with an official
Azahar, which is a different package.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc2.apk` |
| Built from | `31ea3ed33` (clean tree) |
| versionName | `thor-v1-rc2-thor` |
| versionCode | `33735322` |
| sha256 | `d88d14277bdd949b9fe0d95baa17f9e737d4e88b621f6b6f184f332417ea3884` |
| Size | 50 229 335 bytes |
| ABIs | arm64-v8a, x86_64 |

`31ea3ed33` is this commit before the artifact rows above were filled in — a checksum of an
artifact cannot be committed into the tree the artifact is built from. The `thor-v1-rc2` tag
is on the amended commit, so the shipped APK's `Azahar Version:` log banner reads
`31ea3ed33` / `thor-v1-rc2-0-g31ea3ed33` rather than the tagged commit's hash. The two trees
differ only in this file; no code, resource or build file changed between them. rc1 was
published the same way.

## What changed

### (a) Dual screen and display

This is the group that makes the Thor's two panels behave like a 3DS rather than like a
phone with a dark slab underneath it.

**No hidden second display.** `SecondaryDisplay` used to create a 1920x1080 `VirtualDisplay`
named "HiddenDisplay" in its constructor and put the bottom-screen `Presentation` on it
whenever there was no real second display to use — the setting off, the layout NONE, or
simply a one-screen phone. The `enable_secondary_display` setting only ever chose *which*
display the Presentation targeted; it never stopped the second window from existing. That
Presentation was a real surface, so the core rendered and presented a whole second frame
into it every vblank: a fence wait, a fullscreen render pass, a blit, a `vkQueueSubmit`, a
`vkQueuePresentKHR` and two submit-mutex acquisitions, plus a second 1080p swapchain
resident in VRAM, for pixels no panel ever composited. Now the secondary window is created
with no surface behind it, both renderers test the surface rather than the window pointer,
and the Vulkan present window (swapchain, images, command pool, frames) is built when a
surface appears and released when it goes away. Hot-plug still works in both directions with
no restart, and the Thor's real dual-screen path is unchanged — its built-in second panel is
a real display, so the Presentation is created exactly as before. The measured effect is the
EGL and thread rows in the table below.

**The Presentation survives a lid cycle instead of being rebuilt on every display event.**
Four separate reasons the bottom panel used to churn or vanish around a lid close/open:

- The "is this the display we are already on" guard compared `Display` *references*, and the
  available-displays getter re-queries the `DisplayManager` and hands back a fresh object
  every time, so the guard never matched a physical panel. Every `onDisplayChanged` dismissed
  the Presentation and built a new one — a surface destroy and create per callback, which is
  a visible black flash and the trigger for most of the secondary-surface races. It now
  compares `displayId` and checks the Presentation is actually still showing.
- `updateDisplay()` read the display list up to five times per call, potentially getting a
  different answer at each step. It is queried once now.
- A `DisplayListener` callback arriving while the activity was merely *stopped* — which on a
  lid close is exactly when they arrive, unordered with respect to `onStop` — threw
  `BadTokenException` (a Presentation is a Dialog and needs the activity's window token), was
  swallowed, and left the Presentation null until some unrelated display event came along.
  `updateDisplay()` now returns early unless the activity is between `onStart` and `onStop`,
  and the activity re-runs it from `onStart` and `onResume`.
- On a lid open the panel still reports `STATE_OFF` when the activity restarts, so it was
  filtered out. `onStart`/`onResume` now schedule exactly one re-check about 500 ms later,
  and only when a real panel exists and is merely asleep, so a panel that stays off cannot
  turn this into a polling loop.

The display-picking heuristic is also fixed: it used to prefer any display whose name lacked
"Built", which deprioritised the Thor's own built-in second panel the moment any other
non-default display existed (scrcpy, a cast route). It now tests directly for what it was
guarding against — the Odin 2's phantom virtual display — then prefers a non-virtual
built-in panel whose name differs from the default panel's. An explicit choice in the
in-game display chooser still wins over all of it.

**Touching the top panel no longer injects touchscreen input.** `IsWithinTouchscreen`
decided whether a touch landed on the DS touchscreen purely from the window layout's
`bottom_screen` rectangle, with one desktop-only exception. On Android the single-screen
layouts published a full-window `bottom_screen` rectangle even when the bottom screen was
hidden, so the window showing only the top screen behaved like the touchscreen. On the Thor
that is the default arrangement, so touching the upper screen injected touch into the game
(upstream #2020). Both halves now ask `bottom_screen_enabled`, and `SingleFrameLayout` stops
publishing a rectangle for the screen it does not show. `TouchMoved` gets a matching guard,
because touch state is global across windows and a drag over the top panel must not clamp a
touch point the bottom panel set. Covered by tests.

**The 60 Hz request is now actually applied.** `enforceRefreshRate` existed to hold the panel
at the 3DS' 60 Hz and did nothing, for four reasons: `WindowManager.LayoutParams` read back
from a `Window` is a copy, so setting `preferredDisplayModeId` on it had no effect unless it
ran before the window was attached; the match was `refreshRate == 60f`, and panels advertise
60.000004 and 59.94; a mode id carries a resolution as well as a rate, so picking "the 60hz
mode" out of the whole list could silently drop the panel's resolution; and the util only
ever touched the activity window, never the Presentation's. All four are fixed, and both
`SurfaceView`s now also call `Surface.setFrameRate(60, FIXED_SOURCE)` from `surfaceChanged`,
which tells SurfaceFlinger the buffer cadence is fixed at the source rather than irregular.
**This is a deliberate refresh-rate change and it is where the frame-pacing numbers below
come from** — read that section before treating them as extra throughput.

### (b) Boot and shaders

**The shader cache is no longer thrown away during an autosave resume.** This is the 20 %
boot win. `RunCitra()` loaded the disk shader/pipeline cache into the freshly built renderer
and only *then* offered the autosave; loading a savestate goes through `System::serialize`,
which calls `Shutdown(true)` and `Init()` again, and `Init()` constructs a brand new GPU with
a brand new renderer. Everything just read off disk went with the old one, and nothing put it
back. The resumed session then recompiled every pipeline the game touched, which is the
stutter storm right after a resume. Now the resume happens first — still through the same
`Signal::Load` path, so clock re-anchoring and the core error dialog behave as they do for a
user-picked slot — and the disk cache is loaded afterwards, into the renderer that will
actually run the game. If something rebuilds the renderer later (the "ask me" autosave mode,
or a mid-session savestate load) the run loop notices and reloads the disk resources
silently. `ApplyPerProgramSettings()` also used to run against the renderer the load was
about to destroy; that is fixed in passing.

**Asynchronous shader compilation is on by default on Android.** A cold pipeline compiled on
the Vulkan worker blocks it until the driver is done. On the Adreno 740 that is the dominant
source of stutter for the first minutes of a session and every time the game reaches an area
whose pipelines are not cached — and the stock Qualcomm driver has no extended dynamic state,
so rasterization and depth-stencil state fold into the pipeline key and a title needs many
more distinct pipelines than it would on a desktop driver. Compiling on the worker threads
trades a moment of pop-in (the draw is skipped until its pipeline is ready) for not stalling
the frame. Small blits of six vertices or fewer still block, so they do not appear as missing
geometry. The setting is read once when the rasterizer is constructed, so it takes effect on
the next boot; the Graphics screen's switch default was flipped to match. Desktop is
unchanged.

**The loading screen says what the boot is doing.** The first progress stage used to be
emitted *after* `System::Load()` returned, and `System::Load()` is where the Vulkan device,
the swapchain and the whole HLE service tree get built — the better part of a second with
only the game icon on screen. "Initializing…" is now emitted before it. Related:
`initMultiplayer()` no longer runs on the UI thread during `onCreate`, where it read the CFG
username off the emulated NAND and brought the network stack up before the activity had
inflated its layout. And because the "Complete" stage is now emitted after the resume rather
than before it, the loading screen stays up for the whole boot instead of handing over a
black screen mid-load.

### (c) Memory

**Cached surfaces are released when the app is backgrounded.** Nothing in the app implemented
`ComponentCallbacks2.onTrimMemory`. It is now forwarded to the core, which raises a request
under `paused_mutex` and wakes the emulation thread (the callback arrives after `onPause` has
already parked it, so the wait predicate has to see it); the paused branch carries it out
after the autosave and the pipeline-cache flush, in that order — the autosave is the one that
has to reach disk before Android kills the process, and running the trim last means the
savestate does not flush the same surfaces twice. The `RUNNING_MODERATE/_LOW/_CRITICAL`
levels, which arrive while the game is on screen, are deliberately ignored: the release costs
a hitch while the textures upload again.

**This did not fire on the measurement device, and it is not claimed as a win.** See Known
issues (b).

### (d) The Thor profile

**First-run defaults.** A fresh install did not know the Thor is two panels. It booted with
`layout_option = 0` (both 3DS screens stacked on the top panel), `resolution_factor = 1` and
`autosave_mode = 0`, so the bottom panel stayed dark, the 400x240 top screen was drawn 1:1
into a 1080-wide display, and every lid close lost the session. `ThorDefaults` writes the
configuration once, at first run, through the ordinary `config.ini` writer, so the result is
indistinguishable from settings picked by hand: single screen on the primary window, the
opposite screen on the secondary display, secondary display on, no screen swap; Vulkan at 4x
with the disk shader cache and async shader compilation on and no texture filter (a filter
multiplies sampled-texture memory by the square of the resolution factor, and 4x is already
the memory ceiling on the 8 GB SKU); audio stretching on, frame limit 100, CPU clock 100 %;
autosave Always, because the lid and the AYN launcher background the app constantly; perf
logging off. Gating is two-sided: the `thor` build flavour covers this fork's own build, and
a manufacturer/model allowlist covers a vanilla build on the hardware.

**The "Apply Thor defaults" action.** The first-run pass runs at most once per install and,
on that pass, only writes keys `config.ini` does not already have a value for — right for an
upgrade, but it leaves no way back to the profile. Settings > System now grows an "AYN Thor"
section with an action that re-applies the whole thing, overwriting what is there. It
confirms first, names what it will and will not change, logs the applied values and reports
the count as a toast. **On an upgrade this is the only thing that turns the profile on** —
see the top of this document.

**The touch overlay is hidden by default while a physical pad is connected.** The Thor has a
built-in gamepad, so the on-screen d-pad and buttons drawn over the top panel on first boot
were never the input path — just translucent shapes on top of the game that the user had to
go and turn off. `showOverlay` becomes a default rather than a stored constant: with no
explicit choice recorded it answers "no overlay" while a physical gamepad is connected, using
the same test the auto-mapper applies. The moment you use the overlay toggle your choice is
recorded and wins from then on, whatever is plugged in. The fragment also watches for
hot-plug while resumed — unplug the pad mid-game and the controls come back — and stops
checking once you have made an explicit choice.

### (e) Input

**Unbound controller keys no longer fall through to Back.** A gamepad button that no binding
claims leaves `dispatchKeyEvent` unhandled, and Android's key character map then defines
fallback actions for the gamepad range: `KEYCODE_BUTTON_B`/`_Y` come back as `BACK`, `_A`/`_X`
as `DPAD_CENTER`. In this activity `BACK` opens the emulation drawer, and once the drawer is
open every key walks its menu — so an unmapped pad does not merely do nothing, the first press
opens the menu and the pad is stuck in it (upstream #1101 from the other end). While the game
is on screen and the drawer closed, a gamepad-sourced button or d-pad keycode is now consumed
even when no binding wanted it, so Android never synthesises the fallback. The drawer still
opens from the back gesture, the menu button, a bound hotkey, and a real `BACK` key on a pad
(which arrives without `FLAG_FALLBACK`). The first swallowed key is logged with its keycode
and device name, so "the buttons do nothing" has an answer in the log.

**A drawer restored open after a process kill is now closed.** `DrawerLayout` saves whether it
was open and reopens itself from `onRestoreInstanceState`. When Android killed the app and
later restored the task, a session backgrounded with the menu open booted the title from
scratch *behind an open drawer* — over the loading screen, with the menu holding the focus a
gamepad navigates with, and contradicting the drawer's own locked-closed mode. It is now
closed in `onViewStateRestored`, right after the view hierarchy is restored and before
emulation starts.

**Focus returns to the game when the drawer closes.** `DrawerLayout` stops offering the menu's
rows to focus search once closed, but never takes focus *away* from the row that already had
it, so a closed drawer kept the focus a d-pad or `DPAD_CENTER` acts on and a press activated a
menu item behind the game. The drawer's focus is now cleared on close and handed to the input
overlay.

**On the field report from rc1.** Marty's rc1 session had the pad mapped correctly by the
auto-mapper and still saw every button driving the drawer after an autosave resume. That rules
out the unbound-key chain as the cause on this device; the restored-open drawer is the likely
one and is the fix that matters here. The unbound-key swallowing is kept because it protects
any device or moment where a binding genuinely is missing. Neither has been verified on the
Thor.

## The numbers

Copied from `docs/fork/baselines/2026-09-09-rc2-vs-rc1.md`. Baseline is rc1 at `59f9592f4`,
candidate is rc2 at `acf5f0133` — the `thor/main` tip at measurement time, which differs from
this release only by documentation commits.

**Read the caveats first.**

- **One run per metric.** No repeats. Treat any difference under about 5 % as noise unless a
  mechanism is named for it.
- **Taken on a Galaxy Z Fold5** — and on Adreno driver **512.676.1**, where the Thor is on **512.676.53** (SM-F946U1, Android 16), folded on its 2316x904 cover panel,
  running Animal Crossing: New Leaf at `resolution_factor = 3`. Same SoC as the Thor. **Not
  the Thor**: different panel, different thermals, different lid. Nothing below has been
  measured on the target hardware.
- **scrcpy was running for both halves.** It adds a virtual display and an encoder. The rc1
  vs rc2 deltas are still valid — both builds were measured back to back, eight minutes
  apart, on the same device with nothing else changed — but the **absolute** figures are
  **not comparable with the 2026-09-08 rc1-vs-baseline table**, which was taken scrcpy-free.
  Use this table only for rc1 vs rc2.
- **The frame-pacing win comes from putting the panel in 60 Hz**, not from extra throughput.
  Neither build drops or janks a frame. See below.

### The two that matter

| Metric | rc1 | rc2 | Delta |
|---|---|---|---|
| Cold boot, autosave resume: intent → first emulated frame (ms) | 3527 | **2805** | **−722 (−20.5 %)** |
| p95 present interval, 30 s (ms) | 25 | **16** | **−9** |
| p99 present interval, 30 s (ms) | 25 | **16** | **−9** |
| Janky / dropped / late-acquire frames, 30 s | 0 / 0 / 0 of 1798 | 0 / 0 / 0 of 1796 | 0 |

### Everything else

| Metric | rc1 `59f9592f4` | rc2 `acf5f0133` | Delta |
|---|---|---|---|
| Cold boot, autosave ignored: intent → first frame (ms) | 1866 | 1743 | −123 (−6.6 %) |
| Savestate load phase (ms) | 1279 | 1222 | −57 |
| Disk shader/pipeline pass thrown away before the load (ms) | 738 | 0 | **−738** |
| Pipeline disk cache size loaded (KB) | 9587 | 9587 | 0 |
| Warm re-intent → first frame (ms) | 238 | 221 | −17 |
| EGL mtrack @45 s (kB) | 126 316 | **77 140** | **−49 176 (−38.9 %)** |
| TOTAL PSS @45 s (kB) | 1 405 254 | 1 355 270 | −49 984 (−3.6 %) |
| GL mtrack @45 s (kB) | 450 804 | 448 936 | −1 868 (−0.4 %) |
| Native heap @45 s (kB) | 631 615 | 633 960 | +2 345 (+0.4 %) |
| VmRSS @45 s (kB) | 954 236 | 956 256 | +2 020 (+0.2 %) |
| Threads @45 s | 57 | 54 | **−3** |
| TOTAL PSS 45 s after HOME (kB) | 1 372 197 | 1 329 166 | −43 031 (−3.1 %) |
| EGL mtrack after HOME (kB) | 83 160 | 42 180 | −40 980 (−49.3 %) |
| GL mtrack after HOME (kB) | 449 876 | 447 480 | −2 396 (−0.5 %) |
| displayRefreshRate during the window | 120 fps | 60 fps | −60 (deliberate) |
| p50 present interval, 30 s (ms) | 16 | 16 | 0 |
| present-to-present buckets | 8ms=814 16ms=175 25ms=796 33ms=13 | 16ms=1784 17ms=8 33ms=4 | bimodal → single-mode |
| averageFPS, 30 s | 60.659 | 62.335 | +1.68 |
| Foreground CPU, 20 s (% of one core) | 48.88 | 50.09 | +1.21 pp |
| Paused CPU after HOME, 30 s (% of one core) | 0.33 | 0.36 | +0.03 pp |
| perf mean: game_fps / frametime / gpu | 59.43 / 5.03 ms / 1.21 ms | 58.83 / 5.79 ms / 1.52 ms | −0.60 / +0.76 ms / +0.31 ms |
| Hidden 1080p `HiddenDisplay` VirtualDisplay present | **yes** (display 8, 1920x1080) | **no** (0 matches) | removed |
| Pipeline cache loaded into the renderer that survives the resume | **no** (loaded t=0.673 s into a renderer destroyed at t=1.41 s) | **yes** (t=1.854 s, after `Load completed`) | fixed |
| CRASH_NATIVE / ANR over 5 HOME/relaunch cycles | none | none | same |
| Relaunch after HOME resumes in place | yes | yes | same |
| Autosave written on pause / loaded after force-stop | yes / yes | yes / yes | same |

**The boot win is mechanically explained, not a lucky run.** rc1 loaded the disk cache at
t=0.673 s and destroyed that renderer at t=1.411 s: 738 ms spent on an object that no longer
existed. That removed 738 ms matches the measured −722 ms almost exactly. Both builds end up
with a populated 9587 KB pipeline cache — in rc1 as a side effect of the cache switch inside
the savestate load, not because of the pre-load pass — so the payoff is the deleted duplicate
pass, not "the cache would otherwise be empty".

**The frame-pacing win is a 60 Hz panel.** rc1 presented a 60 fps guest to a 120 Hz panel, so
the histogram is the familiar two-mode 8 ms / 25 ms split (814 and 796 samples) and p95 = p99
= 25 ms. rc2 puts the panel in its 60 Hz mode and 1784 of 1796 intervals land in one 16 ms
bucket. Neither build janks or drops a frame, so this is smoothness, not throughput. Worth
stating plainly rather than reading as "9 ms faster".

**The memory win is the second swapchain, not the surface cache.** EGL mtrack falls 49 MiB in
the foreground and 41 MiB behind HOME and the thread count drops by 3, while GL mtrack, native
heap and VmRSS are all unchanged within noise. That is exactly the shape of removing a 1080p
hidden swapchain and its present path, and the display dumps confirm it directly. TOTAL PSS
follows it down by 50 MB foreground / 43 MB paused.

**Paused CPU was already at target on both builds** (0.33 % and 0.36 %, against a < 0.5 %
goal), so the rc1 audio fix is holding and there was nothing left to win. Foreground CPU is
1.2 pp higher on rc2 — single-run noise, in a window where rc2 also renders about 3 % more
frames.

### At 4x, which is what you play at

Taken twice, both boots resuming from the autosave; `config.ini` was restored to 3x afterwards
and the restore verified on the device.

| Metric | rc2 @ 3x | rc2 @ 4x (run 2 / run 1) |
|---|---|---|
| Boot with autosave resume → first frame (ms) | 2805 | 3333 / 9033 (run 1 was the first boot after the install) |
| TOTAL PSS @45 s (kB) | 1 355 270 | 1 306 429 / 1 285 029 |
| GL mtrack @45 s (kB) | 448 936 | 449 096 / 448 056 |
| EGL mtrack @45 s (kB) | 77 140 | 85 576 / 85 576 |
| Native heap @45 s (kB) | 633 960 | 581 381 / 470 757 |
| p50 / p95 / p99 present interval (ms) | 16 / 16 / 16 | 16 / 16 / 16 — 16 / 16 / 24 |
| Janky / dropped frames, 30 s | 0 / 0 of 1796 | 0 / 0 of 1797 — 0 / 0 of 1795 |
| averageFPS, 30 s | 62.335 | 62.374 / 61.845 |
| perf mean: game_fps / frametime / gpu | 58.83 / 5.79 ms / 1.52 ms | 59.38 / 6.07 ms / 1.85 ms — 59.73 / 6.65 ms / 2.48 ms |

`Renderer_UseResolutionFactor: 4` is in both 4x boot logs, so the setting took. **4x holds
60 fps with zero janky frames and costs about +0.3–1.0 ms of GPU time per frame**, and
**graphics memory barely moves** (GL mtrack +160 kB): the 3DS renders 400x240, so 3x is
1200x720 and 4x is 1600x960, and the extra render targets are a couple of MiB — the ~438 MiB
of GL mtrack is the texture and surface cache, not the framebuffers.

The PSS and native-heap columns are **not** a clean 3x-vs-4x comparison: the 3x foreground
sample came from a boot that ignored the autosave (a fresh title start) while both 4x samples
resumed it, so the scenes differ. Only the frame and perf rows are a resolution comparison.

## Known issues

**(a) Changing the texture filter while a game is running can still crash.** Roughly one
attempt in seven takes the process down **on the Fold5**; no rate has been measured on the
Thor, and because the fault is inside the Adreno driver the rate need not transfer. That it
*happens* on the Thor is confirmed: the rc1 crash of 2026-09-08 21:11:37 was this bug, and its
recovered stack matches the Fold5 signature exactly — same `VulkanWorker` thread, same
`vkCmdEndRenderPass`, same `0xb4` fault address — on the Thor's newer driver. It is a driver-level race: changing the filter
rescales every texture mid-frame, and surfaces are destroyed while draws referencing them are
still in flight. **Workaround: set the texture filter from the main menu before launching a
game.** That path has never crashed in testing. A fix is in progress on
`fix/texture-filter-crash` (deferred framebuffer destruction, closing the render pass before a
surface is rescaled, retiring texture handles rather than destroying them) and is not in this
build — the branch is mid-investigation and its device numbers are not in yet. Note the Thor
profile sets the filter to None, so if you run "Apply Thor defaults" you are not on a filter
anyway.

**(b) The background memory trim never actually fires.** The code in (c) above is in this
build and was **not exercised even once** on the measurement device. Neither
`Trimming memory at level N` nor `Releasing cached resources on request of the frontend`
appears anywhere in 5.5 minutes of logcat covering HOME plus three minutes parked, and GL
mtrack behind HOME stays within 1.5 MB of its foreground value on **both** builds. The reason
is the foreground service from rc1: the process sits at `procState=FGS`,
`oom_score_adj=200`, `isFrozen=false`, so Android never sends it a `TRIM_MEMORY_UI_HIDDEN` or
worse while it is parked. The 41 MB of EGL that does disappear at HOME is the framework
releasing the swapchain, and it happens on rc1 too. So this is **unverified, not disproved** —
the path was never entered. It is listed here as a fix owed to the next candidate, not as a
win. The next measurement pass should drive it directly (`am send-trim-memory <pid> HIDDEN`)
or measure a build without the foreground service.

**(c) Two renderer items from rc1 are still open and still unverified on hardware.**

- `vk_master_semaphore`'s own `UNREACHABLE()` on a lost device in the submit/wait path is not
  covered by rc1's Vulkan recovery work. A real device loss reached from the emulation thread
  will still abort the process.
- `doFrame()` reads `window` while `TryShutdown()` resets it (`native.cpp`) — a use-after-free
  on a guest-initiated shutdown ("power off" from the HOME menu). The other half of that pair
  landed in rc1; this half did not.

Neither has been seen in the field and neither has been reproduced on a device. They are
listed because they are known, not because they are expected.

**Also still true from rc1**, unchanged here: no *performance* numbers have ever been taken on
the Thor itself (its identifiers, OS level, Vulkan driver, memory, displays and crash history
were read read-only on 2026-09-09 — running the emulator on it is still owed); the sleep-key/deep-suspend soak is still not automated (nothing in either measurement
pass sent `KEYCODE_SLEEP` or `POWER`, both phones were awake throughout), so everything about
real suspend is reasoned from code; and an autosave request that arrives while the emulation
thread is blocked is not served until it unblocks, with the UI thread giving up after 4 s.

## How to install

Download `azahar-thor-v1-rc2.apk` from the release page, then either open it from a file
manager on the Thor (it needs "install unknown apps" for that file manager), or:

```
adb install -r azahar-thor-v1-rc2.apk
```

Notes:

- This build's `versionCode` is the build timestamp, which is above rc1's `33730851`, so it
  installs over your rc1 cleanly. It is not test-only, so plain `adb install` works and no
  `-t` is needed.
- The install replaces the app but leaves `/sdcard/azahar` alone — config, saves, states and
  the shader cache all survive. Do **not** uninstall: a full uninstall drops the private
  SharedPreferences holding the SAF grant and the user-directory path, which drops you into
  the first-run wizard even though the data is still there.
- **Savestates DO survive between our release candidates.** ~~Savestates from another build
  are rejected by design.~~ This bullet was wrong when it shipped, and Marty disproved it in
  the field on 2026-09-09 by loading an rc1 autosave in rc2, in Animal Crossing, with no
  trouble. `ValidateSaveState` (`src/core/savestate.cpp:78-91`) has three outcomes, not two:
  a differing git revision with a matching `Common::g_build_version` is `RevisionMismatch`,
  which still loads, and only a differing build version is the fatal `BuildMismatch`. rc1 and
  rc2 share an upstream base version, so they are compatible — and they are compatible
  *because of our own change*, `MakeHeader` now writing `build_version` into file headers.
  The real caveat is much narrower: an autosave will only be refused across a **rebase onto a
  new upstream release**, where `g_build_version` itself changes. Saving in-game before an
  upgrade is still never a bad habit; it is not a requirement between our own RCs.
- Then run **Settings > System > AYN Thor > "Apply Thor defaults"**. See the top of this
  document for why.

## What to test on the Thor

Everything here needs the Thor's own lid, its second panel, or real deep suspend, none of
which the lab phone can produce.

1. **Both panels, after "Apply Thor defaults".** The 3DS top screen should fill the 6" top
   panel with nothing letterboxed, and the touch screen should fill the 3.92" bottom panel.
   Settings > Graphics should read Vulkan, internal resolution 4x, texture filter None, disk
   shader cache and asynchronous shader compilation on. Settings > System should read autosave
   Always.
2. **Lid cycle soak.** Close the lid mid-game, leave it 30 s, open it — twenty times. The
   bottom panel must be presenting again within about a second of every wake, with no black
   flash and no lock-up. This is the change with the most moving parts in the release and the
   one the lab phone could not exercise at all.
3. **Lid close for 30+ minutes.** The game continues where it was, or (with autosave Always,
   which the profile sets) the app restarts and resumes from the autosave. Either is a pass; a
   black screen, a hang or the title screen is not. Check the in-game clock afterwards — it
   should read the real current time.
4. **Top-panel touch does nothing.** In ACNL, touch the upper screen: nothing should happen in
   game. The bottom panel must keep working normally. If the top panel still registers as the
   touchscreen, that is a regression in this build's headline display fix.
5. **Resume boot time, by feel.** Time an autosave resume against your memory of rc1, and
   watch for the stutter storm that used to follow a resume while pipelines recompiled — that
   is what the shader-cache fix removes.
6. **The pad after a kill.** Background the game with the emulation drawer open, let Android
   kill it (or force-stop it), then relaunch from the launcher. The drawer must not come back
   open, and the pad must drive the game rather than the menu. This is the fix for what you
   reported on rc1.
7. **No touch overlay with the built-in pad.** No on-screen controls should be drawn over the
   game. Turning them on from the overlay menu must keep them on across a restart.
8. **Battery overnight with the game paused.** Leave a game paused (lid closed) overnight;
   check drain and whether the process survived. `adb shell dumpsys activity exit-info
   org.azahar_emu.azahar.thor` says why if it did not.
9. **Do not change the texture filter while a game is running.** See Known issues (a). If you
   want to try a filter, set it from the main menu before launching.
