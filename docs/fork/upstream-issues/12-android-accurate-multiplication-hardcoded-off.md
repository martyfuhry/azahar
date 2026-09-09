# Android: `shaders_accurate_mul` is hardcoded off in the Android config reader, so Android and desktop silently disagree from one tree

**Shape:** new issue. Scope is the **default divergence only** — this draft deliberately does *not* claim to explain any existing rendering bug. See "What this is not" below, which exists because the first version of this draft got it wrong.

**Searched first:** `gh issue list -R azahar-emu/azahar --search "accurate multiplication" --state all`, `"shaders_accurate_mul"`, `"accurate multiplication android default"`. Nothing filed for this mechanism.

---

## Summary

Every setting on Android is read through `Config::ReadSetting`, which falls back to the shared
default in `src/common/settings.h`. **One setting is not.** `shaders_accurate_mul` is read
directly with a fallback of `false`, hardcoded in the Android reader, while the shared default
is `true`.

Desktop Azahar therefore runs with accurate multiplication **on** and Android runs it **off**,
out of the same source tree, with nothing in the UI or the config file to indicate a
disagreement. The key in a freshly generated `config.ini` is blank, so it reads as untouched;
the switch in the settings UI shows off; and there is no log line, comment or documentation
saying the platforms differ.

**The reportable defect is the invisibility, not necessarily the value.** If the `false` is a
deliberate mobile performance decision, that is a perfectly reasonable call — but it is
currently expressed in a way that no user can discover and that contradicts the file a
contributor would read to find out what the default is. See "If this is deliberate" below; a
maintainer can fix this either by changing the value or by making the divergence explicit, and
the second option is entirely satisfactory.

## Environment

- Azahar: observed on `master` in a fork checked out at `2026-09-09`. The two lines are
  long-standing and are not fork changes — confirm the exact upstream commit before filing.
- Config read from: AYN Thor, Android 13 (API 33), SM8550 / Snapdragon 8 Gen 2, Adreno 740.
- The bug is **not device-specific**. It is a source-level divergence affecting every Android
  build on every GPU.

## Reproduction

1. Install any Android build and complete setup so a `config.ini` is generated.
2. Observe the key is blank, as generated:

```
adb shell "grep -n 'shaders_accurate_mul' /sdcard/azahar/config/config.ini"
# shaders_accurate_mul =
```

3. Launch any title and read the effective value back out of the log:

```
adb logcat -d | grep Renderer_ShadersAccurateMul
```

4. Do the same on a desktop build with an equally untouched config.

## Expected

A blank key means "no opinion", so the emulator uses the shared default from
`src/common/settings.h` — as it does for every other setting the Android reader handles. That
default is `true`, so Android should log `Renderer_ShadersAccurateMul: true`.

Or, if Android genuinely should differ, the difference should be visible: declared in
`settings.h` where the other platform-specific defaults live, and discoverable by a user.

## Actual

Android logs `false`. Desktop logs `true`. Same tree, same blank key, opposite behaviour, and
nothing anywhere says so.

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

The hardcoded `false` is the only thing producing the divergence. The Kotlin enum agrees with
the hardcoded value rather than the shared one (`BooleanSetting.kt:83`), so the settings UI
looks self-consistent — which is part of why this is invisible from outside.

Two things make it undiscoverable from the user side:

- **A blank key reads as untouched.** `jni/default_ini.h` generates every key with an empty
  value, so a user inspecting `config.ini` sees `shaders_accurate_mul =` and reasonably
  concludes nothing is overriding anything.
- **Nothing announces it.** There is no log line or comment noting that the Android reader is
  departing from the shared default, because from the reader's point of view it is not
  departing from anything.

## If this is deliberate

It may well be. Accurate multiplication costs shader throughput, and mobile GPUs are the place
where that is most likely to matter — so "Android defaults this off on purpose" is an entirely
plausible history, and I have not found a commit message or comment either way.

**If so, the defect is that it is invisible, and the fix is to surface it rather than to flip
it.** The codebase already has the idiom: `settings.h` carries `#ifdef ANDROID` defaults for
`async_shader_compilation` (`settings.h:551-558`, with a comment explaining the mobile
reasoning) and `use_vsync` (`settings.h:564-568`). Moving this one to the same form would:

- keep the Android behaviour exactly as it is today;
- put the platform difference in the one file a contributor reads to learn what a default is;
- let the Kotlin default be derived from the shared value instead of independently restating it,
  removing the chance of the two drifting;
