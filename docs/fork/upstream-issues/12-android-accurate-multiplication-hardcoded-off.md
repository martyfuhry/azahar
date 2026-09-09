# Android: `shaders_accurate_mul` is hardcoded off in the Android config reader, which makes Pokémon X render wrong out of the box

**Shape:** new issue. Two claims, one solid and one demonstrated on a single device:

1. The Android config reader hardcodes a `false` fallback for `shaders_accurate_mul`, bypassing `ReadSetting` and contradicting `settings.h`, so Android and desktop behave differently from one tree with nothing visible to the user. **Source-evident.**
2. On an Adreno 740 under Vulkan, that default makes Pokémon X render Froakie almost entirely white at the starter-selection scene, and enabling the setting fixes it. **Reproduced, n=1.**

**Searched first:** `gh issue list -R azahar-emu/azahar --search "accurate multiplication" --state all`, `"shaders_accurate_mul"`. Nothing filed for this mechanism. **#1445** is the open thread for the rendering symptom; this issue supplies a cause for at least the Android half of it, and directly contradicts the workaround currently accepted in that thread. **#1292** and **#440** are the same symptom, closed stale. **#1136** is a different bug in the same setting.

---

## Summary

Every setting on Android is read through `Config::ReadSetting`, which falls back to the shared
default in `src/common/settings.h`. **One setting is not.** `shaders_accurate_mul` is read
directly with a hardcoded fallback of `false`, while the shared default is `true`.

Desktop Azahar therefore runs with accurate multiplication **on** and Android runs it **off**,
from the same source tree. The key in a freshly generated `config.ini` is blank, so it reads as
untouched; the Kotlin settings enum independently restates `false`, so the UI looks
self-consistent; and there is no log line, comment or documentation saying the platforms differ.

The consequence is not theoretical. On a stock Android configuration, **Pokémon X renders a
starter Pokémon almost entirely white**, and the fix is a switch on the Graphics screen that
nobody has a reason to suspect.

## Verified reproduction

**Device.** AYN Thor, Android 13 (API 33), Snapdragon 8 Gen 2 (SM8550 / `ro.soc.model=QCS8550`),
**Adreno 740**, Qualcomm Vulkan driver **512.676.53**, `VK_VERSION 1.3.128` — all read from the
emulator's own boot log.

**Game.** Pokémon X, title ID `0004000000055D00`, at the starter-selection scene.

**Configuration**, from `config.ini` and confirmed against the boot log's own settings dump
(`common/settings.cpp:85`):

```
Renderer_GraphicsAPI: Vulkan          Renderer_UseHwShader: true
Renderer_UseResolutionFactor: 4       Renderer_UseShaderJit: true
Renderer_TextureFilter: None          Renderer_TextureSampling: Game controlled
```

`shaders_accurate_mul` was the **only** setting changed between the two runs. Everything else in
`[Renderer]` was untouched, and most of the section is blank, i.e. at its default.

**Steps.**

1. Stock configuration, `shaders_accurate_mul` blank in `config.ini` (so `false`, per the
   hardcoded fallback). Reach the starter-selection scene.
2. Observe **Froakie renders almost entirely white**, while **Chespin and Fennekin beside him
   render fully saturated and correct**. The selectivity matters: it matches #1445's title
   ("Certain pokemon have white buggy textures") and rules out a global colour or gamma problem.
3. Set accurate multiplication on (Graphics screen, or `shaders_accurate_mul = true`). Change
   nothing else. **Restart the emulator** — see the note on restarts below.
4. Return to the same scene: **Froakie renders correctly blue.**

**Evidence to attach at filing:** the two screenshots of the same scene, before and after. They
are the whole report and it should not be filed without them.

**Restart note, because it has confused reporters in the thread.** `Haisom` points out in #1445
that switching between hardware and software shaders needs a restart to take effect. Anyone
testing this should restart between runs rather than toggling in place, and — as `JengaMasterG`
found — a stale disk shader cache is a further confounder worth clearing if a result looks
inconsistent.

## How this squares with #1445's existing comments

The thread has accumulated contradictory advice, and this result resolves part of it while
leaving part open. Being explicit, because the contradictions are the reason this took a while to
pin down:

- **`sugarbeets`: "A fix for this is enabling accurate multiplication in shader settings."**
  **Confirmed** by the reproduction above.
- **`JengaMasterG`:** resolved it on an Adreno 640 with Vulkan *plus* accurate multiplication
  *plus* a shader-cache wipe. **Consistent** with the above.
- **`Haisom`: "Doesn't work on Android + OpenGL. Selecting Vulkan fixes the issue on Android but
  it's too laggy."** **Contradicted on both halves** by this run: the bug *was* present on
  Android under Vulkan, and accurate multiplication *did* fix it there. Selecting Vulkan alone
  is not sufficient. The OpenGL half is untested here and may still hold — the two renderers do
  not share the accurate-multiplication path, so it is entirely possible the setting behaves
  differently under GL.
- **`ShinyMooTank`:** the same white-texture symptom in Pokémon Alpha Sapphire on **desktop**
  (Ryzen 9 5900X, RTX 3080) with accurate multiplication **already enabled**, at 8x internal
  resolution with an xBRZ texture filter. **Still unexplained, and this issue does not explain
  it.**

**The honest reading is that the white-texture symptom has more than one cause, and this
identifies one of them.** A desktop reproduction with the setting already on cannot be caused by
an Android-only default, so either a second mechanism produces the same visual result, or
something else in that configuration (8x, xBRZ) is involved. Nothing here should be taken as
closing #1445, and #1292 / #440 should not be closed as duplicates on the strength of this alone.
What this does supply is a concrete, reproducible cause for the Android case and an explanation
for why the platform's *default* experience shows the bug.

