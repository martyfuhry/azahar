# thor-v1-rc7

Release candidate 7 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## What this is

**rc6 plus 21 upstream commits** (to `b8c29a64c`, 2026-09-22), one fork fix that the merge
made necessary, and one fix for a boot freeze found on the Thor on 2026-09-24. Eleven of the
upstream commits were merged on 2026-09-14 and never released; the other ten landed since.

If you are coming from **rc4**, as the Thor is: read rc5's and rc6's notes too. The one visible
change in them is that "Skip present duplicate frames" now defaults to off, following upstream.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc7.apk` |
| Built from | `c68efd933` (clean tree) |
| versionName | `thor-v1-rc7-thor` |
| versionCode | `33873705` |
| sha256 | `da623e361f76146413f19b954106ba33c1969279d63158cb3bf96fb1b89f8bba` |
| Size | 50 461 019 bytes |
| ABIs | arm64-v8a, x86_64 |
| Upstream base | `b8c29a64c` |

`c68efd933` is this commit before the artifact rows were filled in; the tag is on the amended
commit, as with rc1 through rc6. Unstripped symbols are at
`~/Development/azahar-builds/azahar-symbols-rc7/`, build id
`3c1fc1153fe9234f2af07530b06c79c67d0e0393`, matching the shipped library.

## The fork fix: your autosave survives this upgrade

Upstream's new HLE audio delay effect (#2473) added two fields to the DSP mixer's savestate, in
the middle of it, with no version. Upstream's own releases are safe because every release
changes the build version and older states are refused. **This fork's builds all carry the same
build version**, which is exactly what lets your autosave carry over between our release
candidates — and it meant rc7 would have *accepted* an rc6-or-older state and read it with the
new layout. The DSP is restored before emulated memory and the kernel, so everything after the
mixer would have been read from the wrong offset. In testing that throws partway through the
load, after the emulator has already been torn down for it; on first launch of a new build that
is the autosave resume path.

`42d91f0fa` versions the mixer state so old states load with the effect at its default, which
is what the DSP had when they were written. `778a0ebb8` writes the old layout and proves the
bytes after it land where they should; with the fix removed the test fails with an exception.

This is the concrete case for plan item **S-1** (a savestate format fingerprint): the next
upstream change to a serialized struct will not necessarily be one somebody notices.

## The fork fix: resuming a game no longer recompiles every pipeline

Found on the Thor on rc4: Super Mario 3D Land resumed from its autosave and then sat on
"Initializing…" and a frozen picture for about **75 seconds**, while four `Pipeline worker`
threads compiled at 100% (~265 core-seconds) and the emulator waited.

The cause was ours (`6b041f4cf`, keeping the disk shader cache across an autosave-resume boot).
On a resume, loading the savestate builds a new renderer and loads the title's caches; the boot
path then loaded **the same caches a second time**. The second load first waited for every
pipeline the first one had queued, then replaced the freshly compiled driver cache with the copy on
disk without saving it, and queued everything again. On the way in, the new renderer's empty cache
was saved under title id `0000000000000000`, which is the 32-byte file you will find in
`shaders/vulkan/pipeline/`. ACNL never showed it because its driver cache on disk is valid, so
both loads were near free (it still cost ~1.6 s of redundant cache loading, now gone). SM3DL's
7.6 MB driver cache dated from 2026-08-28 and matched nothing — most likely written by stock
Azahar, which shares `/sdcard/azahar` — so every pipeline missed, on every resume.

`613c50c73` tracks which title and shader profile the caches were loaded for: a repeat load of the
same caches is skipped, a cache that was never loaded is never saved, saves always go to the title
the cache belongs to, and the outgoing cache is saved before it is replaced. `3fba2a770` puts that
logic under test; with the old logic restored the tests fail.

**Verified on the Fold5:** before the fix, rc7 wrote the title-0 file again on an ACNL resume.
With it, SM3DL cold boot then resume: no title-0 file, the cache is saved under SM3DL's own title
on HOME, and the resume loads the cache once, logs `Disk caches ... are already loaded`, and
reaches its first frame in 1.5 s. **Not yet seen:** a resumed session saving a cache that grew
during play. On the Thor, the sign it works is `0004000000054000-*.bin` getting a new date after
you play.

**Worth knowing:** stock Azahar and this build share `/sdcard/azahar`, so each can overwrite the
other's shader caches. Launch 3DS games only through the Thor build.

## What upstream changed

**Graphics (Vulkan and shared)**

- **Texture codec rewrite** (#2564). Roughly 950 lines in `texture_codec.h`: the CPU-side decode
  of 3DS texture formats. Likely a speed-up for anything that uploads textures often; not
  measured here.
- **Null descriptors for unused texture slots** (#2545). With `VK_EXT_robustness2`, empty slots
  bind a null handle instead of a black texture, so the GPU stops sampling textures that
  contribute nothing.
- **Shadow-texture slot fix** and **relaxed storage image-view requirements** (#2531). A stray
  texture was being bound as the shadow map (Monster Hunter 4 Ultimate's equipment screen).
- **Zero-area renderpasses are culled on both renderers** (#2544), which also fixes the
  height-times-height typo rc6's notes flagged, so that upstream draft is withdrawn.
- **Nearest-neighbour scaling in surface blits.** Scaled copies no longer filter, which is
  correct for data that is not an image.

**Audio**

- **HLE delay effect** (#2473). Titles that use the DSP's delay effect now get it instead of a
  dry signal.

**Input**

- **Touch outside the bottom screen is rejected by the layout itself.** Upstream's refactor
  gates `IsWithinTouchscreen` on `bottom_screen_enabled`, which is the fix this fork has carried
  since rc1 (`a4e13b451`). The fork keeps its extra guard in `TouchMoved`.

**CPU**

- **FastInterp**, a new interpreter (#2376), used only when the JIT is off. The Thor uses the
  JIT. New setting `use_fastinterp` (default on) is mirrored correctly in the Kotlin settings,
  checked per `settings-audit.md` §5.

**Not relevant on this device:** Qt config integers, Windows/MSYS2 CI, libretro and iOS fixes,
a Minecraft hack-list entry.

## Standard of evidence

Host: the Catch2 suite on `thor/main` (85 cases, 1655 assertions) and one `assembleThorRelease`.

Fold5 (2026-09-25): rc7 before the cache fix found a 2026-09-09 ACNL autosave written by a
pre-effects build, offered it (it predates the last boot), and **loaded and ran it at 60 fps,
100% speed** — the savestate fix above working on hardware. The cache fix was verified as
described in its section. Nothing has been run on the Thor.

Save in-game before installing.