- carry a comment saying *why* Android differs, which currently exists nowhere.

That is the outcome I would actually advocate for, and it is a smaller change than flipping the
default. **The substantive question of which value Android should have is a maintainer's call
and I am not trying to settle it.** The narrower point is not a matter of taste: the answer
should not be written in two places that disagree, in a way no user can see.

No patch attached — it is a small change and the choice of shape belongs upstream.

## What this is not

**This does not claim to explain #1445, #1292, #440, or any other rendering report.** An earlier
version of this draft did, and the evidence does not support it. Recording the reasoning so
nobody re-derives the wrong conclusion:

#1445 ("Pokemon X - Certain pokemon have white buggy textures") looked like a promising match —
Android, Adreno, and a symptom that accurate multiplication plausibly affects. Reading the full
comment thread, the picture is mixed and clearly multi-factor:

- **`sugarbeets`** reports that enabling accurate multiplication fixes it.
- **`Haisom`** replies that it *"Doesn't work on Android + OpenGL. Selecting Vulkan fixes the
  issue on Android but it's too laggy"*, with a screenshot, and reports the same on an Alpha 4
  pre-release.
- **`ShinyMooTank`** reports the same white-texture symptom in Pokémon Alpha Sapphire on
  **desktop** (Ryzen 9 5900X, RTX 3080) with accurate multiplication **already enabled** — where
  it already defaults on — alongside 8x internal resolution and an xBRZ texture filter.
- **`takeshineale128`** sees no problem at all on a GTX 1660 or an S25 Ultra.
- **`JengaMasterG`** resolved it with Vulkan *plus* accurate multiplication *plus* wiping the
  shader cache, on an Adreno 640.

So the symptom appears on desktop where the setting is already on, survives the setting being
enabled on Android + OpenGL, and is most consistently avoided by changing renderer. Accurate
multiplication appears in two reporters' working configurations but is plainly not sufficient
on its own, and the renderer and possibly the shader-cache state are involved. **Whatever is
happening in #1445 is not "the Android hardcoded `false`".**

Those threads are still worth citing here, but only as **context that users are confused by
graphical differences between platforms, renderers and versions** — a landscape in which an
undocumented per-platform default difference is an unnecessary extra variable for anyone trying
to debug their own setup. That is a supporting argument for making the divergence visible. It is
not a claim that fixing it fixes anything else.

Related for the same reason, and equally not explained by this: **#1136** (closed,
`priority - high`) was a different Android bug in this same setting, whose reporter noted in
passing that *"the config file generated by this build also doesn't seem to have values
associated with these settings"* — the blank-key observation, from a user who could not make
sense of it.

For completeness on the per-title path: `src/common/hacks/hack_list.cpp` force-enables accurate
multiplication for Ocarina of Time 3D and three Mario & Luigi titles via
`HackType::ACCURATE_MULTIPLICATION`, applied through `GPU::ApplyPerProgramSettings`
(`gpu.cpp:385-399`). No Pokémon title is in that list. This is stated as fact about the table,
**not** as evidence that any Pokémon title needs to be.

## Marty must verify before filing

- [ ] Confirm both lines are present in **upstream `master`** at a named commit, not just in my
  fork, and quote that commit. Check `git log -S "shaders_accurate_mul" -- src/android/app/src/main/jni/config.cpp`
  for a stated rationale — if one exists, this becomes purely the "make it visible" report.
- [ ] Reproduce the divergence end to end: an untouched Android config logging
  `Renderer_ShadersAccurateMul: false` beside an untouched desktop config logging `true`, on
  **official builds from the release page**, not my checkout. This is the whole report and it is
  cheap.
- [ ] Measure what accurate multiplication costs on Adreno before implying anything about which
  default is right. I have not measured it. **If it is expensive, lead with the "make it
  visible" framing** rather than "change the default" — that is the stronger report either way.
- [ ] **Do not add a Pokémon claim back in.** I am playing Pokémon X on Vulkan while this was
  written; whatever I see at the starter-selection scene is a single data point on a
  multi-factor bug with contradictory reports, and it is not evidence about this divergence in
  either direction. If I want to contribute to #1445, that is a separate comment on that thread
  reporting my own configuration and result, not part of this issue.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to read the config path and
correlate the defaults across `settings.h`, the Android reader and the Kotlin settings layer. The
device config was read from my own hardware. I will reproduce the behaviour and take any
measurements myself, and I am writing this report in my own words.*
