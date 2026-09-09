# Graphics settings on the AYN Thor — what to set, and what everything does

Written for someone who wants to play 3DS games, not tune an emulator.

---

## 1. What should I set?

**Open Settings → Graphics, tap "Graphics quality", pick "Best looking (recommended)". Done.**

That is the whole answer. It is what a fresh install of this fork already does on a Thor, and
it is what the preset writes:

| Setting | Value | Why |
|---|---|---|
| Internal resolution | **4x** | The largest whole number the Thor's panels can actually show. |
| Texture filter | **None** | It is not what makes a game look sharper, and it is expensive. |
| Texture sampling | **Game controlled** | Overriding it makes games look wrong and fixes nothing. |
| Asynchronous shader compilation | **On** | The difference between a stutter storm and a smooth first hour. |
| Disk shader cache | **On** | Keeps that smoothness across sessions. |
| Accurate multiplication | **On** | Pokémon X/Y render some surfaces in the wrong colour without it. |
| Linear filtering | **On** | 4x is not exactly the panel's pixel count, so the last stretch wants smoothing. |

Nothing else on the Graphics screen needs to be touched. If a game ever runs badly, drop the
same control to **Balanced** (3x); if you are on a long flight, **Battery saver** (2x).

This is not a guess. Measured on the same silicon (§2), Animal Crossing at 4x runs at **100%
speed with a 16 ms frame and zero janky frames**, using 3.9 ms of a 16.7 ms frame on the GPU.
There is no reason to settle for less.

**Do you need different settings per game?** No. See §5 — the honest answer for this library
is "essentially none", and Azahar on Android has no per-game settings anyway.

---

## 2. The numbers this rests on

Measured 2026-09-09 on the lab Galaxy Z Fold5 (`R3CW705DSTF`, folded, cover panel 904x2316),
the same Snapdragon 8 Gen 2 / Adreno 740 as the Thor, on the `feat/graphics-presets` build
(`versionName thor-v1-rc1-38-thor`, `versionCode 40000000`) with `tools/thor/measure.sh`.
**scrcpy was killed first** — it adds a virtual display and an encoder and contaminates both
frame timing and GPU memory. One run per configuration; each run is a force-stop, a config.ini
edit, a fresh launch, 45 s to settle, then a 25 s capture. The game is **Animal Crossing: New
Leaf**, resumed from its autosave into real gameplay (not a menu). Single window: the secondary
Presentation was turned off so the numbers describe the graphics settings and nothing else.

Two instruments:

- **present-to-present** — `dumpsys SurfaceFlinger --timestats` for the emulator's own layer,
  1 ms buckets. **16 ms is full speed**; anything higher is the emulator falling behind.
- **in-app counters** — the emulator's own `perf:` log line (`speed`, `frametime`, `gpu`), which
  is the only thing that can tell you how much of the frame the GPU actually used.

| Internal res | Filter | present p50 / p95 | Speed | Emu frame / GPU | TOTAL PSS | GPU mem (GL+EGL) |
|---|---|---|---|---|---|---|
| 1x | None | 16 / 16 ms (n=482, 0 janky) | 101% | 6.7 ms / **1.9 ms** | 1472 MiB | 449 MiB |
| 2x | None | 16 / 16 ms (n=679, 0 janky) | 98-101% | 5.9 ms / **1.9 ms** | 1473 MiB | 449 MiB |
| 3x | None | 16 / 16 ms (n=1500, 0 janky) | 96% | 7.4-9.8 ms / **2.4-3.5 ms** | 1476 MiB | 449 MiB |
| 4x | None | (capture missed) | 100% | 11.4 ms / **3.9 ms** | 1396 MiB | 413 MiB |
| 3x | Bicubic | 16 / 16 ms (n=1500, 0 janky) | 95-101% | 6.7-7.2 ms / 1.7-2.3 ms | 1506 MiB | **535 MiB** |
| 4x | Bicubic | **native crash** | — | — | — | — |

