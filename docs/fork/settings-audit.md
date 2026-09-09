# Settings audit — what is actually set on Marty's Thor, and what any of it costs

Three questions, in order:

1. **What is set on his device right now, and is any of it hurting him?** (§1–§3)
2. **Of everything Azahar shows on Android, what actually matters?** (§4)
3. **How do we stop a wrong setting surviving for years unnoticed?** (§5)

The prompt for this was `use_hw_shader = false` sitting in his `config.ini` next to
`resolution_factor = 4` — software vertex shading at the resolution where it costs the most.
He turned it on and the game ran, in his words, "SO MUCH BETTER". His complaint was the real
deliverable: *"this is so dumb that there's like dozens of settings and probably one of them
will make a huge difference but you have no idea what."*

**Source of the config read.** Live, from the device: `ANDROID_SERIAL=7f87e972`,
`adb shell cat /sdcard/azahar/config/config.ini`, 2026-09-09. 18,195 bytes, 448 lines,
13 sections. Strictly read-only — nothing was written, launched, or installed. The device
carries two builds: `org.azahar_emu.azahar.thor` (`thor-v1-rc2-thor`, versionCode 33735322,
first installed 2026-09-08 21:07, updated 2026-09-09 09:27) and
`org.azahar_emu.azahar.debug` (`45c7d2cdd-vanilla-debug`). There is exactly one config file;
there are no per-game configs on Android.

---

## 1. The headline

**His configuration is clean.** After his own hardware-shader fix there is nothing else stale
in it. Twenty-one keys carry an explicit value; **seventeen of them are the default**, and the
**four that are not are all deliberate** — they are the Thor profile this fork writes on first
run. There is no second `use_hw_shader` hiding in there.

That is the honest answer to "probably one of them will make a huge difference." For his
current file: no, not any more. But the *mechanism* that let the first one survive is intact
and is the thing worth fixing, so §5 is the load-bearing section of this document.

One live issue remains, and it is invisible rather than stale: **`shaders_accurate_mul` is
effectively off** because the key is blank and Android's reader hardcodes a `false` fallback
that disagrees with the C++ default. See §3.

### How the damage actually happened, verified

Worth writing down, because it is the failure mode §5 has to close.

- `config.ini` lives on `/sdcard`, not in app-private storage. **It survives uninstall,
  reinstall, and every upgrade.** The Thor package was first installed 2026-09-08, but the
  config predates it.
- `ThorDefaults.applyOnFirstRun()` runs at most **once per install**, guarded by a
  SharedPreferences flag `thor_defaults_applied` (`ThorDefaults.kt:35`). It ran on
  **2026-09-08, against the rc1 profile** (`e750a4056`), and that profile did not mention
  `use_hw_shader` at all — hardware shaders were only added to the shared preset on
  2026-09-09 in `0b953ce2b`, *after* rc2 was cut.
- Even had it run later, it would not have helped: the non-forcing pass in
  `SettingsProfile.kt:48-50` skips **any key config.ini already carries a value for**. A blank
  key gets filled; an explicitly wrong one is skipped forever.
- The flag is set unconditionally at `ThorDefaults.kt:179-182`, **even when nothing was
  written**. So the pass can never run again on this install, no matter what we add to the
  profile later.

The four Thor-profile values in his file (§2) match the rc1 profile exactly. That is the proof
the pass ran once, filled the blanks it knew about on 2026-09-08, and has been inert ever
since. **Every improvement we have made to the profile since then has never reached his
device.** That is the bug, not the individual setting.

### Correcting one thing in the brief

The brief described the hardware-shader toggle as sitting "unlabelled next to CPU JIT and
shader JIT". It is not unlabelled. `SettingsFragmentPresenter.kt:2048-2056` gives it a title
and a description:

> **Enable Hardware Shader** — *Uses hardware to emulate 3DS shaders. When enabled, game
> performance will be significantly improved.*

So the label is clear and even states the direction. What is misleading is the **header above
the whole screen** (`strings.xml:348`):

> *Warning: Modifying these settings will slow emulation*

That is wrong for three of the eleven switches on that screen. `use_cpu_jit`, `use_hw_shader`
and `use_shader_jit` all default **on**, and modifying them means turning them **off**, which
is the slowdown. A user who reads the warning as "these are the performance knobs" and starts
flipping them gets the opposite of what the screen promised. That is a better explanation of
the original mis-toggle than an absent label, and it is a one-line upstream fix (§6).

