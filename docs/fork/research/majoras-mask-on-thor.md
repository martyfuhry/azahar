# Majora's Mask on the AYN Thor

Research note, 2026-09-09. **Read-only. Nothing was installed on, or run against, the Thor.**
**Updated the same evening — see [§5](#5-update-2026-09-09-evening--re-verified-at-thor-v1-rc5-with-three-corrections),
which re-verifies the whole argument at `thor-v1-rc5` and corrects two numbers in §3.4/§3.7 and one
instruction in Appendix A.3. The recommendation is unchanged.**
Every claim about an external project is cited. Every claim about this repo is a `file:line`
in `thor/main` at `1745b2f8f`. Every number about the texture pack was measured from the zip on
the NFS share (headers only — the 4.4 GB archive was never extracted). Anything I could not
verify is marked **uncertain**.

---

## 0. The short answer

**Don't install the 4K texture pack. Play MM3D on your own Azahar build at 4x with custom
textures off.** That is not a hedge — the pack is 29 GiB of decoded pixels, Azahar never frees a
decoded custom texture for the life of the process, and your baseline is already 1.1-1.8 GiB at
4x. The arithmetic is in §3 and it is not close.

And separately from whether it fits: **you would barely see it.** MM3D is a 2015 remake with
hand-redrawn art, not an N64 game. Its native top screen is 400x240; at 4x you are rendering
1600x960 into a 6-inch 1080p panel. The pack's median replacement is an 8x upscale of a 128x128
texture — 1024x1024 where the game asks for 128x128. At that panel size and viewing distance the
extra texel density beyond about 2x-3x is below what your eye resolves on anything but a wall you
have walked up and pressed your nose against. You already played OoT3D at 4x and called it smooth
and good-looking; MM3D is the same engine and the same art pipeline. **The pack buys you very
little and costs you the session.**

**Your instinct about lower tiers was right, and it does change the arithmetic** — Henriko ships a
**1080p tier** (4x rather than 8x) that is a quarter of the memory and is his own explicit Android
recommendation. It goes from "impossible" to "marginal experiment." It is still not the thing to do
first, and the file you have on the share is the wrong tier and six versions old. Full numbers in
§3.7; how to get the right one in Appendix A. §3 is the honest verdict at each resolution, and
§3.8 is a separate reason to be wary that has nothing to do with memory.

**Ranking of ways to play, recommendation first:**

| | Route | Verdict |
|---|---|---|
| **1** | **MM3D on your Azahar `thor-v1-rc2`**, custom textures OFF, 4x | **Do this.** Zero setup, both screens, the definitive version of the game, known-good on your device |
| **2** | **2 Ship 2 Harkinian** (`linkzenic` Android fork) | The strong second — native arm64, actively tracking upstream, and your `.z64` is the exact supported ROM (hash verified below). Single screen, N64 art |
| **3** | **Zelda64Recomp** (`linkzenic` Android fork) | Genuinely interesting, self-described **early beta**, and developed on a Snapdragon 8 Gen 2 handheld — your hardware is its best case. Try it for fun, not for a playthrough |
| **4** | **RetroArch N64** | See §2.4. Fallback only |

---

## 1. Facts established locally

Both verified by reading the files on the share; neither ROM was copied anywhere.

| Thing | Measured |
|---|---|
| `Nintendo 3DS/…Majora's Mask 3D (USA)….3ds` | NCSD, media/title ID **`0004000000125500`** |
| Texture pack's texture folder | `…/user/load/textures/**0004000000125500**/` — **exact match**, the pack is for this dump |
| `Nintendo 64/…Majora's Mask (USA).z64` | 32 MiB big-endian, header `80371240`, `ZELDA MAJORA'S MASK`, game code `NZSE` rev `0` |
| SHA1 of that `.z64` | **`d6133ace5afaa0882cf214cf88daba39e266c078`** |

That SHA1 is byte-for-byte 2S2H's supported **NTSC-U 1.0** hash
([`docs/supportedHashes.json`](https://github.com/HarbourMasters/2ship2harkinian/blob/develop/docs/supportedHashes.json)).
So the N64 ROM you already have is accepted by 2S2H with no conversion, and it is the US N64
release Zelda64Recomp asks for. **You do not need to source anything.**

---

## 2. The four routes

### 2.1 MM3D on Azahar — recommended

Nothing to set up. It is installed, it is your build, and you have already proven the harder
game on it. Specifics that matter for MM3D versus OoT3D:

- **Both screens.** MM3D is the only one of these four that *has* a bottom screen, and it uses it
  constantly — the item/mask grid, the Bombers' Notebook, the map. On the Thor that lands on the
  real second panel through `SecondaryDisplay`/`Presentation`, which is the whole point of the
  device and the thing rc1/rc2 spent work on (`G-1`, `T-2`/`T-3`/`T-4`).
- **Frame rate.** MM3D targets 30 fps and OoT3D targets 30 fps; they are the same engine family.
  Your OoT3D result at 4x is the best available predictor. MM3D has heavier per-frame geometry in
  Clock Town and heavier framebuffer work in the Great Bay/Stone Tower effects, so **expect it to
  be somewhat harder than OoT3D**, not easier. If it drops, 3x is the first knob, not the pack.
- **Settings.** `ThorDefaults.kt:73-92,125` already sets Vulkan, `RESOLUTION_FACTOR = 4`,
  `TEXTURE_FILTER_NONE`, `TEXTURE_SAMPLING_GAME_CONTROLLED`, both shader caches on. The comment at
  `:87` says out loud that "4x is already the memory ceiling on the 8 GB SKU" — written before
  anyone considered adding a 29 GiB texture pack on top.
- **Saves, input, 3D.** Native 3DS saves, your existing controller mapping, real gyro if you want
  it for the archery/Goron minigames.

### 2.2 2 Ship 2 Harkinian — the best non-3DS option

The Majora's Mask counterpart to Ship of Harkinian: a decompilation-based **native port**, not an
emulator. Upstream: [HarbourMasters/2ship2harkinian](https://github.com/HarbourMasters/2ship2harkinian),
very active (~559 commits in the trailing year; latest release **5.0.1 "Battler Bravo"**,
2026-08-25). **Upstream ships Windows/Linux/macOS only — no official Android.**

**Android builds are community, and there is a clearly correct one to pick:**

- **[`linkzenic/2ship2harkinian-Android`](https://github.com/linkzenic/2ship2harkinian-Android)** —
  use this. Branch `android`, last push **2026-09-08**, latest **`v5.0.1-android.2`** (2026-09-08,
  ~36 MB APK). It tracks upstream almost same-day: `v5.0.1-android.1` shipped the day upstream
  5.0.1 did. `arm64-v8a` only, `minSdk 24`, `targetSdk 33`, NDK 26, built `-DUSE_OPENGLES=ON`.
  README says "tested on Android 13" — which is the Thor's OS. 13 issues filed, **all closed**.
- **[`Waterdish/2ship2harkinian-Android`](https://github.com/Waterdish/2ship2harkinian-Android)** —
  the original and the one most articles point at. **Stale: last push 2025-06-14, tracks upstream
  1.1.2**, four major versions behind. Don't use it.
- `robertkirkman/2ship2harkinian-Android` — dormant staging fork.
- Do not grab `linkzenic/Shipwright-Android` by mistake; that is Ocarina of Time.

**ROM:** your `.z64`, unmodified, hash-verified above. First launch asks for it and generates
`mm.o2r` itself via the vendored ZAPDTR/OTRExporter — **no separate extractor tool, no `torch`
step.** Verify with [2ship.equipment](https://2ship.equipment/) if you want a second opinion.

**What it adds:** widescreen with a HUD editor and 4:3/16:9 presets; internal-resolution editor;
working framebuffer effects (motion blur, a functioning Pictobox — now colour, saved as PNG);
full input remapping, right-stick free-look, D-Pad item equips; gyro aim; binary save import from
an emulator *or from Zelda64Recomp*; persistent Owl saves, autosave, pause-saving; a large
built-in randomizer; a mod menu and cosmetics editor with a [GameBanana hub](https://gamebanana.com/games/20371).

**The 60 fps caveat, stated plainly.** High framerate is done by **Matrix Interpolation**, and the
official 1.0.0 notes say **game logic still runs at MM's native 20 fps** — the extra frames are
visual interpolation. It looks much smoother; it is not a 60 fps rewrite, and interpolation
artifacts on individual actors are an ongoing bug class (5.0.0 still lists per-actor interpolation
fixes). Android-specific known issues: orientation lock doesn't work
([SDL #6090](https://github.com/libsdl-org/SDL/issues/6090)) and near-plane camera clipping close
to walls.

**Second screen:** none. N64 MM has no bottom screen; you get one panel.
**Performance on SD8G2: could not verify** — no first-hand report exists. Structurally it is a
20 fps N64 game loop as native arm64 code on GLES, so the CPU side is trivial for an 8 Gen 2; the
only real variable is fill rate at high internal resolution.

### 2.3 Zelda64Recomp — real, and your device is its best case

Static recompilation ([Zelda64Recomp/Zelda64Recomp](https://github.com/Zelda64Recomp/Zelda64Recomp),
latest tagged **v1.2.2**, 2025-08-27) with the RT64 renderer. **MM only — Ocarina of Time is still
"planned", not shipped.**

**Official Android: explicitly refused.** Maintainer Reonu closed
[issue #45](https://github.com/Zelda64Recomp/Zelda64Recomp/issues/45) as *not planned* on
2026-05-15: Android is possible, but they don't want the support load and the "myriad broken
Vulkan drivers", and *"somebody else could make an Android fork."*

**Somebody did:** [`linkzenic/Zelda64Recomp-Android`](https://github.com/linkzenic/Zelda64Recomp-Android),
branch `android-port`, latest **0.6.10** (2026-07-29), RT64 on Vulkan plus SDL input. Its README
states it is **early beta** and that it is *"tested on Snapdragon 8 Gen 2 device, likely requires
Adreno"*, and *"currently runs on AYN/Retroid style devices"* — that is literally your handheld
class. The 0.6.2/0.6.3/0.6.4 release notes are all Samsung/Pixel startup fixes, i.e. the Adreno
handheld path is the one that works and other ecosystems were the bug source. It adds on-screen
touch controls, accelerometer/gyro aim, a save editor, and an Android randomizer mod. It requests
`MANAGE_EXTERNAL_STORAGE` and uses a top-level `/Zelda64/` folder.

**RT64 is not a blocker.** It needs **Vulkan 1.2**; Adreno 740 does 1.3. Ray tracing is *not*
required — [RT64](https://github.com/rt64/rt64) hasn't shipped it and upstream lists it under
Planned. The fork recommends a [Mr Purple Turnip](https://github.com/MrPurple666/purple-turnip/releases)
driver if the stock blob misbehaves, and supports loading custom drivers.

**This is the one that actually gives you uncapped framerate**, and unlike 2S2H it is not
interpolation: RT64 renders at any framerate with the original framebuffer/depth/decal effects
intact, and *"changing framerate has no effect on gameplay."* Plus any aspect ratio, high-res
rendering, gyro aim, autosave on by default, and a [Thunderstore mod hub](https://thunderstore.io/c/zelda-64-recompiled/).

**ROM:** the plain US N64 `.z64` — yours. The runtime auto-converts other container formats but
accepts only that one game.

**Two honest caveats.** (1) Self-described early beta, ~6 weeks with no release at the time of
writing; **whether it is still maintained is uncertain.** (2) **No public performance numbers
exist for it on any Android device** — anything you read claiming "60fps on 8 Gen 2 for the
Majora's Mask Android port" is almost certainly about 2S2H, which several outlets covered and
which is a different project. Also: no second screen.

**Do not bother with Winlator/Mobox/box64.** There is no x86 to translate — the project ships a
native Linux ARM64 build and states ARM64 works on any ARM64 CPU. The native APK is the path.

### 2.4 N64 via RetroArch — fallback only

Works, but it is the worst of the four for this game on this device.

- **Core: `Mupen64 Plus Next GLES3`** — not the plain "Mupen64 Plus Next", which is the GLES2 build
  and the docs warn "the GLES 2.0 version will have graphical issues on a GLES 3.0-compatible
  system" ([libretro docs](https://docs.libretro.com/library/mupen64plus/)). Video plugin
  **GLideN64**, framebuffer emulation **on** — MM's motion blur, Lens of Truth depth copy and the
  moon are exactly the framebuffer tricks GLideN64 spent years implementing
  ([GLideN64 devlog](http://gliden64.blogspot.com/2016/12/depth-buffer-emulation-ii.html)). The core
  changelog also carries an MM-specific freeze fix (SPECIAL_INT on downcounter flip,
  [mupen64plus-libretro-nx](https://github.com/libretro/mupen64plus-libretro-nx)).
- **ParaLLEl-RDP is off the table on Adreno.** It needs `VK_KHR_8bit_storage` /
  `VK_KHR_16bit_storage` / `VK_EXT_external_memory_host`
  ([upstream README](https://github.com/Themaister/parallel-rdp)); Qualcomm has never exposed the
  8-bit one ([issue #47](https://github.com/Themaister/parallel-rdp/issues/47), open since 2022),
  upstream added a workaround commenting that the "proprietary Qcom driver is known to be buggy with
  8/16-bit integer arithmetic" ([1f69c76](https://github.com/Themaister/parallel-rdp/commit/1f69c76)),
  the backport request into the libretro core is
  [open and unmerged](https://github.com/libretro/mupen64plus-libretro-nx/issues/567), and the field
  reports are **1 fps on an Adreno 830 S25 Ultra**
  ([#575](https://github.com/libretro/mupen64plus-libretro-nx/issues/575)) and **black screen on
  Adreno 650** ([RetroArch #18143](https://github.com/libretro/RetroArch/issues/18143)). RetroArch
  Android also **cannot load a Turnip driver** to work around it — the AdrenoTools request
  ([#15472](https://github.com/libretro/RetroArch/issues/15472)) has sat open since 2023. Angrylion
  is software LLE, native-resolution only, and too slow to matter here.
- **Frame rate: could not verify** on Adreno 740 from any primary source. GLideN64 HLE on an 8 Gen 2
  should hold the console's 20/30 fps comfortably; the handheld blogs claiming otherwise (or
  claiming ParaLLEl-RDP works on an Odin 2) contradict every primary source above and should be
  ignored.
- **Second screen:** none. **Saves:** `.srm` in RetroArch's save dir, not interchangeable with
  M64Plus FZ or 2S2H. **Setup:** ~10 minutes without a pack.
- **N64 texture packs do work here** — mupen64plus-next reads `.hts`/`.htc` from
  `<system dir>/Mupen64plus/cache/` or loose Rice PNGs from
  `<system dir>/Mupen64plus/hires_texture/<INTERNAL ROM NAME>/`. The good one is
  **[MM Reloaded](https://github.com/GhostlyDark/MM-Reloaded)** (GhostlyDark's maintained rework of
  Nerrel's MMHD), which ships an HTS build for RetroArch mupen64plus-nx *and* an O2R build for 2S2H
  and an RTZ build for Zelda64Recomp ([install notes](https://evilgames.eu/texture-packs/mm-reloaded.htm)).
  If HD N64 textures are what you actually want, that is the pack — and it works on route 2 and
  route 3 as well.
- **M64Plus FZ** (standalone, [Play Store](https://play.google.com/store/apps/details?id=org.mupen64plusae.v3.fzurita))
  is easier than RetroArch for N64 — it has an in-app "Add Texture Pack" picker that takes the
  `.hts` directly. Rosalie's Mupen GUI has **no Android build** ([RMG](https://github.com/Rosalie241/RMG)).

**One thing that is certain regardless: your 4K pack does not apply here.** It is a *Citra/3DS*
pack — PNGs named `tex1_<w>x<h>_<hash>_<fmt>.png`, keyed by a hash of 3DS texture memory, loaded
by `CustomTexManager` from `load/textures/<3DS title id>/`. N64 high-res packs are a completely
different format (Rice/GLideN64 `.hts`/`.htc`, keyed by N64 CRC) for a completely different game's
assets. There is no conversion. Same for 2S2H and Zelda64Recomp: they take N64-side texture packs
as mods, not Citra packs.

---

## 3. The texture pack on Azahar/Android: the memory verdict

### 3.1 What is actually in the 4.4 GB bundle

It is not a texture pack. It is a **full Windows Citra distribution** — `citra.exe` (55 MB),
`Zelda MM3D 4K.exe` (63 MB), `citra-room.exe`, `znetplay.exe`, ffmpeg DLLs (`avcodec-58/59` alone
are 124 MB), ReShade with 149 shader files, a Project Restoration romfs mod, and PAL/JPN switcher
`.bat` files. 4,822 entries, 4.78 GB uncompressed.

**The only part you want:**

```
Zelda MM3D 4K 1.6.0 (4K)/user/load/textures/0004000000125500/
```

**4,515 entries — 4,513 PNGs plus `CREDITS.txt` and a marker file, 4,061 MiB (3.97 GiB) on disk.**
There is **no `pack.json`** (this matters — see §3.5). Author is Henriko Magnifico; the bundle's
`INSTRUCTIONS.url` points at a Google Doc.

### 3.2 The number that decides it

The filenames encode the **original 3DS** dimensions, not the replacement's. I read the PNG IHDR
of all 4,513 files (headers only, no extraction):

| | |
|---|---|
| PNG bytes on disk | **3.97 GiB** (all 8-bit **palettised**, PNG colour type 3 — that is how 4 GB covers 29) |
| **Decoded RGBA8, whole pack** | **28.95 GiB** |
| Mean per texture | **6.57 MiB** &nbsp;·&nbsp; median 4.00 MiB |
| Upscale factor | 8x for 4,253 of them; 16x for 200; 32x for 19; 64x for one |
| Largest | two at **8192x8192 = 256 MiB each**, 34 at 4096x8192 = 128 MiB |
| Over 16 MiB decoded | **164 textures, 9.94 GiB** |
| Over 64 MiB decoded | 36 textures, 4.75 GiB |

Azahar decodes every custom texture to **RGBA8** (`custom_tex_manager.cpp` /
`material.cpp:99-141`). The palettised PNG compression buys nothing at runtime.

### 3.3 Preload ON — guaranteed kill, and the budget is the known bug

`CustomTexManager::PreloadTextures`, `custom_tex_manager.cpp:204-234`:

```cpp
const u64 sys_mem = Common::GetMemInfo().total_physical_memory;
const u64 recommended_min_mem = 2_GiB;
const u64 max_mem =
    (sys_mem / 2 < recommended_min_mem) ? (sys_mem / 2) : (sys_mem - recommended_min_mem);
...
    if (size_sum > max_mem) {
        LOG_WARNING(Render, "Aborting texture preload due to insufficient memory");
```

Three things are wrong with that on this device:

1. **The budget is total system RAM minus 2 GiB.** On an 8 GB Thor `total_physical_memory` reads
   ~7.4 GiB, so `max_mem ≈ 5.4 GiB`; on a 12 GB SKU ~9.5 GiB; on 16 GB ~13.5 GiB. Android will not
   let one app hold anything like that. This is exactly plan item **M-6** — *"custom-texture preload
   budget from `largeMemoryClass` instead of total RAM… latent OOM only if preload is ever enabled
   on Android"* (`improvement-plan.md:251`). Turning preload on is what un-latents it.
2. **The check is after the add, not before.** `size_sum` is compared *then* the next texture is
   loaded, so it overshoots by one texture — and one texture here can be **256 MiB**.
3. **Aborting is not recovering.** Everything already decoded stays decoded (§3.4). The abort log
   line is the last thing you'd see before the kill, if you saw anything.

There is no arrangement of `max_mem` under which 28.95 GiB fits. **Preload ON = process death,
every time, on every Thor SKU.**

### 3.4 Preload OFF — better, still loses, and here is the exact reason

With preload off and `async_custom_loading` on (the defaults —
`settings.h:653-654`, `BooleanSetting.kt:86-88`), materials load lazily: `Decode()` queues
`LoadFromDisk` on a worker, and `TickFrame` drains at most **8 uploads per frame**
(`MAX_UPLOADS_PER_TICK`, `custom_tex_manager.cpp:29`). So async loading genuinely helps the
*stutter*. It does nothing for the *total*, because:

> **A decoded custom texture is never freed.** `CustomTexture::data` is a `std::vector<u8>` filled
> by `LoadFromDisk` and read by `Surface::UploadCustom` (`vk_texture_runtime.cpp:1069-1085`). There
> is no unload path anywhere in `src/video_core/custom_textures/` — grep for it. Once you walk past
> a texture, its decoded RGBA8 stays in your process until you quit the game.

So the CPU-side cost is **monotonic in distinct textures ever seen**, and it converges on 28.95 GiB
over a playthrough.

The GPU side is worse than you would guess, and this is the part that makes the internal-resolution
question moot. In `Surface::Surface(runtime, surface, material)` (`vk_texture_runtime.cpp`):

```cpp
handles[Type::Base].Create(mat->width, mat->height, levels, ...);
if (res_scale != 1) {
    handles[Type::Scaled].Create(mat->width, mat->height, levels, ...);   // full custom size again
}
```

**At any resolution factor above 1x, a custom-texture surface allocates a second image at the full
custom dimensions.** Not `res_scale²` — 2x, flat. **4x and 3x cost exactly the same custom-texture
memory.** Only 1x halves it. And because this pack has no `pack.json`, `skip_mipmap` is forced true
(§3.5), so `GenerateMipmaps` runs after every custom upload — add ~33% for the mip chain.

Per distinct texture actually used, at 3x or 4x:

| Term | Cost | Lifetime |
|---|---|---|
| Decoded RGBA8 in `CustomTexture::data` | 1 × S | **permanent** |
| GPU `Type::Base` image (+mips) | ~1.33 × S | until the surface is invalidated |
| GPU `Type::Scaled` image (+mips) | ~1.33 × S | until the surface is invalidated |
| Staging buffer | 1 × S | transient, one frame |

With S = 6.57 MiB mean: **~26 MiB steady per distinct custom texture**, ~33 MiB peak.

Now the budget. `android-kill-root-cause.md` measures the baseline at **1.1-1.8 GiB at 3-4x with
custom textures off**, and establishes that on this device lmkd's `kill_heaviest_task` plus Android
13's cached-process cap kill the *biggest* process first. Call the realistic headroom for custom
textures **1.5-2.5 GB** before you are the fattest thing on the Thor.

> **1.5-2.5 GB ÷ 26 MiB ≈ 60-95 distinct textures.** The pack has **4,513**. Clock Town on day one
> will exceed that before you reach the Deku Palace.

Note the CPU term alone (6.57 MiB, permanent) still gives ~230-380 textures. Either way, the pack
is two orders of magnitude past the budget, and the GC cannot save you: `RunGarbageCollector`
(`rasterizer_cache.h:130-145`) only frees surfaces already *sentenced* by invalidation. There is no
byte-budget eviction — that is unfinished plan item **M-7**.

### 3.5 What the fork's own rc2 changes do and don't do here

`9f5b87d49` shrank the Vulkan upload ring from 512 MiB to **64 MiB** and added a one-shot staging
fallback. That commit was a memory win with custom textures *off* (the 512 MiB ring was the
single largest resident term on the Fold5). For this pack it is a mixed picture, and one detail in
the task framing needs correcting:

- **The fallback threshold is 16 MiB, not 64 MiB.** `ONE_SHOT_STAGING_DIVISOR = 4`
  (`vk_texture_runtime.cpp:166`), so `FindStaging` diverts any request over `64 MiB / 4`. **164 of
  these textures cross that line** — the path is not an edge case here, it is the normal path for
  every large texture in the pack.
- **It genuinely fixes what was a hard `ASSERT`.** The commit body: *"a request larger than the ring
  was a hard ASSERT."* Before rc2, the first 256 MiB texture would have aborted outright. So rc2
  makes this pack *possible* where it was previously *impossible*.
- **It has never been exercised in the field, and it aborts on failure.** `AllocateOneShotStaging`
  ends in `LOG_CRITICAL(...); UNREACHABLE();` if `vmaCreateBuffer` fails
  (`vk_texture_runtime.cpp:384-386`). Asking VMA for a **256 MiB host-visible mapped buffer** on a
  pressured 8 GB Android device is a plausible failure. That gives you a *second*, different death
  (see §4).
- It does not change the total by one byte. The ring was never the problem; the 29 GiB is.

**Also, because there is no `pack.json`:** `ReadConfig` fails, and `FindCustomTextures`
(`custom_tex_manager.cpp`) sets `use_new_hash = false; skip_mipmap = true`. Legacy hashing is
almost certainly right for a 2023-era Henriko pack, but it is the first thing to suspect if
textures silently don't appear, and `skip_mipmap` is what triggers the +33% mipmap generation.

### 3.6 Verdict, by resolution and by tier

**4K pack:**

| Setting | Verdict |
|---|---|
| 4x + preload ON | **No.** Dies during the loading bar |
| 4x + preload OFF | **No.** Dies partway into Clock Town or the first dungeon |
| 3x + preload OFF | **No.** Identical custom-texture memory to 4x — the `Type::Scaled` allocation does not scale with the factor |
| 1x + preload OFF | Roughly a third cheaper, still ~100x over budget, and you would be downsampling 1024x1024 textures to a 400x240 framebuffer. **Pointless** |
| 12 GB / 16 GB Thor SKU | Moves the wall later. Does not move it past a playthrough |

**There is no setting that makes the 4K tier work on this device.** The blocker is architectural —
no unload path, no cache byte budget — not a tuning problem. The tier below it is a different
question, and §3.7 answers it.

### 3.7 There IS a lower tier, and it changes the answer from "no" to "maybe"

You were right that Henriko publishes tiers. Current release is **v3.0b**, and the public
[Google Drive folder](https://drive.google.com/drive/folders/13AxQkZj1YPvPvFWVguMDXWCBSB-ixLo0)
linked from [henrikomagnifico.com/zelda-majoras-mask-3d-4k](https://www.henrikomagnifico.com/zelda-majoras-mask-3d-4k)
holds exactly two:

| File | Download | Scale | Author's words |
|---|---|---|---|
| `MM 3D 4K 3.0b-1 (4K).zip` | 3.43 GB | **8x** default resolution | — |
| `MM 3D 4K 3.0b-1 (1080p).zip` | **1.62 GB** | **4x** default resolution | *"best suitable for 1080p displays or lower"* |

**There is no 2K, Lite, or Mobile tier — the 1080p tier *is* the handheld build**, and the author's
own [setup guide/FAQ](https://github.com/TexturesGuide/MM3D_4K_SetupGuide/wiki/Setup-Guide-&-F.A.Q)
says so explicitly for Android: *"download the **1080p version** of the texture pack on your device
(the 4K version might not work on some android devices)"*, plus *disable "Preload custom textures"*,
which the FAQ names repeatedly as a cause of crashes and failures to boot. Two other things about
3.0b are better than what you have: it is **textures-only** (*"NO GAME ROM, EMULATORS OR GAME FILES
ARE INCLUDED"*) — no Windows Citra build to strip out — and it ships a `pack.json`, so the legacy
hash/mipmap fallback in §3.5 stops applying.

Your `1.6.0 (4K)` is ~6 versions old; the 4K tier has actually *shrunk* since (3.43 GB vs your
4.4 GB bundle). A `1.6.0 (1080p)` almost certainly existed too but is no longer hosted —
**uncertain**, the Wayback Machine was down during this research.

**Memory, all three options, measured.** The 1080p tier is half the linear dimensions of the 4K
tier, so exactly a quarter of the decoded bytes. Computed over all 4,513 textures:

| Tier | Decoded RGBA8, whole pack | Mean per texture | Steady cost per distinct texture used, **3x or 4x** | Distinct textures affordable in 1.5-2.5 GB |
|---|---|---|---|---|
| **None** | 0 | 0 | 0 | — (baseline 1.1-1.8 GiB total) |
| **1080p (4x)** | **7.24 GiB** | **1.64 MiB** | **~6.5 MiB** | **~230-380** of 4,513 |
| **4K (8x)** | **28.95 GiB** | 6.57 MiB | ~26 MiB | ~60-95 of 4,513 |

Reminder from §3.4: **3x and 4x are the same number** in that table. The `Type::Scaled` image is
allocated at the full custom size for any factor above 1x, so choosing 3x over 4x saves you render
targets, not textures.

**Verdict on the 1080p tier: it is the only tier worth attempting, and it is still marginal.** It is
4x the game's native texture resolution, which is precisely the point at which extra detail stops
being visible on a 6-inch 1080p panel — so it is also the tier that gives you nearly all of the
visible benefit. But 230-380 affordable textures against 4,513 in the pack still means a long
session accumulates past the budget, because the CPU-side decode is never freed. Expect it to look
great and then die somewhere in the second or third area. If you want to try it, try *that* one, at
3x, preload off — and know it is an experiment, not a playthrough plan.

### 3.8 Independent of memory: Azahar has an open custom-texture crash regression

This is worth knowing before you spend an evening on it. It is **not** an out-of-memory problem and
it is not specific to your fork:

- [azahar#2118](https://github.com/azahar-emu/azahar/issues/2118) — *"[Android] Custom Textures
  Crashing Azahar"*, open since 2026-05-13, filed by a user on an **AYN Thor Max, Snapdragon 8 Gen 2,
  16 GB RAM**. Many games crash with custom textures on and are fine with them off; a commenter pins
  the regression to **2125.1.1 and later**, crashing when a custom texture stops being used. Also
  reproduced on Windows and Steam Deck.
- [azahar#1308](https://github.com/azahar-emu/azahar/issues/1308) — **Henriko's pack on MM3D
  specifically**: with Vulkan + custom textures, black screen then crash after a few seconds. Fine on
  OpenGL; fine without custom textures. You run Vulkan.
- [azahar#1013](https://github.com/azahar-emu/azahar/issues/1013), [#761](https://github.com/azahar-emu/azahar/issues/761)
  (save states + custom textures), [#1512](https://github.com/azahar-emu/azahar/issues/1512)
  (instant crash if `pack.json` exceeds 56,313 bytes) are adjacent hazards.
- One of these **is** fixed in your tree: [PR #2481](https://github.com/azahar-emu/azahar/pull/2481)
  *"preserve SurfaceBase state for OpenGL custom surfaces"* is `c07f2cc96`, confirmed an ancestor of
  `thor/main`. But it is the **OpenGL** path, and you render with Vulkan, so it does not cover #1308
  or #2118.

Net: even on a 16 GB Thor Max with enough RAM, custom textures on recent Azahar crash for reasons
that have nothing to do with the pack's size. **Uncertain** whether any of that is still live at
your tree's base, but nothing in `thor/main` addresses the Vulkan case.


---

## 4. What to expect, and the failure signatures

**With custom textures off (recommended):** ~1.1-1.8 GiB resident at 4x, the same as OoT3D. MM3D
targets 30 fps; expect it a bit heavier than OoT3D in Clock Town and around the Great Bay /
Stone Tower framebuffer effects. Drop to 3x if it dips — resolution factor *does* scale the
render targets even though it doesn't scale custom textures.

**If you run a pack anyway, there are two distinct deaths and they look different:**

1. **Out-of-memory kill (the likely one).** No crash dialog, no tombstone. The process is killed
   while cached or under pressure; when you come back the game silently reboots at the title
   screen — exactly the symptom `android-kill-root-cause.md` §1 documents. Preceded, if you catch
   it, by `Aborting texture preload due to insufficient memory` in logcat.
2. **`UNREACHABLE()` abort in the staging path (the rc2-specific one).** A hard native crash the
   moment a 128-256 MiB one-shot staging allocation fails, with
   `Failed allocating <N> KiB staging buffer with error <VkResult>` at CRITICAL immediately before
   it. This produces a tombstone; the OOM kill does not. **If you see this, you found the first
   field exercise of the rc2 fallback — save the log, it is worth a fix.**

Read the reason:

```bash
adb shell dumpsys activity exit-info org.azahar_emu.azahar.thor
```

- `reason=LOW_MEMORY(3)`, `reason=SIGNALED(2) SIGKILL`, or
  `reason=OTHER(13) sub=LARGE_CACHED/MEMORY_PRESSURE` → **death #1**, the pack is too big.
- `reason=CRASH_NATIVE(5)` → **death #2**; pull the tombstone and grep logcat for
  `Failed allocating`.

Watch headroom live while playing:

```bash
adb shell dumpsys meminfo org.azahar_emu.azahar.thor   # TOTAL PSS, Native Heap, GL mtrack
adb logcat -d | grep -E "Render.*(Aborting texture preload|Failed allocating|one-shot)"
```

---

## 5. Update, 2026-09-09 evening — re-verified at `thor-v1-rc5`, with three corrections

§1-§4 above were written against `1745b2f8f`. Marty is now on **`thor-v1-rc5`** (`188ce81ec`,
tree at `b3dfc8978`) at **4x, Vulkan, texture filter None, dual-screen, autosave**. This section
re-checks the argument at that tree. **The recommendation does not change. Two of the numbers do,
one in the pack's favour.**

**Nothing in rc3/rc4/rc5 touched this code.** `git log --oneline thor-v1-rc2..HEAD --
src/video_core/custom_textures src/video_core/renderer_vulkan/vk_texture_runtime.cpp
src/video_core/rasterizer_cache` is **empty** across all 69 commits. Every `file:line` in §3 is
still accurate.

### 5.1 Correction: the `Type::Scaled` duplicate does **not** apply at texture filter = None

§3.4 says a custom-texture surface allocates a second full-size image "at any resolution factor
above 1x". That is wrong for Marty's configuration, and the error is roughly a factor of two
against the pack.

The custom-surface constructor gates on the **surface's** `res_scale`, not on the global
resolution factor:

```cpp
// vk_texture_runtime.cpp:949-963
handles[Type::Base].Create(mat->width, mat->height, ...);
if (res_scale != 1) {
    handles[Type::Scaled].Create(mat->width, mat->height, ...);   // full custom size again
}
if (has_normal) {
    handles[Type::Custom].Create(mat->width, mat->height, ...);   // only with a normal map
}
```

and `Surface::Surface(runtime, const SurfaceBase& surface, const Material* mat)` copies
`res_scale` in from the surface it replaces (`: SurfaceBase{surface}`). For a **sampled texture**,
that value is set one place only:

```cpp
// rasterizer_cache.h:584, GetTextureSurface
params.res_scale = filter != Settings::TextureFilter::NoFilter ? resolution_scale_factor : 1;
```

**With no texture filter, sampled-texture surfaces have `res_scale == 1` regardless of the
resolution factor**, so `handles[Type::Scaled]` is never created for them. `ThorDefaults.kt` ships
`TEXTURE_FILTER_NONE` and that is what Marty runs. (A surface can still be scaled up later if it
becomes a render target — `rasterizer_cache.h:1211-1213,1388-1391` — but that is rare for ordinary
game textures, and for a *custom* surface `ScaleUp` sizes the new image from the **game-side**
`width * res_scale`, not `mat->width`, which is a mismatch worth remembering next to the open
Vulkan crashes in §3.8.)

`Type::Custom` is likewise conditional on the material having a **normal map**. Henriko's pack is
colour-only as far as anything I can see, so that third image is not allocated either —
**uncertain**, not verified against the actual pack contents.

**Revised per-distinct-texture cost, at Marty's exact settings** (4x, filter None, custom textures
on, preload off, async on), with S = decoded RGBA8 size:

| Term | Cost | Lifetime | Verified |
|---|---|---|---|
| CPU `CustomTexture::data` | 1.00 × S | **permanent** | yes — no unload path exists |
| GPU `Type::Base` image | 1.00 × S | until invalidation | yes |
| mip chain on top of Base | +0.33 × S, **only if `skip_mipmap`** | " | yes — `SkipMipmaps()` gates `GenerateMipmaps` at `rasterizer_cache.h:1131` |
| GPU `Type::Scaled` | **not allocated** at filter None | — | yes (this section) |
| GPU `Type::Custom` | only with a normal map | — | code yes, pack contents **uncertain** |
| Staging | 1 × S, one frame | transient | yes |

So **~2.0 × S steady, ~2.3 × S if mipmaps are generated** — not the ~4 × S §3.4 implies.

### 5.2 What that does to the arithmetic, and what it does not

At the **1080p tier** (mean S = 1.64 MiB, from the doc's own header scan of the 4K tier divided by
four): **~3.3-3.8 MiB steady per distinct texture**, not ~6.5 MiB.

Against the 1.5-2.5 GB headroom judgement in §3.4, that is **~450-750 distinct textures**, up from
230-380. The pack has **4,513**. It is a better ratio and it is the same conclusion: the CPU-side
decode is never freed, so the cost is monotonic in distinct textures ever seen and the ceiling if a
session eventually touches the whole pack is **7.24 GiB CPU + ~7.24 GiB GPU**.

**The number nobody has measured is the one that decides it:** how many distinct textures an actual
MM3D session touches per hour. Not measurable without running it, and the Thor is out of bounds.
Everything else here is arithmetic on top of a guess about that.

**Baseline, now with a real Thor number instead of a range.** `~/Development/azahar-builds/
measure-rc2-2026-09-09/session-rc2-4x.log`, cold boot at `resolution_factor = 4` on the Thor:

```
TOTAL PSS  1306429 kB (1276 MiB)   TOTAL RSS  1434548 kB (1401 MiB)
GL mtrack   449096 kB ( 439 MiB)   Native Heap 581381 kB (568 MiB)
game_fps 59.38  speed 99%  gpu 1.85 ms  frametime 6.07 ms  (6 samples)
```

**Caveat that matters: that is Animal Crossing: New Leaf on rc2, not MM3D.** It confirms the
"1.1-1.8 GiB at 4x" band with a hard measurement and it confirms 4x is not GPU-bound on this
device. It says nothing MM3D-specific. There is **no MM3D memory measurement anywhere in this
repo's captures**.

### 5.3 New since §3 was written: the autosave transient lands at the worst moment

§3 predates the rc3/rc4 autosave work. Two facts from it now bear on the pack:

- A savestate write is fed **310-314 MB across every title measured**, and that buffer is
  allocated in one piece (`releases/thor-v1-rc4.md:88-97`).
- rc5 ships the **periodic** autosave **off** (`61ca81516`), on Marty's own call. What is always on
  is the **pause-time save** — the one that fires when the app backgrounds.

Which means the ~310 MB transient lands at exactly the instant the process stops being foreground
and becomes lmkd's heaviest cached task (`android-kill-root-cause.md:54`). A texture pack makes
the process fatter precisely at the moment it is most likely to be killed, and adds 310 MB of
allocation on top. Autosave means he loses less when it happens; it does not make it happen less.

### 5.4 The tier question has a clean answer, and it is not a matter of taste

`graphics-settings-guide.md:113-130` has the panel numbers, and they settle which tier is
dimensioned for this device:

| Panel | Pixels | 3DS screen fitted to | Factor at which it is full |
|---|---|---|---|
| Top, 6" | 1080x1920 | 1800x1080 | **4.5x** |
| Bottom, 3.92" | 1080x1240 | 1080x810 | 3.4x |

At resolution factor R the renderer samples textures at R× native density. A replacement texture at
N× the original's dimensions is therefore fully resolved only when R ≥ N.

- **1080p tier = 4× replacements.** Exactly saturated at **R = 4**, which is what he runs.
- **4K tier = 8× replacements.** Needs **R = 8** to resolve. The guide's own conclusion is that 5x
  is the first factor that fills the top panel and **"6x and up are invisible on both panels. There
  is nowhere for the pixels to go."**

**So the 4K tier is dimensioned for a render resolution this hardware cannot display.** Its extra
texels are unreachable except on a surface magnified more than 2x on screen — a wall the camera is
pressed against. This replaces the "below what your eye resolves" hand-wave in §0 with something
checkable: **the 1080p tier is not a compromise on this device, it is the correct tier**, and it
carries essentially all of the visible benefit.

(Henriko's own wiki tells Android users to start at **2x** internal resolution with the pack
installed. That is his advice for a phone with less headroom than a Thor; at 2x the 1080p tier's
own texels stop resolving too. The tier and the resolution factor want to match.)

### 5.5 Provenance: this is not AI slop, by the author's own description

The question of whether the pack is a neural upscale is answerable from the author's own page
([henrikomagnifico.com/zelda-majoras-mask-3d-4k](https://www.henrikomagnifico.com/zelda-majoras-mask-3d-4k)):

> "I usually rework the textures by hand in Photoshop by compositing native 4K textures with an
> upscaled texture as a reference"

An upscale is used as a **reference layer**, composited against source art and reworked by hand.
That is the opposite of an ESRGAN-and-ship pipeline, and it is consistent with the pack's stated
goal of remaking every texture from scratch. He does not name the upscaler he references.
**Current version is v3.0b: 4K tier 3.43 GB, 1080p tier 1.62 GB, textures-only** (the
Re-Orchestrated soundtrack and the "Henriko Ultra 8.0" / "N64 Style 3.0" ReShade presets are
separate optional downloads, not part of the pack). The GitHub wiki is stale relative to the
download page — it still describes 2.0.0 as current.

**What I could not verify, and am not going to pretend otherwise.** Whether the pack "looks worse
in motion" is the standard failure mode of upscaled packs and I found **no** community discussion
either way — the session that chased this had its web-search budget exhausted and Reddit, GBAtemp,
the Citra forums and archive.org were all unreachable. **Absence of criticism here is absence of
evidence, not evidence of absence.** A GameBanana API query for MM3D texture mods returned nothing,
so Henriko's is very likely the only maintained MM3D pack — again a negative result from one
source, not a survey.

### 5.6 Two failure modes in the loader that §3 did not cover

Both read out of `thor/main` at `b3dfc8978`; **neither was executed**, so both are code-reading
results, not observations.

**A single corrupt PNG is undefined behaviour, not a skipped texture.** `CustomTexture::width` and
`::height` are plain uninitialised `u32` (`material.h:58-59`, no initialiser). The only writer is
`DecodePNG`, and when it fails `LoadPNG` logs and returns **without** touching them and without
marking anything:

```cpp
// material.cpp:83-86
if (!image_interface.DecodePNG(data, width, height, input)) {
    LOG_ERROR(Render, "Failed to decode png: {}", path);
    return;
}
```

`Material::LoadFromDisk` then never checks that the texture actually loaded — it only checks that
`textures[0]` is non-null — so it reads the indeterminate `width`/`height` into the material and
falls through to `state = DecodeState::Decoded` (`material.cpp:116-141`). `TickFrame` sees
`Decoded`, runs the upload, and `Surface::Surface(..., mat)` calls
`handles[Type::Base].Create(mat->width, mat->height, ...)` **with garbage dimensions**. Outcome is
anything from a wrong-looking texture to an absurd VMA allocation to a driver abort. For a 1.6 GB
download this is a live concern: **verify the archive before copying it to the device.**

**A malformed `pack.json` is a hard crash, not a fallback.** `ReadConfig` parses with exceptions
suppressed and then dereferences unconditionally:

```cpp
// custom_tex_manager.cpp:335-340
nlohmann::json json = nlohmann::json::parse(config, nullptr, false, true);
const auto& options = json["options"];
skip_mipmap = options["skip_mipmap"].get<bool>();
```

`allow_exceptions = false` makes a parse failure return a **discarded** value rather than throw —
and `operator[]` on a discarded value then throws `json::type_error`, uncaught, from a code path
with no handler. A file that parses but has no `"options"` object is worse: `json["options"]`
inserts a null, and the const `operator[]` on null is a `JSON_ASSERT` that compiles out under
`NDEBUG`. The graceful "no pack.json → legacy defaults" path in §3.5 only covers the file being
**absent**. A present-but-broken one is a crash. This is almost certainly the mechanism behind
[azahar#1512](https://github.com/azahar-emu/azahar/issues/1512).

The genuinely graceful case is a **missing** replacement: `GetMaterial` returns `nullptr` with
`Unable to find replacement for surface with hash …` at WARNING and the stock texture is used
(`rasterizer_cache.h:1107-1110`). That is the behaviour Appendix A.4 leans on and it is sound.

### 5.7 M-6 is still true, and Android reaches it only by hand

`improvement-plan.md` M-6 (the preload budget taken from total RAM) is **unchanged at rc5** —
`custom_tex_manager.cpp:207-234` still reads `Common::GetMemInfo().total_physical_memory`, still
subtracts a flat 2 GiB, and still compares `size_sum` *before* adding the next texture so it
overshoots by one. On an 8 GB Thor that is a ~5.4 GiB budget for a process Android will not let
past a fraction of it.

**But the Android UI does not expose the toggle at all.** `SettingsFragmentPresenter.kt:1382-1394`
has the `PRELOAD_TEXTURES` switch commented out, upstream, with:

```kotlin
// Disabled until custom texture implementation gets rewrite, current one overloads RAM
// and crashes Citra.
```

That is upstream Azahar stating §3.3's conclusion in its own source. It also means **Appendix A.3's
"Preload Custom Textures = OFF" is not an instruction Marty can follow — there is no such switch on
the phone.** The default is already `false` (`settings.h:749`) and `jni/config.cpp:239` reads
`[Utility] preload_textures` from `config.ini`, so the only way to turn it on is to hand-edit that
file. **Don't.** M-6 stays a latent bug rather than a reachable one, which lowers its priority but
not its correctness.

The two settings that *are* in the Android UI, under Graphics → the block after Dump Textures, are
**Custom Textures** and **Async Custom Texture Loading** (`SettingsFragmentPresenter.kt:1341-1359`).

### 5.8 Standing recommendation at rc5

**Unchanged: don't install a pack for this playthrough.** The corrections in 5.1 make the 1080p
tier roughly twice as affordable as §3.7 said and the panel arithmetic in 5.4 says it is the right
tier — but "twice as affordable" is 450-750 textures against 4,513, in a loader that frees nothing,
on the device whose kill behaviour this fork exists to fight, with the Vulkan custom-texture
regressions of §3.8 still unaddressed in `thor/main`. He is mid-playthrough and it runs at 99%
speed with 439 MiB of GPU memory to spare.

**If he ever does experiment**, the shape is: `MM 3D 4K 3.0b-1 (1080p).zip`, at 4x (not 3x — 5.4
supersedes A.3 on this; the tier and the factor should match, and 4x is what the pack's 4×
replacements are cut for), custom textures on, async on, on a **branch of the save he cares
about** — copy the `.sav`/savestate off the device first, because backing out is deleting the
folder but a corrupted session is not. And it is an evening's experiment, not a playthrough plan.

---

## Appendix A — if you want to try a pack anyway

### A.0 Do not use the file on your share. Download the 1080p tier instead.

`Zelda Majora's Mask 3D 4K 1.6.0 (4K).zip` is the wrong tier (8x, 29 GiB decoded), six versions
old, and wrapped in a Windows Citra distribution. Get
**`MM 3D 4K 3.0b-1 (1080p).zip` (1.62 GB)** from the
[Google Drive folder](https://drive.google.com/drive/folders/13AxQkZj1YPvPvFWVguMDXWCBSB-ixLo0)
linked at the bottom of [henrikomagnifico.com/zelda-majoras-mask-3d-4k](https://www.henrikomagnifico.com/zelda-majoras-mask-3d-4k).
It is textures-only, ships a `pack.json`, and is what the author tells Android users to use. (The
MediaFire mirror was empty when checked.) Then skip to A.2 — there is nothing to strip out.

### A.1 Only if you insist on the 1.6.0 4K bundle: extract selectively

Never extract the whole 4.4 GB; you only want one subtree and you do not want `citra.exe` anywhere
near your storage.

```bash
# 1. Look before you leap
unzip -l "/nfs/roms/Texture Packs/Zelda Majora's Mask 3D 4K 1.6.0 (4K).zip" \
  | grep 'user/load/textures/0004000000125500/'

# 2. Extract ONLY the texture folder, flattened, to scratch (not into a git repo)
mkdir -p /tmp/mm3d-tex
unzip -j "/nfs/roms/Texture Packs/Zelda Majora's Mask 3D 4K 1.6.0 (4K).zip" \
  "Zelda MM3D 4K 1.6.0 (4K)/user/load/textures/0004000000125500/*" \
  -d /tmp/mm3d-tex/0004000000125500/

# 3. Sanity: expect 4513 PNGs, ~3.97 GiB
find /tmp/mm3d-tex/0004000000125500 -name '*.png' | wc -l
du -sh /tmp/mm3d-tex/0004000000125500
```

### A.2 Where it goes on the Thor

Azahar Android's user directory is the folder you chose at first
run (`PermissionsHandler.citraDirectory`, via SAF); on a default install that is
`/storage/emulated/0/Android/data/org.azahar_emu.azahar.thor/files/`. The exact path is shown in
the app's settings. The pack goes at:

```
<user dir>/load/textures/0004000000125500/tex1_*.png
```

**Verify the archive before it goes anywhere near the device** — §5.6: one truncated PNG is
undefined behaviour in the loader (indeterminate image dimensions, not a skipped texture), and a
malformed `pack.json` is a hard crash rather than a fallback.

```bash
cd <the extracted 0004000000125500 folder>
unzip -t "MM 3D 4K 3.0b-1 (1080p).zip"          # before extracting

python3 - <<'EOF'                                # after: every PNG header must be intact
import glob
bad = []
for f in glob.glob('*.png'):
    with open(f, 'rb') as fh:
        d = fh.read(33)
    if d[:8] != b'\x89PNG\r\n\x1a\n' or d[12:16] != b'IHDR' or len(d) < 33:
        bad.append(f)
print(len(glob.glob('*.png')), 'png,', len(bad), 'bad'); print('\n'.join(bad[:20]))
EOF

python3 -m json.tool pack.json >/dev/null && wc -c pack.json   # must parse, must be < 56313 bytes
```

Flat, plus `pack.json` if the tier ships one. `GetTextures()` does scan recursively (depth 64) but
`ParseFilename` keys off the leaf name, so flat is what the pack expects. Copy over MTP or
`adb push`; a `.nomedia` in that folder saves the media scanner from indexing 4,513 PNGs.

### A.3 Settings

> **Superseded in two places by §5.** (a) There is no *Preload Custom Textures* switch in the
> Android UI — upstream commented it out (§5.7); it is off unless `config.ini` is hand-edited, so
> there is nothing to do. (b) Use **4x, not 3x**: §5.4 shows the 1080p tier's 4x replacements are
> cut for exactly that factor, and §5.1 shows 3x saves no custom-texture memory at filter None.

Graphics/Utility: **Custom Textures = ON**, ~~**Preload Custom Textures = OFF**~~ (not in the UI —
§5.7; the default is already off), **Async Custom Texture Loading = ON**, Resolution Factor
**4x** (§5.4), Texture Filter **None** (a filter multiplies sampled-texture memory by res² —
`rasterizer_cache.h:583`, plan items `M-7`/`D-2` — and you do not want that stacked on a texture
pack; the author's FAQ separately blames Linear Filtering for misplaced textures under memory
pressure).

### A.4 Verifying it is actually loading

With `custom_textures` on, `GetMaterial` logs
`Unable to find replacement for surface with hash <hash>` at WARNING for **every miss**
(`custom_tex_manager.cpp`). So:

```bash
adb logcat -d | grep -c "Unable to find replacement"
```

If the pack is wired up, that count is modest and you can visibly see Henriko's work. If the folder
or title ID is wrong, *every single texture* logs it and the game looks stock. If the game looks
stock but the miss count is low, the hash mode is wrong — write a `pack.json` next to the PNGs with
`{"options":{"skip_mipmap":false,"flip_png_files":true,"use_new_hash":true}}` and retry, because
with no `pack.json` at all Azahar forces `use_new_hash = false` and `skip_mipmap = true`
(`custom_tex_manager.cpp`, `FindCustomTextures`). Keep any `pack.json` under 56,313 bytes —
[azahar#1512](https://github.com/azahar-emu/azahar/issues/1512).

**Expect it to die anyway** — from memory (§3), or from the open Vulkan custom-texture regression
(§3.8), which is not something a smaller pack fixes. §4 says how to tell which.

---

## Appendix B — open questions

- **Is `linkzenic/Zelda64Recomp-Android` still maintained?** No release since 2026-07-29. Uncertain.
- **Is the Vulkan custom-texture crash (azahar#1308 / #2118) still live at this tree's base?**
  Not investigated. The OpenGL half (PR #2481 / `c07f2cc96`) is merged here; the Vulkan half is not
  addressed anywhere in `thor/main`. Worth a deliberate look if custom textures ever become a goal.
- **2S2H and Zelda64Recomp performance on SD8G2.** No public data for either. Both are structurally
  cheap; neither has been measured on this hardware by anyone whose report I could find.
- **How many distinct textures does an hour of MM3D actually touch?** This is the single number the
  whole memory argument rests on (§5.2) and nobody has measured it. It is measurable — count
  distinct hashes reaching `UploadCustomSurface` — but not without running the game.
- **Is `CustomTexture::width` really uninitialised on a decode failure in practice?** (§5.6.) Read
  out of the source, never executed. A unit test over a deliberately truncated PNG would settle it
  in minutes and is worth writing regardless of whether anyone installs a pack.
- **Does Henriko's 3.0b `pack.json` set `skip_mipmap`, and does the pack ship normal maps?** Both
  change the per-texture cost in §5.1 and neither was checked against the actual 3.0b download.
- **Does the pack look worse in motion?** Unanswered (§5.5). The web-research pass had its search
  budget exhausted and could not reach any forum. This is the standard failure mode of upscaled
  packs and it remains genuinely unknown here.
- **M-6 and M-7** (`improvement-plan.md:251,248`) are the two changes that would make *any* large
  texture pack viable on this device: a preload budget from `largeMemoryClass`, and a texture-cache
  byte budget with LRU eviction. A third is missing from the plan entirely and is arguably the most
  important for packs: **an unload path for `CustomTexture::data`** so the permanent CPU-side term
  becomes bounded.
