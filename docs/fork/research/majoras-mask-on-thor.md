# Majora's Mask on the AYN Thor

Research note, 2026-09-09. **Read-only. Nothing was installed on, or run against, the Thor.**
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

Flat, plus `pack.json` if the tier ships one. `GetTextures()` does scan recursively (depth 64) but
`ParseFilename` keys off the leaf name, so flat is what the pack expects. Copy over MTP or
`adb push`; a `.nomedia` in that folder saves the media scanner from indexing 4,513 PNGs.

### A.3 Settings

Graphics/Utility: **Custom Textures = ON**, **Preload Custom Textures = OFF** (non-negotiable,
§3.3, and the author's FAQ says the same), **Async Custom Texture Loading = ON**, Resolution Factor
**3x**, Texture Filter **None** (a filter multiplies sampled-texture memory by res² —
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
- **M-6 and M-7** (`improvement-plan.md:251,248`) are the two changes that would make *any* large
  texture pack viable on this device: a preload budget from `largeMemoryClass`, and a texture-cache
  byte budget with LRU eviction. A third is missing from the plan entirely and is arguably the most
  important for packs: **an unload path for `CustomTexture::data`** so the permanent CPU-side term
  becomes bounded.