---

## 2. Every explicit value in his config

"Default" here means **the value the emulator actually uses on Android when the key is blank**,
which is not always what `settings.h` says — `jni/config.cpp` overrides several fallbacks, and
the Kotlin enums have a third opinion that only affects the UI. Where they disagree the
Android-effective value is the one that matters, and the disagreement is called out.

| Key | His value | Android-effective default | Verdict |
|---|---|---|---|
| `use_hw_shader` | `true` | `true` (`settings.h:560`) | **Was `false`. Repaired by hand 2026-09-09.** The one that mattered. |
| `resolution_factor` | `4` | `1` (`settings.h:572`) | **Non-default, deliberate.** Thor profile. |
| `layout_option` | `1` (Single Screen) | `2` (Large Screen — `config.cpp:189-190`) | **Non-default, deliberate.** Thor profile. |
| `secondary_display_layout` | `4` (Opposite Screen Only) | `0` (None — `config.cpp:221-223`) | **Non-default, deliberate.** Thor profile. |
| `autosave_mode` | `2` (Always) | `0` (Off — `settings.h:523`) | **Non-default, deliberate.** Thor profile. |
| `cpu_clock_percentage` | `100` | `100` | = default |
| `graphics_api` | `2` (Vulkan) | `2` | = default |
| `async_shader_compilation` | `true` | `true` on Android (`settings.h:555`) | = default |
| `texture_sampling` | `0` (Game controlled) | `0` | = default |
| `use_vsync` | `false` | `false` on Android (`settings.h:565`) | = default |
| `use_disk_shader_cache` | `true` | `true` | = default |
| `use_integer_scaling` | `false` | `false` | = default |
| `use_frame_limit` | `true` | `true` | = default |
| `frame_limit` | `100` | `100` | = default |
| `filter_mode` | `true` | `true` (`settings.h:646`) | = default |
| `texture_filter` | `0` (None) | `0` | = default |
| `screen_orientation` | `2` (Automatic) | `2` | = default |
| `enable_secondary_display` | `true` | `true` | = default |
| `swap_screen` | `false` | `false` | = default |
| `enable_audio_stretching` | `true` | `true` | = default |
| `perf_log_interval` | `0` (off) | `0` | = default |

**`[Debugging]` and `[Utility]` are entirely clean.** Every key in both sections is blank:
`renderer_debug`, `enable_rpc_server`, `deterministic_async_operations`, `record_frame_times`,
`instant_debug_log`, `pica_debugging`, `toggle_unique_data_console_type`,
`dump_textures`, `custom_textures`, `preload_textures`. Nothing is on that should not be. The
only non-blank key in `[Debugging]` is `perf_log_interval = 0`, which is this fork's own
perf-logging interval and `0` means off — correct.

### What the four deliberate values cost or buy

Ranked by how much they move:

1. **`resolution_factor = 4`** — draws the game at 4× the 3DS's own resolution.
   *Buys:* the entire visible difference in sharpness; 4× is the largest whole factor the
   Thor's 1080×1920 top panel can use (`min(4.8, 4.5)`).
   *Costs:* measured on the same silicon, **3.9 ms of GPU time in a 16.7 ms frame** at 100%
   speed with zero janky frames — 23% of the budget, with room. Cost is quadratic, so this is
   the only setting on the whole Graphics screen with a real quality/speed trade.
   *Verdict:* deliberate, correct, affordable. Full numbers in
   `docs/fork/graphics-settings-guide.md` §2.

2. **`autosave_mode = 2` (Always)** — resumes from a fresh autosave without asking.
   *Buys:* the Thor's lid and the AYN launcher background the app constantly; without this a
   backgrounded session is a lost session.
   *Costs:* periodic save I/O. Negligible against the alternative.
   *Verdict:* deliberate, and the right call for this hardware specifically.

3. **`layout_option = 1` + `secondary_display_layout = 4` + `enable_secondary_display = true`**
   — one 3DS screen per physical panel, top on top.
   *Buys:* this is what makes a Thor behave like a 3DS instead of stacking both screens onto
   the top panel.
   *Costs:* nothing measurable; it is a presentation choice.
   *Verdict:* deliberate, and the single most valuable thing the Thor profile does.