**What the table says.**

1. **4x is free on this chip.** Every scale from 1x to 4x holds a 16 ms present interval with
   *zero* janky frames and 96-101% emulation speed. Nothing about the picture quality costs
   anything a player can feel here. That is why the recommendation is the top preset rather
   than a cautious middle one.
2. **The GPU cost is real but tiny against the budget.** The emulator's own GPU counter roughly
   doubles from 1x to 4x (1.9 → 3.9 ms), which is the quadratic cost showing up exactly where
   §3 says it should. It is 23% of a 16.7 ms frame at 4x, so there is room; on a title that is
   already GPU-bound it would be the thing that runs out.
3. **Resolution barely moves memory; a texture filter does.** Resident memory is flat across
   1x-4x — the render targets are tens of MiB against a ~1.4 GiB process, and run-to-run
   variation (±80 MiB) is larger than the effect. Turning **one** filter on at the same 3x
   added **86 MiB of GPU memory** (449 → 535 MiB, +19%), which is the sampled-texture rescaling
   described in §4 showing up on a meter. That is the cost of a setting that does not raise the
   render resolution at all.
4. **4x + Bicubic crashed the app outright.** `APP CRASH (NATIVE)`, SIGSEGV inside
   `qglinternal::vkCreateGraphicsPipelines` in `/vendor/lib64/hw/vulkan.adreno.so`, reached from
   `Vulkan::GraphicsPipeline::Build` on the async shader-compile thread — a `pthread_mutex_lock`
   on a destroyed mutex inside the proprietary driver. Being fair about attribution: the fault
   is in the driver's pipeline creation, not in the filter shader, so this is not proof that the
   filter itself is broken. It is one more run of the scaled-texture path ending in a crash, on
   a configuration nobody needs.

**What is not here.** The heavier second title (Zelda: A Link Between Worlds) was pushed to the
device and taken through file creation, but the session's device budget ran out before it could
be driven into steady-state gameplay and its autosave written, so there are no ALBW numbers.
The ACNL result — full speed with headroom at every scale — is the load-bearing one, and it is
the *lighter* case: a second title that is genuinely GPU-bound is what would show 4x costing
something. **That row is pending.**

---

## 3. Why 4x, and why not 8x

A 3DS renders its top screen at **400x240** and its bottom screen at **320x240**. "Internal
resolution 4x" means the emulator draws those at 1600x960 and 1280x960 instead, and the result
is then scaled to whatever the panel is.

The Thor has two panels:

| Panel | Pixels | What it shows | Scale at which it is full |
|---|---|---|---|
| Top, 6" | 1080x1920 (used as 1920x1080) | the 3DS top screen, fitted at 5:3 → **1800x1080** | 1080 / 240 = **4.5x** |
| Bottom, 3.92" | 1080x1240 | the 3DS bottom screen, fitted at 4:3 → **1080x810** | 1080 / 320 = **3.4x** |

So:

- **2x** (800x480 top, 640x480 bottom) covers 44% of the top panel's height and 59% of the
  bottom panel's width. Visibly soft, but far from the mush 1x is.
- **3x** (1200x720, 960x720) covers 67% of the top panel and 89% of the bottom panel — the
  bottom screen is effectively as sharp as it will ever get here.
- **4x** (1600x960, 1280x960) covers 89% of the top panel and *oversamples* the bottom panel by
  19%, which is free anti-aliasing down there.
- **5x** (2000x1200) is the first factor that fully saturates the top panel — and it costs 56%
  more pixel work than 4x to buy that last 11%.
- **6x and up** are invisible on both panels. There is nowhere for the pixels to go.

Cost is quadratic, not linear: every render target, depth buffer and blit is `factor x factor`
times as many pixels. Relative to 4x, 3x is 56% of the pixel work, 2x is 25%, 1x is 6%. This is
the *only* setting on the Graphics screen with a real quality-versus-speed trade-off, which is
why it is the only thing the three presets actually differ in. Padding the presets with
unrelated toggles would have looked more impressive and told you less.