## Root cause

`src/android/app/src/main/jni/config.cpp:142-143`, in `Config::ReadValues()`:

```cpp
    Settings::values.shaders_accurate_mul =
        android_config->GetBoolean("Renderer", "shaders_accurate_mul", false);
```

Every neighbouring line goes through the template instead:

```cpp
    ReadSetting("Renderer", Settings::values.graphics_api);
    ReadSetting("Renderer", Settings::values.async_shader_compilation);
    ReadSetting("Renderer", Settings::values.use_hw_shader);
```

and `ReadSetting` for a `bool` (`config.cpp:91-94`) passes the shared default:

```cpp
template <>
void Config::ReadSetting(const std::string& group, Settings::Setting<bool>& setting) {
    setting = android_config->GetBoolean(group, setting.GetLabel(), setting.GetDefault());
}
```

The shared default is `true`:

```cpp
// src/common/settings.h:563
SwitchableSetting<bool> shaders_accurate_mul{true, Keys::shaders_accurate_mul};
```

Three things then conspire to make it undiscoverable:

- **A blank key reads as untouched.** `jni/default_ini.h` generates every key with an empty
  value, so a user inspecting `config.ini` sees `shaders_accurate_mul =` and reasonably concludes
  nothing is overriding anything.
- **The Kotlin layer restates the wrong value.** `BooleanSetting.kt:83` declares its own default
  of `false` rather than deriving it, so the settings UI agrees with the hardcoded fallback and
  looks self-consistent.
- **Nothing announces the difference.** No log line, no comment, no documentation says Android
  departs from the shared default — from the reader's point of view it is not departing from
  anything.

For completeness on the per-title path: `src/common/hacks/hack_list.cpp` force-enables accurate
multiplication for Ocarina of Time 3D and three Mario & Luigi titles under
`HackType::ACCURATE_MULTIPLICATION`, applied via `GPU::ApplyPerProgramSettings`
(`gpu.cpp:385-399`). No Pokémon title is in that list, so Pokémon X gets the platform default.
Adding titles to that table is a possible mitigation, but it treats each game one at a time and
does not address the divergence.

## What to do about it

Two shapes, and **either would be a good outcome**. The choice of which default Android should
have is a maintainer's call and I am not trying to settle it.

**1. Make the divergence explicit.** If the `false` is a deliberate mobile performance
decision — plausible, since accurate multiplication costs shader throughput and mobile is where
that matters most — then the defect is that it is *invisible*, and the fix is to surface it. The
codebase already has the idiom: `settings.h` carries `#ifdef ANDROID` defaults for
`async_shader_compilation` (`settings.h:551-558`) and `use_vsync` (`settings.h:564-568`), each
with a comment explaining the mobile reasoning. Moving this one to the same form would keep
Android's behaviour byte-for-byte identical, put the difference in the file a contributor reads
to learn what a default is, let the Kotlin default derive from the shared value instead of
drifting from it, and carry a comment saying why — which currently exists nowhere.

**2. Change the default.** The reproduction above is an argument that `false` is the wrong
answer for at least some titles on at least some Adreno hardware, and that the cost of being
wrong is a visibly broken game with no clue pointing at the fix.

I would advocate for (1) at minimum, since it is smaller, preserves current behaviour, and fixes
the part that is unambiguously a defect. If the performance cost turns out to be small on mobile,
(2) as well.

No patch attached — it is a small change and the shape belongs upstream.

## Marty must verify before filing

- [ ] **Attach both screenshots.** Before and after, same scene, same device. Without them this is
  an assertion. Commit them alongside this draft (suggested:
  `docs/fork/baselines/2026-09-09-pokemon-x-accurate-mul/`) so they do not get lost.
- [ ] Confirm the two source lines are present in **upstream `master`** at a named commit, not
  just in my fork, and quote it. Check `git log -S "shaders_accurate_mul" -- src/android/app/src/main/jni/config.cpp`
  for a stated rationale — if one exists, lead with shape (1) and cite it.
- [ ] Re-run the before/after on an **official build from the release page**, not my checkout, so
  the report is about upstream. My "before" state is evidenced by a `config.ini` read with the
  key blank; capture the emulator's own `Renderer_ShadersAccurateMul: false` boot line too, since
  both my device logs have since rotated to `true` and no longer show the original state.
- [ ] **Say n=1 in the report and mean it.** One device, one driver (512.676.53), one game, one
  renderer. Do not generalise to "Android" or to other titles beyond noting the platform default
  is the same everywhere.
- [ ] **Do not present this as closing #1445**, and do not propose closing #1292 / #440 as
  duplicates. `ShinyMooTank`'s desktop report with the setting already on is unexplained and must
  be acknowledged in the body, not omitted. Offer this as a cause for the Android case.
- [ ] Measure what accurate multiplication costs on Adreno before arguing for shape (2). I have
  not measured it, and if it is expensive that materially strengthens the case that the `false`
  was deliberate — which makes shape (1) the right ask.
- [ ] Optional but valuable: test Android + **OpenGL** with the setting on, since `Haisom`'s
  claim that it does not help there is untested by this run and may well be true.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to read the config path and
correlate the defaults across `settings.h`, the Android reader and the Kotlin settings layer. I
reproduced the rendering bug and its fix on my own hardware and took the screenshots myself, and
I am writing this report in my own words.*