**Nothing else in his config costs him anything.** The remaining seventeen explicit values are
the defaults written out longhand by the first-run profile, which is by design — the profile
lists values that already match the default so that "Apply Thor defaults" can put them back.

---

## 3. The one live issue: `shaders_accurate_mul`

This is not a stale hand toggle and it is not a non-default value. The key is **blank** in his
config. It is a problem anyway, and it is exactly the class of thing a user cannot discover.

```
src/android/app/src/main/jni/config.cpp:142-143
    Settings::values.shaders_accurate_mul =
        android_config->GetBoolean("Renderer", "shaders_accurate_mul", false);
```

Every other setting on Android is read through `Config::ReadSetting`, which passes
`setting.GetDefault()` — the C++ default. This one key bypasses that and **hardcodes `false`**.
The C++ default is `true` (`settings.h:563`). Desktop Azahar therefore runs with accurate
multiplication on and Android runs with it off, from the same source tree, silently.

- **What it does:** emulates the PICA200's multiply semantics exactly instead of using the
  GPU's fast path.
- **What off costs:** wrong colours and missing effects in titles that depend on the exact
  rules. Azahar force-enables it per title for Ocarina of Time 3D and Paper Jam via
  `src/common/hacks/hack_list.cpp`, but **not** for Pokémon X/Y, which needs it too.
- **What on costs:** a little shader throughput. Not measured on the Thor; it is small enough
  that upstream ships it on by default everywhere except here.
- **Why it has not reached him:** `GraphicsPresets.kt:160` sets it to `true`, and that entry
  predates rc2 — but his first-run pass ran on 2026-09-08 against the rc1 profile, which had
  no such entry, and the one-shot flag has blocked every pass since (§1).

**Ranked #1 of what is left**, because it is the only thing in his configuration that is
currently wrong, and because it is invisible: the key looks untouched, the UI shows the switch
off, and nothing indicates the emulator is disagreeing with its own default.

**Recommendation:** delete the special case and let it read like everything else, so Android
inherits the `true` from `settings.h`. That is a two-line change in `jni/config.cpp` plus
`BooleanSetting.kt:83` (`false` → `true`) to keep the UI honest. Worth an upstream issue (§6).

### Documentation that actively misleads

While checking defaults, five comments in `jni/default_ini.h` contradict `settings.h`. These
are what a curious user reads when deciding whether to change something, so they are not
harmless:

| Key | Comment claims | Actual default |
|---|---|---|
| `is_new_3ds` | "0: Old 3DS (default)" | `true` — **New 3DS** (`settings.h:499`) |
| `simulate_3ds_gpu_timings` | "1 (default): Enable delay" | `false` (`settings.h:581`) |
| `turbo_limit` | "100 (default)" | `200` (`settings.h:574`) |
| `large_screen_proportion` | "Default value is 2.25" | `4.0` in `settings.h:596`; Android reader uses `2.25` (`config.cpp:196`) |
| `secondary_display_layout` | lists values 0–3, "0 (default)" | enum has **8** values (`settings.h:73-82`); Android default is `0`, C++ default is `4` |

The `is_new_3ds` one is the most consequential: his blank key means he is running as a **New
3DS**, which is the right answer and the opposite of what the file tells him.

---

## 4. What actually moves the needle on Android

This complements `docs/fork/graphics-settings-guide.md`, which is the plain-language answer for
the Graphics screen specifically and has the measurements. This section is the wider view: the
whole settings surface, ranked by whether touching it can help or hurt you.

The guide answers *"what should I set?"*. This answers *"which of these can silently cost me
something?"* — which is the actual complaint.

### The short list: worth ever touching

Six things. Everything else is noise.

| # | Setting | Screen | Right direction | Why |
|---|---|---|---|---|
| 1 | **Internal resolution** (`resolution_factor`) | Graphics | As high as the panel can use — **4×** on a Thor | The only genuine quality/speed dial. Quadratic cost. Everything above the panel's pixel count is invisible and still costs. |
| 2 | **Screen layout block** (`layout_option`, `enable_secondary_display`, `secondary_display_layout`) | Layout | One 3DS screen per panel | On dual-panel hardware this is the difference between a 3DS and a smushed phone port. Nothing else on the Layout screen matters. |
| 3 | **Autosave** (`autosave_mode`) | System | **Always** on a handheld | Anything that backgrounds the app aggressively — a lid, a launcher — eats sessions without it. |
| 4 | **Hardware shaders** (`use_hw_shader`) | Debug | **On**, always | Not a dial. Off means `pica_core.cpp:1068` refuses `accelerate_draw` and the CPU interprets the 3DS vertex pipeline. Slower, hotter, worse battery. Listed here only so you know to check it. |
| 5 | **CPU JIT** (`use_cpu_jit`) | Debug | **On**, always | Same shape: off is the ARM11 interpreter. Nobody playing a game wants this. |
| 6 | **Accurate multiplication** (`shaders_accurate_mul`) | Graphics | **On** | Correctness, not speed. See §3 — Android currently defaults it wrong. |

