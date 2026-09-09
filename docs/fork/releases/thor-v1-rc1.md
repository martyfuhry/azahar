# thor-v1-rc1

Release candidate 1 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## What this build is

Upstream Azahar `master` at `073110cb4` ("tools: Added basic-mxe-config.sh script") plus
the fork's own work, merged on the `thor/main` integration branch. Nothing in this build
is upstream Azahar code that has been version-bumped or re-branded: the fork commits sit
on top of an unmodified upstream tree.

Package `org.azahar_emu.azahar`, arm64-v8a only, vanilla flavour, release build, signed
with the Android debug key. It uses the same package name as an official Azahar build but
a different signature, so it will not upgrade one; it replaces, and is replaced by, other
builds of this fork.

The fork's work is contained in these areas: suspend/resume stability, background CPU and
GPU memory, a first-launch controller mapping for the built-in pad, and a measurement kit.
No emulation accuracy work, no new graphics features, no UI redesign.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc1.apk` |
| Built from | `b070d5a3a` (clean tree) |
| versionName | `b070d5a3a-vanilla` |
| versionCode | `33730700` |
| sha256 | `738de89e2054e23013da2141bc1c4ef974f74d4216b628fbcbe77b053679963a` |
| Size | 35 959 108 bytes |

`b070d5a3a` is this commit before the artifact rows were filled in — a checksum of an
artifact cannot be committed into the tree the artifact is built from. The `thor-v1-rc1`
tag is on the amended commit, so the shipped APK's `versionName` and its `Azahar Version:`
log banner read `b070d5a3a` rather than the tagged commit's hash. The two trees differ
only in this file; no code, resource or build file changed between them.

## What changed

### (a) Stability on suspend and resume

This is the reason the fork exists. On the Thor a lid close, a screen blank or a long pause
would routinely cost the session: the process was killed while backgrounded, the Vulkan
swapchain aborted on the first frame after a wake, or the launcher's re-sent intent
restarted the game from the title screen.

**Guest clock resync.** Emulated time only advances while the emulation thread runs, so a
paused emulator's 3DS clock froze for the whole length of the pause; a game left asleep
overnight woke up believing it was still yesterday evening, which matters for anything
that reads the clock (Animal Crossing, daily events, streetpass timers). The shared page
handler now advances the guest clock by the host time that elapsed while emulated time
stood still, the way PTM does when a real console wakes from sleep, and every resume path
(device wake, pause menu, returning from another activity) requests it. The correction is
measured against the host and emulated time sampled at boot, so the clock never moves
backwards and repeated resyncs cannot apply the same interval twice. The same anchoring
now happens at boot and after a savestate load, which previously left the guest ahead of
the host by the entire emulated uptime of the saved session. Fixed clocks and movie
overrides are untouched.

**Autosave on background, resume on next launch.** New setting `autosave_mode`, in the
Android UI under **Settings > System > Autosave > "Save state on exit"**, three values:

- **Off** (default) — never writes or loads an autosave.
- **Ask before resuming** — writes a state whenever emulation is backgrounded or shut
  down, and offers it in a dialog on the next boot of the same title.
- **Always resume** — writes it and loads it on the next boot without asking.

**It is Off by default; you have to turn it on.** In `config.ini` the key is
`autosave_mode` under `[System]` (0 = Off, 1 = Ask, 2 = Always).

The state goes to a reserved slot (1000, written as `<title>.autosave.cst`) that no slot
picker shows and no manual save can overwrite. The save is handed from the UI thread to
the emulation thread, which performs it between `RunLoop` slices; `onStop` waits up to 4 s
so the write has landed before Android considers the app stopped. On the next boot the
autosave is only offered when it is newer than the last recorded boot of that title, so a
state can never roll back progress you made after declining it once; a state from an
incompatible build is skipped with a toast naming that build.

Savestates now stream through zstd instead of being materialised three times in memory
(serialize buffer, string copy, compressed copy). A New 3DS state is a few hundred MiB, so
the old path's peak was two uncompressed images plus the compressed one — allocated at
exactly the moment the app is going to the background on a memory-tight phone, which is to
say it was itself a likely trigger of the low-memory kill it was supposed to insure
against. Files are written to `.tmp` and renamed into place, so a kill part-way through a
save leaves the previous state intact. States written by earlier builds still load.

**Foreground service.** Emulation now runs under a `specialUse` foreground service for the
lifetime of `EmulationActivity`, which takes the process out of the cached tiers the
low-memory killer reclaims first. It is started in `onCreate`, stopped in `onDestroy`, and
`stopWithTask` ends it if you swipe the task out of Recents. A low-importance "Azahar is
Running" notification goes with it; tapping it returns to the running game rather than
relaunching it. On Android 13+ the notification is hidden until POST_NOTIFICATIONS is
granted — the service runs either way, the permission only controls whether you see the
notification.

**Vulkan swapchain and surface.** Three separate aborts on resume are fixed:

- Every acquire or present result other than success / suboptimal / surface lost / out of
  date hit `UNREACHABLE()` and took the process down as `REASON_CRASH_NATIVE`. On Android
  the GPU is suspended with the screen, and the Adreno driver may answer the first acquire
  after a wake with `VK_ERROR_DEVICE_LOST` or `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`
  (upstream #2455) — every one of those paths aborted the process and the game with it.
  Results are now classified by what could fix them: surface-class results wait for the next
  surface the frontend hands over, device-class results recreate on the surface already
  held. Recreation attempts are capped (8), and the fence wait in `GetRenderFrame()`, which
  used to spin forever on an unexpected result, is capped too, so a dead device can no
  longer pin the emulation thread at 100 % of a core. When the cap is reached the window is
  marked lost and the core raises one error dialog — you can still stop the game and write
  a save, the guest state is intact.
- `Swapchain::Create()` overwrote its surface handle without destroying the old one.
  Android hands over a new `Surface` on every rotation and every return from background, so
  each cycle leaked a `VkSurfaceKHR` and its reference to a torn-down window. Fixing the
  leak exposed a second bug — the present window remembered the last `ANativeWindow`
  address and `surfaceDestroyed()` never cleared it, so once addresses started being
  recycled a new window was mistaken for the old one and the present thread waited for it
  forever (black screen after resume). Both halves are fixed together.
- `Swapchain::Destroy()` destroyed semaphores by a stale count instead of iterating the
  vectors, so a `Create()` that bailed out on `VK_ERROR_SURFACE_LOST_KHR` (which Android
  produces when the app is slept or rotated while the previous surface is being replaced)
  made the next `Destroy()` pass already-destroyed handles to `vkDestroySemaphore` — an
  abort on resume.

**Lifecycle.** Four distinct ways to lose a session, all fixed:

- *A re-sent launch intent no longer restarts the game.* `EmulationActivity` is
  `singleTop`, so a launcher or shortcut firing the launch intent while the game is up
  landed in `onNewIntent`, which stopped the running title and booted the intent's title
  from scratch, whichever title it was — and the Thor's launcher re-sends the intent on
  every lid open (upstream #2329). An intent targeting the title already running or booting
  now resumes in place; a genuinely different title still goes through the
  autosave-then-stop-then-reload path.
- *Rotation and fold no longer recreate the activity.* A rotation, a fold/unfold or a theme
  change destroyed and rebuilt `EmulationActivity`, which destroys the render surface,
  restarts the foreground service and races the emulation thread's surface recreation.
  `configChanges` now covers orientation, screen size, screen layout, smallest width, UI
  mode and density, and `onConfigurationChanged` re-applies what `onCreate` used to derive
  from the configuration.
- *A pause during boot now takes effect.* Backgrounding Azahar while the loading screen was
  up left the guest running headless, with audio, behind the launcher: the Kotlin side
  only paused when the core reported itself running, and `RunCitra` reset the pause flag
  after the load anyway. A pause that arrives during boot is now parked and applied when
  the loop starts.
- *A restore after a kill no longer lies about being ready.* The saved instance state
  outlives the process, so after Android killed a backgrounded Azahar and later restored
  the task, `isEmulationReady=true` was handed to a fresh boot: loading UI hidden over a
  black surface, savestates menu computed against no core, rotation unblocked mid-renderer-
  creation. The saved flags are now trusted only when the core actually survived.

Also here: the audio stream is stopped rather than muted while paused (see the CPU number
below); the Vulkan driver pipeline cache is flushed to disk when emulation is paused and
every five minutes, so pipelines compiled during a session survive a background kill and
the next boot does not recompile them; and a guest-initiated shutdown can no longer race
`doFrame()` into a destroyed window.

### (b) Performance and memory

Measured before/after, copied from `docs/fork/baselines/2026-09-08-rc1-vs-baseline.md`.

**Read the caveats first.** These numbers were taken on a **Galaxy Z Fold5** (SM-F946U1,
Android 16, Snapdragon 8 Gen 2 — the same SoC as the Thor, not the same device, not the
same panel, not the same thermal envelope), **not on the Thor**. They are **one run per
metric**, not a median of three: the device window was 25 minutes and repeats were not
affordable. **Treat any difference under about 5 % as noise.** The phone was folded on its
cover panel at 120 Hz, awake throughout, scrcpy off, running Animal Crossing: New Leaf
resumed from the same autosave on both builds.

Baseline is `b8aa5f893` (the fork before this wave of work). Candidate is `009cfe333`,
which differs from this release only by documentation and a Gradle `versionCodeOverride`
property — no runtime code.

| Metric | Baseline | This build | Delta |
|---|---|---|---|
| Paused CPU after HOME, 30 s (% of one core) | 8.19 | 0.33 | **−7.86 pp (−96 %)** |
| GL mtrack at 45 s (kB) | 770 552 | 311 980 | **−458 572 (−59.5 %)** |
| TOTAL PSS at 45 s (kB) | 1 702 451 | 1 243 443 | **−459 008 (−27.0 %)** |
| TOTAL PSS after HOME (kB) | 1 667 326 | 1 209 976 | −457 350 (−27.4 %) |
| GL mtrack after HOME (kB) | 769 296 | 310 528 | −458 768 |
| Native heap at 45 s (kB) | 616 007 | 615 655 | −352 |
| EGL mtrack at 45 s (kB) | 126 316 | 126 316 | 0 |
| VmRSS at 45 s (kB) | 925 840 | 927 600 | +1 760 (+0.2 %) |
| Threads at 45 s | 55 | 55 | 0 |
| Foreground CPU, 20 s (% of one core) | 62.73 | 59.31 | −3.42 pp |
| Cold boot, autosave ignored: intent → first frame (ms) | 2162 | 2066 | −96 (−4.4 %) |
| Cold boot, autosave resume: intent → first frame (ms) | 3535 | 3374 | −161 (−4.6 %) |
| Savestate load phase (ms) | 1664 | 1562 | −102 |
| p50 present interval, 30 s (ms) | 16 | 16 | 0 |
| p95 present interval, 30 s (ms) | 25 | 25 | 0 |
| p99 present interval, 30 s (ms) | 33 | 33 | 0 |
| Janky frames, 30 s | 0 / 1797 | 0 / 1798 | 0 |
| Average FPS, 30 s | 60.630 | 60.620 | −0.01 |

**Paused CPU, 8.2 % → 0.3 %.** With the game paused behind HOME the baseline's AAudio
callback thread kept burning 7.82 % of a core producing silence, because the Android
frontend only set the output volume to zero. The stream is now stopped outright through a
new `Sink::SetPaused` (cubeb / SDL2 / OpenAL implementations), and the callback itself is
made cheap when muted or at real-time speed. Android's excessive-CPU kill for background
processes triggers above 2 % over five minutes, so the baseline was roughly 4x over the
line that gets the game killed and this build is well inside it. Also contributing: the
perf overlay's one-second timer is cancelled while the fragment is paused, and the
emulation thread no longer runs during the boot window if you background the app mid-load.

**GPU memory, 770 MB → 312 MB.** The Vulkan texture upload ring was a 512 MiB host-visible,
persistently mapped buffer. It is walked monotonically, so over a session every page of it
gets touched and stays resident — neither swappable nor visible to the cached-app freezer.
With custom textures off, the largest request it ever serves is one 1024x1024 guest texture
(4–8 MiB). The ring is now 64 MiB, and any request larger than a quarter of it is served by
a dedicated one-shot buffer instead, so the runtime cannot run out of ring (which used to
be a hard assert). This single change accounts for the entire 27 % PSS reduction — native
heap, EGL and VmRSS are unchanged — and it holds both in the foreground and behind HOME, so
it is a smaller steady-state allocation rather than a deferred one.

**Frame pacing is unchanged.** Identical p50/p95/p99 buckets, zero janky frames on both
builds. The 16/25/33 ms split is the expected behaviour of a 60 fps guest on a 120 Hz
panel. There is no regression here, and no visible headroom either. (One frame-path change
did land: skipped duplicate frames now `Flush()` instead of `Finish()`, dropping a full GPU
drain per skipped vblank — for a 30 fps title that is one drain every other vblank. It did
not move this measurement.)

**Boot is about 4 % faster**, entirely in the savestate load phase; the pre-load phases are
within a few milliseconds of each other. At one run per scenario, this is at the edge of
what can be claimed.

Not in the table, because the baseline cannot produce it: this build logs a `perf:` summary
line (see Diagnostics). Over the 30 s window it read `game_fps=59.52 speed=99.33 %
frametime=5.70 ms gpu=1.41 ms swap=0.13 ms ipc=0.05 ms svc=0.06 ms rem=4.06 ms`.

### (c) Controller

A fresh install had no controller bindings at all, so the Thor's built-in pad did nothing
in game until every button was mapped by hand or the Auto-Map row in Controls was found and
used. Worse, the unmapped `KEYCODE_BUTTON_B` fell through to Android as BACK and opened the
emulation drawer mid-game (upstream #1101).

This build seeds a default mapping the first time it sees a physical gamepad — from
`EmulationActivity.onCreate` for a pad already connected (the handheld case) and from the
first key or motion event for a pad that appears later. It writes exactly the same tables
the Auto-Map dialog writes; the only difference is that the face-button layout is looked up
by USB vendor/product ID rather than learned from a press of A.

**What to expect on the Thor.** The Thor reports its pad as "Odin Controller", vendor
`0x2020`, product `0x0111`. In Thor mode the east button is labelled A and sends
`KEYCODE_BUTTON_A`, so it takes the Nintendo layout and the shell's printed labels match
the 3DS labels. If you flip the system-wide layout switch to Xbox, the pad re-enumerates as
product `0x0112` with the south button on `KEYCODE_BUTTON_A` and takes the Xbox
(positional) layout. Unknown pads get the positional Xbox table, which is what most Android
controllers report. Joy-Cons keep their existing special case. D-pad detection picks hat
axes or the four D-pad keycodes based on what the device reports.

**When it does and does not run.** Seeding is recorded under a key outside the
`InputMapping` prefix, so it happens at most once per install. If you have already mapped
even one button, it will never touch your bindings, and clearing all bindings does not
bring the defaults back.

**How to reset.** Settings > Controls > **Auto-Map Controller**, then press A on the pad
when prompted. That remains the explicit reset and it overwrites whatever is there.

### (d) Diagnostics

**`perf_log_interval`** — Settings > **Debug** > "Log performance summary", a 0–60 s slider
(`perf_log_interval` under `[Debugging]` in `config.ini`; 0, the default, is off). At the
chosen interval the emulation loop writes one line to the log:

```
perf: game_fps=59.9 system_fps=60.0 speed=100% frametime=9.87ms gpu=1.23ms swap=2.34ms ipc=0.45ms svc=0.12ms rem=5.73ms
```

It goes to `logcat -s CitraNative` and to `/sdcard/azahar/log/azahar_log.txt`, so a session
nobody was watching still leaves numbers. It shares counters with the on-screen overlay, so
running both just makes each window shorter (and therefore noisier); every figure is a rate
or a per-frame mean. Related: recorded frame times (`record_frame_times`) are now flushed
to the CSV every ten seconds instead of only from the destructor, so a session that ends in
a kill still leaves its trace.

**`tools/thor/measure.sh`** — the host-side measurement kit, driven over adb. Run
`identity` first, every time, and never attribute a number to a build without it matching.

```
export ANDROID_SERIAL=<serial>
tools/thor/measure.sh identity          # versionName/versionCode, log banner, pid, wakefulness, scrcpy check
tools/thor/measure.sh cpu 60            # per-thread CPU deltas + process total + the excessive-CPU line
tools/thor/measure.sh mem               # PSS / GL / EGL / native heap / VmRSS
tools/thor/measure.sh boot              # intent-to-first-frame timeline from epoch-stamped logcat
tools/thor/measure.sh frames 30         # SurfaceFlinger present-to-present buckets, p50/p95/p99
tools/thor/measure.sh perf              # the perf: lines above
tools/thor/measure.sh exitinfo          # condensed exit-info, saveable and diffable for soaks
tools/thor/measure.sh soak              # play, HOME, screen off, wait, wake, relaunch, report
tools/thor/measure.sh install <apk>     # install and refuse to succeed unless the versionName took
```

Raw captures are kept under `measure-out/<timestamp>/` so any number can be traced back.
`tools/thor/README.md` documents each subcommand and the conventions. The release build is
declared `<profileable android:shell="true"/>`, so `simpleperf` can attach to it — that is
the build that actually runs on the Thor, so profiling no longer requires a different APK
with different behaviour.

**Kill reasons** — after any disappearance, ask the system why:

```
adb shell dumpsys activity exit-info org.azahar_emu.azahar
```

The reason code is what distinguishes the cases: `REASON_LOW_MEMORY` / `REASON_OTHER` with
an "excessive cpu" description means the mitigations above are not enough,
`REASON_CRASH_NATIVE` means an abort (which is what the Vulkan work was fixing), and
`REASON_USER_REQUESTED` is just a force-stop.

## How to install

```
adb install -r -d -t /home/martyfuhry/Development/azahar-builds/azahar-thor-v1-rc1.apk
```

Or copy the APK to the Thor and open it from a file manager (needs "install unknown apps"
for that file manager).

Notes:

- `-t` is required because the APK is debug-signed. `-d` asks to allow a downgrade, but
  Android ignores it for a non-debuggable package, so a build whose `versionCode` is lower
  than what the Thor already carries fails with `INSTALL_FAILED_VERSION_DOWNGRADE` — and
  `adb` still prints `Success` for the streamed part, so check with `dumpsys package
  org.azahar_emu.azahar | grep versionName` afterwards. This build's `versionCode` is
  33730700 (the build timestamp), above every earlier fork build, so it installs cleanly
  over any of them.
- The install replaces the app but leaves `/sdcard/azahar` alone — config, saves, states and
  the shader cache all survive. Do **not** uninstall: a full uninstall drops the private
  SharedPreferences holding the SAF grant and the user-directory path, which drops you into
  the first-run wizard even though the data is still there.
- **Savestates from another build are rejected by design.** A `.cst` file carries the git
  revision it was written by, and loading one from a different build fails with a build
  mismatch rather than corrupting a session; the autosave from a previous build is skipped
  with a toast rather than loaded. So **save in-game before you install this**, and do not
  count on an existing savestate or autosave surviving the update.

## What to test on the Thor

These are the cases that could not be automated on the lab phone, either because they need
the Thor's lid or because they need real deep-suspend rather than a `KEYCODE_SLEEP`.

1. **Close the lid mid-game, leave it 30+ minutes, open it.** The expected outcomes are:
   the game continues where it was; or, with `autosave_mode` set to Ask/Always, the app
   restarts and resumes from the autosave. Either is a pass. A black screen, a hang, or a
   return to the title screen is not. Check the guest clock in-game afterwards — it should
   read the real current time, not the time you closed the lid.
2. **Bottom screen after a lid open.** Confirm the secondary display is still on and still
   showing the bottom screen after the lid cycle, and that touch on it still works. The
   surface-destroy path for the secondary display is fixed in this build, but the wider
   dual-screen work is not in it (see Known gaps).
3. **Home and return.** Press Home mid-game, wait, tap the "Azahar is Running"
   notification, and confirm you land back in the running game rather than a fresh boot.
   Repeat with the launcher icon instead of the notification — that is the re-sent-intent
   path, and it should also resume in place.
4. **Battery overnight with the game paused.** Leave a game paused (Home, or lid closed)
   overnight and check the battery drain and whether the process is still alive in the
   morning. If it died, `adb shell dumpsys activity exit-info org.azahar_emu.azahar` will
   say why. The paused CPU number above predicts this should now be nearly free, but that
   was 30 seconds on a different phone, not eight hours on the Thor.
5. **Turn on `autosave_mode` first.** It is Off by default. Set it to Always (or Ask) in
   Settings > System > Autosave before starting, or none of the resume-after-kill behaviour
   is exercised at all.

## Known gaps

- **The sleep-key soak is not automated.** The measurement pass never sent
  `KEYCODE_SLEEP`/`POWER`; the phone was awake for its whole session, so the screen-off CPU
  state and the wake-from-deep-suspend path have no before/after numbers. Everything about
  suspend in this document is reasoned from code and from the HOME-with-screen-on
  measurements. Test 1 above is the real check.
- **All numbers are single-run, on a Galaxy Z Fold5, not on the Thor.** Same SoC, different
  device. Nothing here has been measured on the target hardware.
- **Secondary-display and Thor-specific display work is wave 2 and is not in this build.**
  The branches `perf/secondary-window`, `fix/thor-display`, `perf/trim-memory` and
  `perf/boot` exist but are not merged into `thor/main`. What is in this build for the
  second screen is only the surface-lifecycle fix (`secondarySurfaceDestroyed` now takes
  the same path as the primary).
- **Vulkan device loss inside the scheduler is still fatal.** `vk_master_semaphore`'s own
  `UNREACHABLE()` on a lost device in the submit/wait path is not covered by the recovery
  work; a real device loss reached from the emulation thread will still abort.
- **Autosave spike limits.** A save request that arrives while the emulation thread is
  blocked (a core error dialog, or a savestate load waiting for a surface) is not served
  until it unblocks, and the UI thread gives up after 4 s. The Ask dialog is a plain dialog
  and does not survive a configuration change. Multi-window pauses save but do not wait.
- **The version banner in worktree builds is fixed.** Builds made from a git worktree used
  to stamp `Azahar Version: UNKNOWN | UNKNOWN-UNKNOWN` into the log and into every savestate
  header, because the CMake build-info step only looked for `.git/objects` and a worktree's
  `.git` is a file. Both the identity check and savestate build matching now work from a
  worktree build. Any savestate written by an affected build carries the UNKNOWN revision
  and will not load here.