The presets do not hardcode 4. They compute `min(long side / 400, short side / 240)` from the
display and take the whole part of it, capped at 4 — so the same code gives 3x on a 720p
handheld and stays correct if the panels ever change.

The resolution list's own first entry, **"Auto (Screen Size)"**, does something similar but
rounds *up*: `FramebufferLayout::GetScalingRatio()` (`framebuffer_layout.cpp:19`) is
`ceil(top screen width / 400)`, which on the Thor is `ceil(1800 / 400)` = **5x**. That is the
"saturate the panel exactly" answer, and it costs 56% more pixel work than 4x. The presets take
the floor instead, deliberately.

**Why cap at 4 rather than let it go to 18?** Because past the panel there is no picture
improvement at all, only frame time and memory; and on the 8 GB Thor SKU the emulator is already
the largest process on the device.

---

## 4. Every graphics setting Azahar shows on Android

One sentence each, plus whether it is worth a player's attention. `src/common/settings.h` has
the types; `SettingsFragmentPresenter.addGraphicsSettings()` is the list as it appears on screen.

### The ones that matter

| Setting | What it does | Worth exposing? |
|---|---|---|
| **Internal resolution** (`resolution_factor`) | Draws the game at N times the 3DS's own resolution; cost grows with N squared. | **Yes** — it is the only real quality/speed dial. Now driven by the preset. |
| **Asynchronous shader compilation** (`async_shader_compilation`) | Compiles new shaders on worker threads instead of freezing the frame that needed them; you get a moment of pop-in instead of a hitch. | **No** — it should always be on; the preset keeps it on. On mobile drivers, without it a cold cache is a stutter every time a new effect appears. This fork already flipped the C++ default to on for Android. |
| **Disk shader cache** (`use_disk_shader_cache`) | Saves compiled shaders so the second session through an area is smooth. | **No** — always on. |
| **Accurate multiplication** (`shaders_accurate_mul`) | Emulates the PICA's multiply rules exactly instead of using the GPU's fast path; fixes wrong colours and missing effects in some games at a small shader cost. | **Borderline.** Android's config reader defaults it *off* (`jni/config.cpp:143`) while the desktop default is *on* (`settings.h:563`) — a discrepancy worth knowing. Azahar force-enables it per title for OoT3D and Paper Jam via `common/hacks/hack_list.cpp`, but not for Pokémon X/Y, which needs it too. The presets turn it on. |

### The ones that sound important and are not