Items 4–6 are on this list because they can be *wrong*, not because they are worth tuning. The
only one a player should ever deliberately move is #1, plus #2 and #3 once at setup.

### The leave-alone list

Grouped by why they do not deserve attention.

**Already correct, and the default is the answer.** `graphics_api` (Vulkan on Adreno),
`async_shader_compilation`, `use_disk_shader_cache`, `spirv_shader_gen`,
`disable_spirv_optimizer`, `filter_mode`, `use_skip_duplicate_frames`, `async_presentation`,
`use_frame_limit` / `frame_limit` at 100, `enable_audio_stretching`, `async_custom_loading`,
`delay_start_for_lle_modules`, `use_display_refresh_rate_detection`.

**Sounds important, is not.** `texture_filter` — does *not* raise render resolution, it upscales
the artwork; costs 86 MiB of GPU memory at 3× measured, and the scaled-texture path has open
crash reports on Adreno including one recovered from this Thor. `texture_sampling` — "Game
controlled" is right; the overrides make games look wrong. `use_integer_scaling` — shrinks the
picture on these panels. `use_vsync` — the frame limiter does the pacing; vsync only adds
latency. `turbo_limit` — only meaningful bound to a hotkey.

**Actively harmful if turned on.** `renderer_debug` (validation layers),
`deterministic_async_operations` (the source comment says outright it "makes performance worse
if enabled"), `enable_rpc_server`, `record_frame_times`, `instant_debug_log`,
`pica_debugging` (does nothing on Android), `dump_textures`, `preload_textures`,
`custom_textures` (the one feature that can genuinely exhaust memory on an 8 GB device).

**Workarounds for specific broken titles, not global settings.**
`delay_game_render_thread_us` (leave 0), `simulate_3ds_gpu_timings` (leave off),
`disable_right_eye_render` (breaks Metroid: Samus Returns' bottom screen — upstream #740,
maintainer's verdict "just don't use it"), `cpu_clock_percentage` (100; over- and under-clocking
both break timing-sensitive games).

**Meaningless on this hardware.** The entire Cardboard VR block, the stereoscopy block with 3D
off, `swap_eyes_3d`, `factor_3d`.

**Taste, and genuinely so.** Performance overlay and its seven sub-toggles, `screen_gap`,
`aspect_ratio`, `upright_screen`, `bg_red`/`green`/`blue`, `volume`, `region_value`, `language`,
custom layout coordinates.

### The general rule, stated plainly

> On the Graphics screen, one setting trades quality for speed: internal resolution.
> On the Layout screen, one decision matters: which 3DS screen goes on which panel.
> On the Debug screen, **nothing should ever be changed** — three of its switches are
> performance features that are already on, and turning them off is pure loss.

If that rule had been on the Debug screen instead of "Modifying these settings will slow
emulation", the hardware-shader toggle would probably never have been flipped.

---

## 5. Making it not recur: a repair pass with provenance

The current behaviour is the bug. `ThorDefaults` deliberately declines to overwrite an existing
config, sets its one-shot flag whether or not it wrote anything, and is gated to Thor hardware.
The result is that every opinion we have formed since 2026-09-08 has never reached the device
it was formed for.

The design below **repairs rather than warns**, runs without being asked, and never overwrites a
real decision.

### The classification

Three tiers. The tier a setting lands in is a claim about *the setting*, not about Marty.

#### Tier 1 — wrong. Always repaired, on every upgrade, even over an explicit user change.

Both tests must pass: **the effect is large enough to feel**, and **no legitimate reason exists
to want the other value** — not "we prefer this", genuinely no hardware, game, or person for
which the other value is right.

| Key | Correct value | Why there is no other side |
|---|---|---|
| `use_hw_shader` | `true` | `pica_core.cpp:1068` gates `accelerate_draw` on it. Off = the 3DS geometry pipeline interpreted on the CPU. Slower, hotter, worse battery, at every resolution and on every device. The type case; he felt it instantly. |
| `use_cpu_jit` | `true` | Off = the ARM11 interpreter (`core.cpp:605`). Same shape, larger factor. |

**That is the whole of tier 1, and it should stay that way.** Two entries that are obviously
right beat a longer list that argues.

*Candidates I deliberately did not add, and why.* `renderer_debug` and
`deterministic_async_operations` are the same shape — pure loss, no player wants them — and I
expect both belong here. I am not putting them in tier 1 **without measuring them on a release
build first**: `renderer_debug` only costs what the validation layers cost, and I have not
confirmed those layers are even shipped in the release APK, in which case the setting is nearly
free and fails the "large effect" test. Measure, then promote. Both are blank in his config, so
nothing is riding on it today.

*The cost of tier 1 being absolute.* A developer deliberately disabling the JIT to debug it will
have that undone at the next upgrade. That is the correct trade — the escape hatch below exists
for exactly this — and it is a rarer event than a player losing half their performance for a
year.

#### Tier 2 — we know better. Applied on every upgrade, but only where we still own the value.

Most players are happier with our value and a naive user is worse off without it, but a
reasonable person could differ. **Provenance decides**: if the live value still equals what we
last wrote, it is ours and we update it; if it differs, the user changed it and we never touch
it again.

| Key | Our value | Reasoning, and the legitimate other side |
|---|---|---|
| `async_shader_compilation` | `true` | Marty's own type case: trades compile stutter for momentary pop-in. Someone who hates pop-in more than hitching could reasonably differ. |
| `use_disk_shader_cache` | `true` | Second session through an area is smooth. Other side: storage, or working around a corrupted cache. |
| `use_shader_jit` | `true` | Pure loss when off — same computation, slower engine (`pica_core.cpp:73`). *Hesitation:* with hardware shaders on, most draws bypass the software vertex path entirely, so the effect is real but game-dependent and not always large. It fails tier 1's "large effect" test, so it sits here. |
| `shaders_accurate_mul` | `true` | Correctness (§3). Costs a little shader throughput, so someone chasing frames on a title that does not need it could differ. Better fixed at the default (§3); until then, repair it. |
| `texture_filter` | `0` (None) | Memory and stability: 86 MiB of GPU memory at 3× for zero extra render resolution, plus open Adreno crash reports. *Hesitation:* this is genuinely a **look** preference, which pulls toward tier 3, and I nearly put it there. It stays in tier 2 only because the shared preset already owns it and because the failure mode is a crash rather than an opinion. If you disagree, moving it to tier 3 costs nothing. |
| `texture_sampling` | `0` (Game controlled) | Overrides make games look wrong. Someone chasing a specific look could differ. |
| `filter_mode` | `true` | No preset renders at exactly the panel's pixel count. Pixel purists could differ. |
| `simulate_3ds_gpu_timings` | `false` | A per-title workaround, not a global. Someone playing an affected title could want it. |
| `delay_game_render_thread_us` | `0` | Same shape. |
| `use_skip_duplicate_frames` | `true` | Saves GPU work in 30 fps titles. Costs a blocking GPU drain per skip, so a differing preference is conceivable. |

Tier 2 is where most of the value is, and it is safe precisely because it can be opted out of by
simply changing the setting.

#### Tier 3 — genuine taste. Set once on first run, never touched again.

`resolution_factor`, `layout_option`, `secondary_display_layout`, `enable_secondary_display`,
`swap_screen`, `screen_orientation`, `aspect_ratio`, `upright_screen`, `screen_gap`,
`use_frame_limit`, `frame_limit`, `turbo_limit`, `cpu_clock_percentage`, `autosave_mode`,
`enable_audio_stretching`, `volume`, `is_new_3ds`, `region_value`, `language`, `use_vsync`,
`use_integer_scaling`, `graphics_api`, the performance-overlay block, the stereoscopy block, the
Cardboard block, and all custom-layout coordinates.

*Hesitations recorded, per instruction:*
- **`use_vsync`** — off is right on Android and the frame limiter does the pacing, which argues
  tier 2. But a user with visible tearing genuinely wants it on, and that is a real preference
  about their own eyes. Tier 3.
- **`cpu_clock_percentage`** — values like 25 are simply broken, which tempts a sanity repair.
  But overclocking is a legitimate per-game tactic for specific titles. Tier 3; do not
  special-case it.
- **`graphics_api`** — Vulkan is right on Adreno, but switching renderers mid-life is a bigger
  decision than a repair pass should make, and OpenGL is a real fallback when a driver breaks.
  Tier 3.

### The mechanism: a provenance record

Tier 2 needs to know whether the current value is ours or the user's. Record what we wrote.

**Where it lives: a sidecar next to config.ini** —
`/sdcard/azahar/config/fork-profile.json`.

This placement is the important design decision. The obvious home is SharedPreferences, which
is where `thor_defaults_applied` and the graphics preset already live — and it is **wrong for
this**. SharedPreferences is app-private and is wiped on uninstall, while `config.ini` sits on
`/sdcard` and survives. After a reinstall the record would be gone but the config would remain,
every tier-2 key would look "not ours", and tier 2 would be frozen forever on exactly the
devices that have been around longest. The record has to have the same lifetime as the thing it
describes.

A `[ForkProfile]` section inside `config.ini` would also survive — the Android save path is
`org.ini4j.Wini`-based and preserves unknown keys verbatim, and the C++ side on Android only
ever *reads* the file (`jni/config.h` declares no save) — but a separate file is clearer and
cannot be mangled by a "reset to defaults", which deletes `config.ini` outright
(`SettingsActivity.kt:204-236`).

```json
{
  "schema": 1,
  "lastAppliedVersionCode": 33735322,
  "owned": { "use_disk_shader_cache": "true", "shaders_accurate_mul": "true" },
  "lastRepair": {
    "at": "2026-09-09T13:40:00Z",
    "fromVersionCode": 33675801,
    "changed": [
      { "key": "use_hw_shader", "from": "false", "to": "true", "tier": 1,
        "why": "Hardware shaders were off — the CPU was doing work the GPU should have." }
    ]
  }
}
```

**The algorithm**, run once per `versionCode` change (plus once on adoption):

- **Tier 1:** if the live value is not the correct value, write it. Unconditional. Record it in
  `lastRepair`.
- **Tier 2, key present in `owned`:** if live value == `owned[key]`, it is still ours — write
  the current opinion and update `owned`. If it differs, the user changed it: **delete the key
  from `owned` permanently** and never touch it again.
- **Tier 2, key absent from `owned`:** we have not had an opinion on this key before. Write it
  once and record it.
- **Tier 3:** untouched, except by the existing first-run pass.

**The one judgement call to sign off on.** That last rule is how a *newly added* tier-2 key
reaches an existing install — and it means the first time we form an opinion about a key, we
overwrite whatever is there, including a value the user set deliberately before we had an
opinion. I think that is right and it is what you asked for ("a user who inherits a stale value
is simply never receiving our opinion"), but it is the only step in the design that can
overwrite a real decision, so it should be a conscious choice rather than a side effect. The
alternative — seeding `owned` from the live config on adoption — is safer and permanently
freezes tier 2 on every existing install, which defeats the point.

### Visibility and the escape hatch

- **Log every change** with its reason, in the plain-language form above, at `Log.info`. The
  `lastRepair` block in the sidecar is the durable copy.
- **One-line notice**, not a dialog: after a pass that changed something, a snackbar on the game
  list — *"Repaired 2 settings for performance"* — with a **Details** action opening a plain
  list of what changed, from what, to what, and why. A pass that changed nothing is silent.
- **Undo:** the details screen offers **Undo** for the whole pass, which restores the recorded
  `from` values and, for tier 2, marks those keys as the user's forever. This is the honest way
  to let someone say "I meant that": it is discoverable exactly when it is relevant, and it
  costs nothing when unused.
- **Escape hatch:** a single switch in Settings → System, *"Keep my settings exactly as I set
  them"*, which disables the whole pass including tier 1. One switch, not per-key, off by
  default, and deliberately not on the path anyone walks by accident.
- **Keep "Apply Thor defaults" unchanged.** It stays the explicit, forcing, full-profile reset
  including tier 3, and it should also reset `owned` to everything it just wrote.

### Where it lives and what it costs

**New file:** `src/android/app/src/main/java/org/citra/citra_emu/utils/SettingsRepair.kt`,
holding the tier tables and the pass, plus a small `ProfileRecord.kt` for the sidecar. I have
**not written either** — `agent-thor-corrections` owns `GraphicsPresets.kt` and
`ThorDefaults.kt`, and the pass has to call into both, so this needs sequencing rather than a
race.

**Wiring:** the same two call sites `ThorDefaults.applyOnFirstRun()` already uses —
`CitraApplication.kt:65` and `CitraDirectoryHelper.kt:79` — but gated on a stored `versionCode`
rather than a one-shot boolean. It reuses `SettingsProfile.write` and the existing
`AbstractSetting` singletons, so writes are indistinguishable from menu edits, as today.

**Scope:** all devices, not just Thor. Tiers 1 and 2 are claims about the emulator, not about
the AYN Thor; only tier 3 is device-shaped, and that stays in `ThorDefaults`.

**Cost:** roughly 250–350 lines of Kotlin plus ~40 of wiring and a handful of strings. Half a
day for the pass and the record, another half for the notice, details screen and undo. Unit
tests are straightforward — the tier tables are data and the algorithm is a pure function over
(live config, record) — and need no device. **No C++ changes.**

**Two bugs to fix while in there**, both root causes rather than symptoms:

1. `ThorDefaults.kt:179-182` sets `thor_defaults_applied` even when nothing was written. Whether
   or not it moves to the repair pass, that flag should reflect what happened.
2. `jni/config.cpp:142-143`'s hardcoded `false` for `shaders_accurate_mul` (§3). Fixing the
   default is better than repairing it on every device forever.

---

## 6. Should some of this not be user-facing at all?

Yes, and it is worth raising upstream. Three items, in descending order of how likely they are
to be accepted.

1. **`shaders_accurate_mul`'s Android default contradicts the C++ default** (§3). This is a
   plain inconsistency in one line, it silently costs correctness in titles the per-title hack
   table does not cover, and the fix is two lines. **File it.**
2. **The Debug screen's warning header is wrong for three of its switches** (§1).
   *"Modifying these settings will slow emulation"* is false for `use_cpu_jit`,
   `use_hw_shader` and `use_shader_jit`, where modifying means turning off something already on.
   Rewording it to name the direction — *"These settings are already set for best performance.
   Changing them will usually make emulation slower."* — is a strings-only change and plausibly
   prevents the exact failure that started this. **File it.**
3. **Should `use_hw_shader`, `use_cpu_jit` and `use_shader_jit` be exposed to players at all?**
   My position: no. They are debugging affordances with no player-facing use, and each one has a
   failure mode where the emulator keeps working and just gets much worse — the hardest kind of
   setting to diagnose, because nothing looks broken. The right shape is a developer-options
   gate, the way Android itself hides its equivalents. This is the most valuable of the three
   and the least likely to be accepted, since removing user-facing options is contentious and
   these have been there since Citra. **Worth filing, expect pushback**, and note that our repair
   pass gets us the benefit locally either way.

Three smaller code defects found while auditing, all upstream, all trivial, none urgent:

- `SettingsFile.kt:250-254` — the `IntListSetting` branch assigns the list then falls through to
  `return null`, so `layouts_to_cycle` is parsed and immediately discarded. It is never loaded.
- `use_frame_limit` is declared twice, as `BooleanSetting.USE_FRAME_LIMIT`
  (`BooleanSetting.kt:105`) and `IntSetting.USE_FRAME_LIMIT` (`IntSetting.kt:59`), against the
  same key. `settingFromLine` tries booleans first, so the Int variant is dead code.
- The five `default_ini.h` comments that contradict `settings.h` (§3).

---

## 7. What this audit is not

- **Nothing was written to the device.** Read-only throughout, as instructed.
- **The performance claims are measurements where §2 of the graphics guide has them and
  reasoning from source where it does not.** `shaders_accurate_mul`'s cost on Adreno is *not*
  measured; the argument for it is correctness, and if it turns out to cost real frames on the
  Thor that changes its tier, not its correctness.
- **`renderer_debug` and `deterministic_async_operations` are unmeasured** and deliberately left
  out of tier 1 for that reason (§5).
- **One title, one device family.** The frame-time numbers behind §2 and §4 come from Animal
  Crossing: New Leaf on a Fold5 with the same SoC. A genuinely GPU-bound title is what would
  make `resolution_factor` look different, and that run is still pending.
