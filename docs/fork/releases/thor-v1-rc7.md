# thor-v1-rc7

Release candidate 7 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## What this is

**rc6 plus 21 upstream commits** (to `b8c29a64c`, 2026-09-22), and one fork fix that the merge
made necessary. Eleven of those commits were merged on 2026-09-14 and never released; the other
ten landed upstream since.

If you are coming from **rc4**, as the Thor is: read rc5's and rc6's notes too. The one visible
change in them is that "Skip present duplicate frames" now defaults to off, following upstream.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc7.apk` |
| Built from | _pending_ |
| versionName | _pending_ |
| versionCode | _pending_ |
| sha256 | _pending_ |
| Size | _pending_ |
| ABIs | _pending_ |
| Upstream base | `b8c29a64c` |

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

Host verification: the Catch2 suite on `thor/main` and one `assembleThorRelease`. See the
verification section below for the on-device check.

Save in-game before installing.