| Setting | What it does | Worth exposing? |
|---|---|---|
| **Texture filter** (`texture_filter`: None / Anime4K / Bicubic / ScaleForce / xBRZ / MMPX) | Runs a smoothing or pixel-art upscaling shader over the game's own artwork as it is uploaded. It does **not** make the game render at a higher resolution. | **No.** Two reasons beyond taste. (1) Memory: with any filter selected, `rasterizer_cache.h:584` sets every *sampled* texture's scale to the render scale — at 4x that is sixteen times the video memory per texture, plus an extra render pass on every upload (`vk_texture_runtime.cpp:1053-1066`). Measured: one filter at 3x added **86 MiB of GPU memory** (449 → 535 MiB) for zero extra render resolution, and 4x + Bicubic crashed the app natively inside the Adreno driver's pipeline creation (§2). (2) Stability: the scaled-texture path still has open crash reports, and a fix for one of them was in flight in this fork while this was written. The presets keep it off. |
| **Texture sampling** (`texture_sampling`: Game controlled / Nearest / Linear) | Overrides how the game asked its textures to be sampled. | **No.** "Game controlled" is correct; the overrides exist for people chasing a specific look and mostly make games look wrong. |
| **Linear filtering** (`filter_mode`) | Smooths the final stretch from the rendered image to the panel. | **No** — on is right whenever the render size is not exactly the panel size, which is always. |
| **Integer scaling** (`use_integer_scaling`) | Forces the final stretch to a whole number, leaving black bars. | **No** — a pixel-purist option; it shrinks the picture on the Thor's panels. |
| **Graphics API** (`graphics_api`) | OpenGL ES or Vulkan. | Leave at **Vulkan** on Adreno; the Thor profile sets it. Not part of the preset, because switching renderers mid-life is a bigger decision than a quality preset should make. |
| **SPIR-V shader generation** / **Disable SPIR-V optimizer** | The first emits the PICA fragment shader as SPIR-V instead of GLSL; the second skips the optimisation pass before handing it to the driver. Both default on, i.e. SPIR-V is generated and *not* optimised, which trades a little runtime shader quality for much shorter compiles and much less stuttering. | **No** — driver-shaped settings whose defaults are already the mobile-correct ones. |
| **Delay render thread** (`delay_game_render_thread_us`) | Sleeps the game's render thread by a fixed number of microseconds; a workaround for a handful of titles' timing bugs. | **No** — leave at 0. |
| **Skip duplicate frames** (`use_skip_duplicate_frames`, under Advanced) | Does not re-present a frame identical to the last one. Saves GPU work in 30 fps titles, at the cost of a blocking GPU drain on each skip (`renderer_vulkan.cpp:1177`). | **No** — default on is right; this is a fork-internal performance question, not a player's. |
| **Dump textures / Custom textures / Async custom loading** | Texture-pack machinery. | **No** — off. Custom textures are also the one feature that can genuinely exhaust memory on an 8 GB device. |
| **Stereoscopy block** (`render_3d_which_display`, `render_3d`, 3D depth, **Disable right eye render**, swap eyes) | Renders the 3DS's second eye and how to display it. "Disable right eye render" halves the geometry cost in 3D-enabled games. | **No**, and one warning: do **not** turn on "Disable right eye render" globally. It puts Metroid: Samus Returns' bottom screen off-centre (upstream #740, maintainer's verdict: "just don't use it"), and Azahar already ignores it for Luigi's Mansion: Dark Moon via the per-title table. With 3D off — the default — there is no right eye to disable anyway. |
| **Cardboard VR block** | Positions the image for a phone-in-a-headset. | **No** — meaningless on a handheld. |

### Related settings that live elsewhere

| Setting | Where | What it does |
|---|---|---|
| **Frame limiter / limit %** (`use_frame_limit`, `frame_limit`) | General | Caps emulation speed at N% of a real 3DS. On at 100% is correct; the Thor profile sets it. Not owned by the preset — it is a speed setting, not a picture setting. |
| **VSync** (`use_vsync`) | Debug | Off on Android by default and should stay off; the frame limiter does the pacing and vsync only adds latency. |
| **CPU clock percentage** (`cpu_clock_percentage`) | Debug | Over- or under-clocks the emulated ARM11. 100% is correct; raising it breaks timing-sensitive games and lowering it breaks most things. |

---

## 5. Do any of Marty's games need special settings?

**Essentially none.** Two things to know, and neither is a per-game setting you have to make:

1. **Azahar already applies per-title fixes by itself.** `src/common/hacks/hack_list.cpp` carries
   a title-ID table that the Android path applies at boot (`jni/native.cpp:307` →
   `GPU::ApplyPerProgramSettings`). In this library it covers **Ocarina of Time 3D** and
   **Mario & Luigi: Paper Jam** (accurate multiplication forced on), **Super Mario 3D Land**
   (texture-copy timing), and **Luigi's Mansion: Dark Moon** (the right-eye toggle is ignored).
   Nothing to do.
2. **Pokémon X / Y** are the one real finding: on Android/Adreno they render some surfaces in the
   wrong colour unless *Accurate Multiplication* is on (upstream #1445, #1292, #440), and they
   are not in the hack table. Since the setting is global on Android anyway, the presets simply
   turn it on for everything. That is the single non-stock value in this guide.

Two more worth writing down, though neither changes what you should set:

- **Fire Emblem Fates (Birthright / Conquest)** — upstream #1989 reports that on Android/Vulkan,
  the 2D battle sprites break at anything above 1x, and that neither accurate multiplication nor
  the right-eye toggle helps. No preset reaches 1x on the Thor (Battery saver is 2x), so if you
  see it, set **Internal Resolution** to *Native (400x240)* by hand for those two games — which
  will show the preset as Custom — and pick Best looking again afterwards. Nothing the presets
  can detect.
- **Monster Hunter 4 Ultimate** — upstream #1852 is a Vulkan crash at 6x caused by the rasterizer
  cache collecting surfaces still in flight. 4x is well below where it was reported, and the cap
  in §3 keeps you there.

Everything else — all three Cooking Mamas, Hyrule Warriors Legends, Kid Icarus, Mario Kart 7,
Smash, the Zeldas, Miitopia, Shovel Knight, Story of Seasons, Bravely Default, Metroid Prime
Federation Force, Hey! Pikmin, and the rest — has no corroborated setting requirement at all.

Sources checked and what they were worth:

- `dist/compatibility_list` (the submodule) records **only** a 0-5 rating per title. No notes, no
  settings, no free text — and its own README says it is OpenGL-only, so it cannot answer a
  Vulkan question. It is not a settings database and should not be treated as one.
- Upstream issues (`azahar-emu/azahar`) — where the two findings above came from.
- There is no Azahar wiki or per-game settings database anywhere; third-party guides say "leave
  the accuracy options alone unless a game needs it", which is this document's conclusion too.

**Per-game overrides are not implemented on Android at all.** The per-game config UI is Qt-only
(`src/citra_qt/configuration/configure_per_game.*`); `Settings.saveSettings` on Android has a
literal `// TODO: Implement per game settings`, and upstream #38 is the open request. So even if
a game needed a different value, the only way to give it one today is to change it globally and
change it back.

---

## 6. How the preset control works

- **`utils/GraphicsPresets.kt`** — the preset table, the resolution arithmetic from §3, and the
  persisted choice (`Settings.PREF_GRAPHICS_PRESET`, a SharedPreferences int, never in
  config.ini).
- **`utils/SettingsProfile.kt`** — the single write path shared with `ThorDefaults`. It mutates
  the same setting objects the menu edits *and* writes each key straight to config.ini, so the
  values are indistinguishable from ones you picked by hand and survive a process kill.
- **`ThorDefaults`** no longer carries its own graphics values; its first-run profile splices in
  `GraphicsPresets.profileFor(BEST_LOOKING)`. That is why the Thor boots at the right resolution
  out of the box, and why that resolution now comes from the panel rather than a hardcoded 4.
- **Flipping to Custom** is one hook: `SettingsFragmentPresenter.putSetting()` runs on every hand
  edit, and `GraphicsPresets.onSettingEdited()` demotes the preset to Custom the moment an owned
  setting stops matching. Choosing a preset again puts everything back.
- If nothing has ever been chosen, the picker reports whichever preset the current configuration
  already matches rather than calling a stock setup "Custom".
- The device-awareness is not theoretical, and was confirmed on the device. On the Fold5 used
  for §2 the cover panel is 904x2316, so `min(2316 / 400, 904 / 240)` = 3.77: the picker there
  offers "Best looking 3x / Balanced 2x / Battery saver 1x", not the Thor's 4/3/2. Same code,
  different panel, different answer. Choosing it logged

  ```
  [GraphicsPresets] Applied BEST_LOOKING: resolution_factor=3, texture_filter=0,
  texture_sampling=0, async_shader_compilation=true, use_disk_shader_cache=true,
  shaders_accurate_mul=true, filter_mode=true
  ```

  and the row's value changed from Custom (which is what it correctly reported for a
  hand-edited configuration) to "Best looking (recommended)".
- The row is not runtime-runnable: it owns two settings the core only reads at boot (async shader
  compilation, accurate multiplication), so it greys out during emulation exactly as those rows
  below it already do.
