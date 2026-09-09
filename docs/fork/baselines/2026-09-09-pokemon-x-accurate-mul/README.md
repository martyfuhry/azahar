# Pokémon X, Froakie, accurate multiplication on the AYN Thor

Reproduction and fix of the symptom reported in azahar-emu/azahar#1445, captured 2026-09-09
on Marty's AYN Thor. Single-variable: only `shaders_accurate_mul` changed between the two.

| | |
|---|---|
| Device | AYN Thor, Android 13, Adreno 740, driver 512.676.53, Vulkan 1.3.128 |
| Build | `thor-v1-rc2` (`31ea3ed33`), package `org.azahar_emu.azahar.thor` |
| Title | `0004000000055D00`, Pokémon X (USA), starter-selection scene |
| Settings held constant | `graphics_api = Vulkan`, `resolution_factor = 4`, `texture_filter = None`, `use_hw_shader = true`, `use_disk_shader_cache = true` |

`before-accurate-mul-off.png` — `shaders_accurate_mul` blank in `config.ini`, which Android
reads as `false` via the hardcoded fallback at `jni/config.cpp:142-143`. Froakie renders
almost entirely white; Chespin and Fennekin beside him are correctly saturated. The
selectivity matters: it is one material, not a global washout.

`after-accurate-mul-on.png` — `shaders_accurate_mul = true`, game restarted, same scene.
Froakie renders correctly blue.

**Caveats, deliberately on the record.** One device, one driver, one game, Vulkan only.
Android + OpenGL is untested here and the two renderers do not share the accurate-mul path,
so upstream #1445's claim that the setting does not help on OpenGL may still hold. A desktop
report in that thread shows the same symptom with the setting already enabled, so the
white-texture symptom probably has more than one cause and this is one of them.

**The before-state log line is gone.** Both device logs have since rotated with the setting
on, so `Renderer_ShadersAccurateMul: false` no longer exists anywhere. The before evidence is
these screenshots plus the `config.ini` capture recorded in `docs/fork/settings-audit.md`.
Re-capturing that line on an official build is cheap now and impossible later.
