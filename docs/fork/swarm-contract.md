
## Wave 2 (started 2026-09-08 ~19:30, all agents on Opus; wave 1 merged at 009cfe333)

| Agent | Branch | Items | Device? | Status |
|---|---|---|---|---|
| agent-measure-rc1 | (none; measures) | baseline b8aa5f893 vs candidate 009cfe333 with tools/thor/measure.sh | yes, first | done |
| agent-thor-display | `fix/thor-display` | T-2, T-3, T-4 | host only now | merged |
| agent-secondary-window | `perf/secondary-window` | G-1 | host only now | merged |
| agent-trim | `perf/trim-memory` | M-2 | host only now | merged |
| agent-boot | `perf/boot` | L-1, G-3, L-2 | host only now | merged |
| agent-thor-defaults | `feat/thor-defaults` | T-6 | host only now | merged |
| agent-resume-input | `fix/resume-input-focus` | no plan id: unmapped-pad key fallback, drawer focus, restored-open drawer | host only now | merged |

Wave 2 rule: agents verify on the host (build, tests, Kotlin compile, APK builds) and leave device measurement to a later measurement pass; the phone belongs to agent-measure-rc1. Release flow: after measurement, an Opus release agent writes `docs/fork/releases/thor-v1-rc1.md` from the measurement table and the commit bodies, tags `thor-v1-rc1`, and copies the APK to `~/Development/azahar-builds/azahar-thor-v1-rc1.apk`.

Wave 2 was merged into `thor/main` in the order thor-display → secondary-window → trim-memory → boot → thor-defaults → resume-input-focus, each merge verified with `compileThorReleaseKotlin`, and the set verified once at the end with the host `tests` binary and one `assembleThorRelease`. Every device claim in those commit bodies is still unverified; the measurement pass owes rc2 its numbers.

## Wave 3 candidates

In progress, not merged — the branch may be mid-edit, so leave it to its agent:

- `fix/texture-filter-crash` (agent-texfilter).

Then, from `docs/fork/improvement-plan.md` §3, what Tier 2 and Tier 3 still have open:

**Tier 2**
- **L-4 / M-3** — FCRAM/VRAM/DSP allocated with `mmap(MAP_ANONYMOUS)` instead of a memset, FCRAM sized to 128 MiB when `!is_new_3ds`, `PageTable` built once. Biggest remaining resident-memory win; needs the savestate tests.
- **G-6** — re-test the timeline-semaphore blacklist on Turnip, and a driver-version gate for the Qualcomm EDS / custom-border-color blacklists. Device-serial, high risk.
- **R-10** — `doFrame()` reads `window` while `TryShutdown()` resets it (`native.cpp`), a use-after-free on guest shutdown. R-9 landed in wave 1; this half did not.

**Tier T (rank alongside Tier 1 for this device)**
- **T-5** — scheduling experiment: emu-thread priority, optional affinity, an ADPF hint session. Setting-gated, must show a win on the Thor or stay off.
- **T-7** — lid-vs-Home distinction (`onUserLeaveHint` vs `DisplayListener`), so a lid close can autosave and let the process freeze while Home keeps the foreground service.
- **T-8** — lid-cycle and dual-layer measurement kit extending `tools/thor/measure.sh` (Perfetto frametimeline + layers, per-thread last-CPU histogram, cpufreq/thermal sampler). Enables verifying T-2/T-4/T-5.

**Tier 3**
- **R-4** — release `surface_mutex` before `System::Load`, a stop token for `recreate_surface_cv`, and the recursive-mutex `adopt_lock` wait in the init callback. Removes the ANR-on-boot and the shutdown deadlock.
- **R-2** — classify `VK_ERROR_DEVICE_LOST` and unexpected acquire/present results into a renderer rebuild instead of `UNREACHABLE`, and bound the retry loop in `GetRenderFrame`. Partly pre-empted by the wave-1 surface work; what is left is the device-lost rebuild.
- **M-7** — texture-cache byte budget with LRU eviction of unreferenced surfaces.
- **M-4** — `MemoryRef` 40 B → ≤16 B in `PageTable::refs`. Touches every `GetPointer` path and the savestate format.
- **G-7** — cache `StaticPipelineInfo::Hash()` per pipeline and hash only non-padding fields.
- **G-9** — dynarmic page-table pointer mask / absolute offset, and drop the throwaway null-page-table JIT.
- **M-6** — custom-texture preload budget from `largeMemoryClass` rather than total RAM. Latent only.

## Fold5 version-code rule (added 2026-09-08 night)

Every APK that will be installed on the Fold5 is built with `-PversionCodeOverride=40000000`, so any build installs over any other (the default version code is a build timestamp and Android refuses to downgrade a release build). Thor release builds keep the default timestamp code. Lock files live ONLY under the session scratchpad path named above; never create a `scratchpad/` directory inside the repo.

## Device gotchas learned the hard way (2026-09-09)

- **Hold the lock before ANY adb action against the phone**, including a bare `screencap` or
  `dumpsys`. An agent that prepared scripts while merely queued photographed a different
  agent's app and nearly reported those pixels as its own result.
- **`topResumedActivity` is per display.** On the Fold5 it can correctly name your activity
  while another app owns display 0's pixels. Check `dumpsys window | grep mCurrentFocus`
  before and after any screen capture, and discard the frame if it changed.
- **The Fold5 cover screen is 904x2316 and its bottom gesture strip starts around y=2185.**
  Taps below that are swallowed as swipes and open Recents. Scroll a target above the strip
  before tapping it; fixed bottom buttons at y~2127 are safe.
- **scrcpy contaminates measurements** (it adds a virtual display and an encoder). Record
  whether it was running; never compare numbers taken with it against numbers taken without.
- **Never trust a screenshot you did not prove is fresh.** `screencap` can fail while an
  earlier PNG is still on the device, and `adb pull` will happily return that stale image.
  An agent nearly reported a renderer fix as verified across four configurations using a
  photograph of a different app taken before the test started; the tell was a clock inside
  the image reading 45 minutes earlier, and byte-identical measurements across every config.
  Write each capture to a unique remote path, delete it first, check it exists and is over
  1 KB on the device, do not swallow `screencap`'s stderr, delete the local file before
  pulling, and emit a sentinel rather than a number if any of that fails. Identical values
  across supposedly different configurations mean the harness is broken, not that the fix
  worked.

## Sleep and wake on the Fold5 (revised 2026-09-09)

The blanket "never send sleep keys" rule is replaced by this. Marty keeps a PIN on the lab
phone and prefers to unlock on request rather than disable it.

- **Only the agent currently holding `device.lock` may send `KEYCODE_SLEEP` / `KEYCODE_WAKEUP`**,
  and it must wake the device before releasing the lock. Sleeping the phone while another
  agent is mid-run breaks them, which is what the original ban was protecting against.
- **A locked screen does not block most measurement.** `dumpsys`, `/proc` and `/sys` reads,
  per-thread CPU deltas, `exit-info`, meminfo, suspend counters and wake-lock listings all
  work normally while the keyguard is up. An app launched *before* the sleep keeps running
  behind the lock screen. So a full sleep/measure/wake cycle needs no human.
- **What does need a human** is anything touching the UI after a wake: launching an app,
  tapping, `uiautomator` dumps, or reading app content from a screenshot. For those, stop and
  ask for an unlock at that exact point rather than failing or working around it. Batch such
  steps together so one unlock covers them all.
- Never change the lock setting yourself.
