# Time-scheduled night mode on the AYN Thor (Android 13) — brightness, media volume, blue-light filter, with no flash on wake

**Scope.** AYN Thor, Android 13, dual internal displays, built-in gamepad. Goal: at a fixed time each evening the screen gets dimmer, the media volume gets quieter, and a blue-light filter comes on — and all three are *already* in effect the instant the screen lights up, not applied a frame later.

**Read-only research.** Nothing below was executed on any device. Every ADB command is written for Marty to run himself. Where I could not verify something without the hardware, it is marked **UNVERIFIED** with the exact test to run.

---

## TL;DR — the recommendation

Do this, in this order, and stop as soon as it is good enough:

1. **Night Light on a custom schedule** (built in, free, zero flash, survives Doze). Covers the blue-light filter completely. `Settings → Display → Night Light → Schedule → Turns on at custom time`.
2. **Turn adaptive brightness OFF.** This is not optional if you want a scheduled brightness to be deterministic — see §0.2. On a handheld you play at fixed distance indoors, you lose nothing.
3. **Extra Dim on a schedule via one automation macro** for the brightness half. Extra Dim (`reduce_bright_colors_activated`) is better than setting the brightness slider on this device, because it is a *composer-level* dim rather than a per-display panel brightness — which matters a lot on a two-screen device (§5.2). Needs a one-time ADB grant.
4. **Media volume on the same macro.** No built-in does this. Do Not Disturb explicitly does **not** touch the media stream (§1.4), so DND is useless for the thing he actually cares about — game audio.
5. **Bedtime Mode is optional and mostly redundant** here, and its "Dark theme" toggle is the one thing in this whole report that can actually disturb a running emulator (§5.3). If you use it, use it for grayscale/DND only, not dark theme.

So: **one built-in (Night Light) + one free automation macro (Extra Dim + media volume) + one ADB command.** MacroDroid free tier is enough — this is 2 macros, the free limit is 5. Tasker is not needed.

The single ADB command you will need:

```
adb shell pm grant com.arlosoft.macrodroid.helper android.permission.WRITE_SECURE_SETTINGS
```

(or, for Tasker: `adb shell pm grant net.dinglisch.android.taskerm android.permission.WRITE_SECURE_SETTINGS`)

---

## 0. The key insight, checked

### 0.1 Why a *scheduled* change does not flash and a *triggered* one always does

This holds, and the mechanism is worth stating because it also tells you where it breaks.

Screen brightness lives in `Settings.System.screen_brightness` (int, 0–255) with a float twin `screen_brightness_float` (0.0–1.0); Android 11+ runs a `BrightnessSynchronizer` in `system_server` that keeps the two in step, which is why the old `settings put system screen_brightness <n>` still works on 13. `DisplayPowerController` holds a `ContentObserver` on that setting.

- **Write it while the display is off:** the settings row changes, the observer fires, `DisplayPowerController` updates its cached target. Nothing is *drawn*, because the panel is unpowered. On the next wake the display is brought up **at the stored value**. There is no sequence in which the old value is ever put on the panel — it stopped being the stored value while the panel was dark. Hence no flash. This is a property of where the value is read, not a timing race you are winning by luck.
- **Write it on a screen-on / user-present / unlock trigger:** by the time your broadcast receiver runs, `PowerManagerService` has already completed the wake and the panel is lit at the *old* brightness. The correction is necessarily visible. You cannot fix this with a faster app; the ordering is structural.

Same argument for Night Light and grayscale: both are colour transforms pushed from `ColorDisplayService` down to SurfaceFlinger/HWC2 `setColorTransform`. Set the matrix while the screen is off and the first composed frame after wake already carries it ([AOSP: Implement night light](https://source.android.com/docs/core/display/night-light), [`ColorDisplayService.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/display/color/ColorDisplayService.java)).

Media volume is not a display thing at all — `AudioManager` stream volume is persisted and applied to the mixer immediately; there is no "flash" concept. Setting it while asleep is trivially fine.

**Conclusion: the premise is correct.** Schedule the change, do not trigger it on wake.

### 0.2 The one thing that *does* override it: adaptive brightness

With `screen_brightness_mode = 1` (adaptive on), `DisplayPowerController` does not use your stored brightness as the target at all — `AutomaticBrightnessController` derives brightness from the ambient light sensor, and a value you write is absorbed as a short-term "user adjustment" that shifts the curve and is then discarded when ambient conditions change or the short-term model expires. Practically: your 21:00 write may simply not survive the wake, and worse, you can get a *visible ramp* a second or two after wake as the sensor settles — which is exactly the effect he is trying to avoid, just delayed. Android Authority's write-up describes the same user-visible symptom from the other direction: "Adaptive brightness is overriding your setting in the background by reading the light sensor and constantly recalculating" ([Android Authority](https://www.androidauthority.com/adaptive-brightness-android-explained-3222961/), [Android Police](https://www.androidpolice.com/reset-adaptive-brightness-android/)).

**So: adaptive brightness OFF is a hard prerequisite for the brightness half of this.** Night Light and Extra Dim are unaffected by it — they are colour/composer transforms, not brightness — which is a second reason to prefer the Extra Dim route.

---

## 1. What the Thor can already do with no extra apps

### 1.1 Night Light — **fully covers the blue-light filter**

`Settings → Display → Night Light` (Pixels label the menu "Display & touch"; an AOSP build like AYN's is normally just "Display"). Options: an **Intensity** slider, and **Schedule** = `None` / `Turns on at custom time` / `Turns on from sunset to sunrise` (the latter needs Location on) ([Google support](https://support.google.com/pixelphone/answer/7169926)). AOSP defaults for the custom window are 22:30–06:30.

The schedule is run by `ColorDisplayService` inside `system_server` using exact alarms — it is not an app, it is not subject to app standby or battery optimisation, and it fires while the device is dozing. This is the single most reliable piece of the whole setup.

- Covers: **blue-light filter — yes, completely.**
- Does not cover: brightness, volume.
- **UNVERIFIED on the Thor:** an OEM AOSP build can ship with `config_nightDisplayAvailable=false` and no Night Light menu at all. Check the menu exists before planning around it; if the menu is missing, `adb shell settings get secure night_display_activated` returning `null` confirms the service is absent, and you fall back to a filter app (Twilight, f.lux) which draws a screen overlay instead — noticeably worse, because an overlay can be suppressed over some fullscreen/secure surfaces and costs a composition layer.

### 1.2 Bedtime Mode (Digital Wellbeing) — **partially useful, one landmine**

`Settings → Digital Wellbeing & parental controls → Bedtime mode`. Bedtime routine = **Use a schedule** (start/end times + days) or **Turn on while charging**. Screen options at bedtime, per Android 13: **Grayscale**, **Keep the screen dark** (kills always-on display), **Dim the wallpaper**, **Dark theme**. Plus a **Do Not Disturb** toggle ([9to5Google how-to](https://9to5google.com/2022/08/24/android-13-bedtime-mode-how-to/), [9to5Google on the A13 redesign](https://9to5google.com/2022/08/05/android-13-bedtime-mode/)).

Read that list carefully: **Bedtime mode does not reduce screen brightness.** "Dim the wallpaper" dims the *wallpaper bitmap* behind your icons — it does nothing at all while you are in a game. "Keep the screen dark" is about AOD. Grayscale is real and is applied at the composer, so it is flash-free like Night Light, but a grey Zelda is probably not what he wants.

- Covers: nothing he asked for, except as an optional grayscale/DND wrapper.
- Landmine: the **Dark theme** toggle flips `uiMode`, which is a configuration change delivered to every running activity — see §5.3.
- **UNVERIFIED on the Thor:** Digital Wellbeing is a GMS package, not AOSP. The Thor's software is described everywhere as Android 13 with an AYN launcher and Play Store access, but no review I found enumerates Digital Wellbeing. One-line check: open `Settings` and search "bedtime". If it is not there, install it from Play (`com.google.android.apps.wellbeing`) or skip it — you lose nothing important.

### 1.3 Adaptive Brightness — **fights you; turn it off**

`Settings → Display → Adaptive Brightness → off`. See §0.2. This is a prerequisite, not a preference.

### 1.4 Do Not Disturb — **does not touch media volume**

Stated plainly, because it is the common misconception and it is the thing he cares about: **DND on stock Android does not mute or reduce the media stream.** Google's own documentation frames DND as limiting *interruptions* — notifications, calls, messages ([Google support](https://support.google.com/android/answer/9069335)). Music, video and game audio play at full media volume with DND on. Samsung's One UI added an explicit opt-in "mute media" toggle precisely *because* stock does not do it ([SamMobile](https://www.sammobile.com/2019/01/09/one-ui-completely-mute-media-volume-do-not-disturb-mode/)) — the Thor is AOSP, so that toggle does not exist here.

- Covers: **nothing** of his three wants. Useful only so notifications don't ping mid-game.

### 1.5 Extra Dim — the built-in that actually does the brightness job

`Settings → Accessibility → Display → Extra dim` (Android 12+). It dims **below the panel's minimum brightness** using a composer-level transform, with its own intensity slider. Backing key is `secure reduce_bright_colors_activated` (0/1), with `secure reduce_bright_colors_level` for intensity ([abilitynet guide](https://mcmw.abilitynet.org.uk/how-to-make-your-display-extra-dim-in-android-13), [Quick-Tile-Settings issue #76](https://github.com/RBN-Apps/Quick-Tile-Settings/issues/76)).

**It has no built-in schedule on Android 13** — only a Quick Settings tile. That is the one gap an automation app fills, and it is a single boolean write. Because it is a composer transform it is (a) immune to adaptive brightness, and (b) the best candidate for covering *both* screens (§5.2).

### Coverage table

| | brightness | media volume | blue-light filter | scheduled? | flash on wake? |
|---|---|---|---|---|---|
| Night Light | no | no | **yes** | **yes, built in** | none |
| Bedtime Mode | no (wallpaper only) | no | no (grayscale ≠ filter) | yes, built in | none |
| Extra Dim | **yes (better than the slider)** | no | no | **no schedule** | none, once scheduled |
| Adaptive Brightness | actively harmful here | no | no | n/a | n/a |
| Do Not Disturb | no | **no** | no | yes | n/a |

Built-ins get him the filter for free and give him the right dimming *mechanism* — they just cannot schedule the dim, and cannot touch volume at all. That is a two-action macro, not a Tasker project.

---

## 2. Does AYN ship anything of its own?

The Thor has an **AYN control centre** on the centre AYN button, with three tabs — **Mode** (dual-screen layout, turn either display off, **per-screen brightness and per-screen volume**), **Task** (launch apps onto the bottom screen), **Settings** (FPS/CPU/GPU/temp overlay, performance profiles Standard/Medium/High, fan Quiet/Smart/Sports). Long-pressing the AYN button is a shortcut to turn the bottom screen off ([RetroHandhelds setup guide](https://retrohandhelds.gg/ayn-thor-setup-guide/), [DroiX review](https://droix.net/blogs/ayn-thor-handheld-review/)).

**No AYN scheduling tool, no time-based profiles, no per-app display/audio profiles are documented anywhere I could find.** DroiX's summary — the software is "functional rather than feature-rich" — matches: it is a dual-screen manager plus a performance overlay, and that is all.

**I could not verify AYN's full OS package list** (no published changelog, the official manual mirror 403s to my tooling). Treat "AYN ships nothing that schedules anything" as *well-supported but not proven*. The one AYN-specific fact that matters and *is* well-attested is the per-screen brightness/volume sliders — see §5.2, they are the reason this device is not just a normal phone for this problem.

---

## 3. Automation apps, if the built-ins are not enough (they aren't, for two of three)

What each one actually needs to do: **(a)** set brightness or Extra Dim, **(b)** set the **media** stream specifically, **(c)** toggle a blue-light filter.

On (c) — this is the part people get wrong. **Night Light has no public API.** There is no `ColorDisplayManager` method a normal app may call. Every app that claims to toggle Night Light does it by writing the hidden secure setting:

```
settings put secure night_display_activated 1
```

which requires `android.permission.WRITE_SECURE_SETTINGS` — a signature|privileged permission that a non-root app can only obtain via a one-time `adb shell pm grant`. There is no accessibility-service trick that does it properly, and no root-free alternative other than a screen-overlay filter app. **But you do not need any of this**, because Night Light's own built-in schedule already does the job better than any app can (§1.1). The only reason to care about `WRITE_SECURE_SETTINGS` is **Extra Dim**, which has no built-in schedule.

| | Tasker | MacroDroid | Automate (LlamaLab) |
|---|---|---|---|
| Cost | paid, ~$3.50 one-off | **free up to 5 macros**; Pro one-off removes limit + ads ([Play listing](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid), [XDA](https://www.xda-developers.com/automate-your-device-for-free-with-macroadroid/)) | free up to 30 blocks/flow; Premium one-off |
| Setup cost | high — most powerful, least friendly | **low** — trigger/action/constraint lists, this task is ~5 taps | medium — visual flowchart, fiddly for a 2-action job |
| Set brightness (`WRITE_SETTINGS`) | yes (Display Brightness) | yes (Brightness action) | yes ([Screen brightness set](https://llamalab.com/automate/doc/block/screen_brightness_set.html), has an explicit `Automatic` argument so it can also flip adaptive mode) |
| …with adaptive on | unreliable — see §0.2, all three are | same | same; LlamaLab additionally document the block "may not set a correct brightness level due to an Android bug" on 15 and "possibly lower on some devices", workaround = use *System setting set* instead |
| Set **media** stream volume | yes, per-stream | yes, per-stream | yes — streams include Ring/**Media**/Music/Voice call/Accessibility ([Audio volume set](https://llamalab.com/automate/doc/block/audio_volume_set.html)) |
| Toggle Night Light / Extra Dim | yes, via secure-settings write (needs the grant); historically via a Java Function, and via plugins like AutoTools Secure Settings ([Tasker help](https://tasker.joaoapps.com/userguide/en/help/ah_secure_setting_grant.html), [XDA](https://www.xda-developers.com/tasker-beta-secure-settings-permission-lock-screen-screenshot-action-android-p/)) | yes, via its helper app + the grant ([MacroDroid forums thread](https://www.tapatalk.com/groups/macrodroid/granting-write_secure_settings-permission-via-adb-t2923.html), [walkthrough](https://dothanhlong.org/android-granting-write_secure_settings-permission-via-adb/)) | yes — [System setting set](https://llamalab.com/automate/doc/block/system_setting_set.html) writes System/Secure/Global given the "modify secure settings" privilege |
| One-time ADB grant needed | `adb shell pm grant net.dinglisch.android.taskerm android.permission.WRITE_SECURE_SETTINGS` | `adb shell pm grant com.arlosoft.macrodroid.helper android.permission.WRITE_SECURE_SETTINGS` (note: the **helper** package, not `com.arlosoft.macrodroid`) | `adb shell pm grant com.llamalab.automate android.permission.WRITE_SECURE_SETTINGS` |

**Pick MacroDroid.** It is free at this size, the two actions he needs are first-class list items rather than script, and one macro plus its inverse is 2 of the 5 free macros. Tasker's extra power buys nothing for "at 21:00, write two settings." Automate is the nicest *model* of the three but is the worst fit for a two-step job, and its own docs carry a media-volume caveat that lands squarely on this device (§5.5).

⚠️ `WRITE_SECURE_SETTINGS` is a broad permission — it lets the grantee write essentially any system-wide secure/global setting. Granting it to a well-known automation app is standard practice in this community, but it is not nothing; it is why Android makes you go through ADB. There is no way to scope it to one key.

---

## 4. The recommended setup, step by step

### Step 1 — Night Light on a schedule (2 minutes, no PC)

1. `Settings → Display → Night Light`
2. **Intensity**: about **60–70%** — *a starting point*; you want obviously amber, not sepia-photo.
3. **Schedule → Turns on at custom time**
4. Start **21:00**, end **07:00** (or whenever he actually stops).
5. Leave the main toggle alone — the schedule drives it.

Prefer custom times over sunset-to-sunrise: sunset drifts across the year and in June would fire around 21:30, in December around 16:30. He wants "when I go upstairs", which is a clock time.

### Step 2 — kill adaptive brightness (30 seconds)

`Settings → Display → Adaptive Brightness → off`. Then set the daytime brightness where he likes it — that becomes the value the morning macro restores.

### Step 3 — the ADB grant (one time, needs a PC)

On the Thor: `Settings → About phone → tap Build number 7×`, then `Settings → System → Developer options → USB debugging` on. Plug in, accept the RSA prompt.

On the PC:

```
adb devices
adb shell pm grant com.arlosoft.macrodroid.helper android.permission.WRITE_SECURE_SETTINGS
```

Install MacroDroid **and** its helper app first (MacroDroid prompts for the helper the first time a macro needs it) — `pm grant` fails with "package not found" if the helper is not installed yet.

Sanity-check the grant and learn the current values while you are there:

```
adb shell settings get secure reduce_bright_colors_activated
adb shell settings get secure reduce_bright_colors_level
adb shell settings get secure night_display_activated
adb shell settings get system screen_brightness
adb shell settings get system screen_brightness_mode      # must be 0 after Step 2
```

Then unplug and turn USB debugging back off — the grant is persistent across reboots (it survives until the app is uninstalled).

### Step 4 — the night macro (MacroDroid)

**Macro: "Night — 21:00"**

- **Trigger:** `Day/Time Trigger` → 21:00, every day.
- **Action 1:** `Volume → Set Volume` → **Media** → about **25–30%** *(a starting point; on a 15-step media stream that is step 4)*. Uncheck any "show volume UI" option so it does not pop a slider.
- **Action 2:** `Applications → System Setting` (or `Secure Setting`) → category **Secure**, name `reduce_bright_colors_activated`, value `1`.
  Optionally add name `reduce_bright_colors_level` value `50` first, to set the dim depth.
  *If you would rather move the actual panel brightness instead of using Extra Dim* (see §5.2 before you decide): `Screen → Brightness` → **20%**, with auto-brightness off.
- Leave Night Light out of the macro — its own schedule already handles it, and doing it twice just gives you two things to debug.

**Macro: "Day — 07:00"** — the exact inverse: media volume back to his daytime level, `reduce_bright_colors_activated` → `0`.

Then, in MacroDroid's settings: **exclude MacroDroid from battery optimisation** (`Settings → Apps → MacroDroid → Battery → Unrestricted`) and, on Android 12+, allow **Alarms & reminders** for it (`Settings → Apps → Special app access → Alarms & reminders`). Without the latter, its exact alarms are downgraded and §5.6 bites.

### Step 5 — verify there is no flash

The honest test, in order:

1. **Prove the schedule fired while asleep.** Temporarily set the night macro to fire 3 minutes out. Put the Thor to sleep *now* (power button, or close the lid). Wait 5 minutes without touching it. Wake it.
   - **Pass:** the very first frame is already dim/amber. No transition.
   - **Fail:** it comes up bright and corrects a moment later → the trigger fired on wake, not on schedule, or adaptive brightness re-derived the value (recheck `screen_brightness_mode` is `0`).
2. **See the failure mode for contrast**, so you know what "pass" is worth: build a throwaway macro with a `Screen On` trigger and the same brightness action. Wake the device. You will see the flash plainly. Delete it.
3. **Slow-motion video** if the eye is not sure: record the wake at 240fps with another phone. A scheduled change shows zero bright frames; a triggered one shows 5–15 of them.
4. **After a long sleep** — the case that actually matters. Leave it asleep overnight, or at least 2–3 hours across the 21:00 boundary, then wake it. This is the Doze test (§5.6); a short 5-minute sleep does not exercise it.
5. `adb shell settings get secure reduce_bright_colors_activated` right after waking, to confirm the value is what you think and the dim you are seeing is the one you set.

---

## 5. Gotchas

### 5.1 Adaptive brightness

Covered in §0.2. It is the number one reason a scheduled brightness "randomly doesn't work". Off, always, for this setup. If he ever turns it back on, expect the 21:00 brightness write to survive sometimes and not others, which is worse than it never working.

### 5.2 Two screens — the real one

The Thor has **two internal displays with separate brightness**, exposed as separate sliders in the AYN control centre ([RetroHandhelds](https://retrohandhelds.gg/ayn-thor-setup-guide/)). That means:

- `Settings.System.screen_brightness` is the **default display's** brightness. Android has had per-display brightness (`DisplayManager.setBrightness(displayId, …)`) since 11, but the *settings key* is singular and maps to the default display. **Expect a MacroDroid brightness action to dim only the top screen and leave the bottom one blazing** — which on a DS-layout emulator is arguably the worse half to leave bright. **UNVERIFIED**; test it directly: set brightness to 10% from a macro and look at both panels.
- This is the main argument for **Extra Dim over the brightness slider**: Extra Dim is a colour transform at the composer, not a panel backlight change, so it has a real chance of covering both surfaces. **Also UNVERIFIED** — it depends on whether AYN's HWC applies the `setColorTransform` matrix to both displays or only the primary. Same test: toggle the Extra Dim QS tile and watch both panels.
- **Night Light: same question, same answer — test it.** AOSP's night-light documentation describes the matrix going to HWC2 `setColorTransform` and says nothing about multi-display behaviour ([AOSP](https://source.android.com/docs/core/display/night-light)); it is an OEM integration detail. Toggle Night Light manually and look at the bottom screen.
- If it turns out the bottom screen ignores everything, the pragmatic fallback is AYN's own long-press-AYN-button shortcut to **turn the bottom screen off** at night, or dim it once by hand in the AYN menu and leave it there — its slider is not something a third-party app can reach.

### 5.3 Interference with a running emulator

Mostly none, with one exception:

- **Night Light, grayscale, Extra Dim: harmless.** All three are composer-level transforms. The app never learns they happened, no configuration change, no surface recreation, no `onPause`. An emulator running under a foreground service is completely unaffected.
- **Brightness and volume writes: harmless.** No lifecycle involvement.
- **Bedtime Mode's "Dark theme": not harmless.** Flipping dark theme changes `uiMode`, which is a `Configuration` change delivered to every running activity. An activity that does *not* declare `uiMode` in `android:configChanges` is **destroyed and recreated** — mid-game, at 21:00. Azahar itself is safe: `src/android/app/src/main/AndroidManifest.xml:84` declares `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density"`. Other emulators may not be — melonDS-android's `EmulatorActivity` declares a `configChanges` list whose full contents I have not checked, and the Thor already has a documented history of losing emulation state around sleep. **Recommendation: do not enable Bedtime Mode's Dark theme toggle.** Set dark theme once, permanently, and be done.
- **Lid behaviour:** closing the lid is a sleep/wake path, not a schedule path. Nothing in this setup hooks it. Note this cuts the other way too — the *reason* the scheduled approach wins is that it is entirely decoupled from wake events, lid or button.
- **Foreground service:** Azahar's emulation runs under `ForegroundService` with `foregroundServiceType="specialUse"` (`AndroidManifest.xml:103`). A foreground service keeps the *app* out of Doze restrictions; it does not make the *device* stay awake, and it has no interaction with any of the settings written here.

### 5.4 Doze can delay a third-party trigger (but cannot cause a flash)

Doze defers app alarms. Whitelisting from battery optimisation helps but does not fully exempt an app; in Doze, deferred work is typically released in maintenance windows and an app can end up ~15 minutes late ([MacroDroid forum discussion](https://www.tapatalk.com/groups/macrodroid/how-to-exclude-macrodroid-from-doze-in-marshmallow-t1564.html), [date/time triggers not firing](https://www.tapatalk.com/groups/macrodroid/date-time-triggers-not-firing-ever-t3182.html)). Android 12+ additionally gates exact alarms behind `SCHEDULE_EXACT_ALARM` / *Alarms & reminders* special access.

Two consolations:

- **A late trigger cannot produce a flash.** The worst case is that at 21:04 he wakes the device and gets daytime brightness — wrong, but steady. The flash failure mode is exclusively a property of wake-triggered rules.
- **Night Light is immune**, because its schedule runs in `system_server`, not in an app. That is a good reason to leave the filter to the built-in even though MacroDroid *could* do it.

Mitigation for the macro half: battery-optimisation exemption **plus** Alarms & reminders access (Step 4). If it still drifts, move the trigger earlier (20:45) so the window has closed well before he goes upstairs.

### 5.5 Media volume on a Chinese-brand Android 12+ device

LlamaLab explicitly document that "setting the *Media* and *Music* volume may be unreliable on Chinese brand devices, e.g. OnePlus and Realme, running Android 12+", and suggest the *Accessibility* stream as a workaround ([Audio volume set](https://llamalab.com/automate/doc/block/audio_volume_set.html)). AYN is a Chinese OEM on Android 12+. This is a plausible failure mode on the Thor regardless of which automation app is used — and the Thor's **per-screen volume** sliders in the AYN menu suggest the audio path is not stock either.

**Test it first**, before building the rest: a one-action macro that sets media volume to 20%, run manually with a game running. If it does not take, try `STREAM_ACCESSIBILITY`, or fall back to setting the volume by hand (the volume rocker is right there — this is the one of the three that costs him a single button press, and unlike brightness there is no flash cost to doing it after wake).

### 5.6 Smaller things

- MacroDroid's ADB grant targets `com.arlosoft.macrodroid.helper`, **not** `com.arlosoft.macrodroid`. Getting this wrong is the most common failure in the forum threads ([XDA](https://xdaforums.com/t/cant-grant-macrodroid-helper-v1-8-write_secure_settings-permissions.4591077/)).
- If the Thor's build has no Night Light menu, `settings get secure night_display_activated` returns `null` and no amount of ADB writing will help — the service is not there. Overlay apps (Twilight, f.lux) are the fallback and are strictly worse.
- A factory reset or MacroDroid reinstall drops the ADB grant. Keep the command written down.
- Sunset-to-sunrise needs Location on, which is a background battery cost on a handheld. Custom times avoid it.

---

## Sources

- [AOSP — Implement night light](https://source.android.com/docs/core/display/night-light) · [`ColorDisplayService.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/display/color/ColorDisplayService.java)
- [Google — Change your screen color at night (Night Light)](https://support.google.com/pixelphone/answer/7169926) · [Google — Limit interruptions with Modes & Do Not Disturb](https://support.google.com/android/answer/9069335)
- [9to5Google — How to enable and configure Android 13's Bedtime mode](https://9to5google.com/2022/08/24/android-13-bedtime-mode-how-to/) · [9to5Google — Bedtime mode gets Android 13 redesign](https://9to5google.com/2022/08/05/android-13-bedtime-mode/)
- [AbilityNet — How to make your display extra dim in Android 13](https://mcmw.abilitynet.org.uk/how-to-make-your-display-extra-dim-in-android-13) · [`reduce_bright_colors_activated` (Quick-Tile-Settings #76)](https://github.com/RBN-Apps/Quick-Tile-Settings/issues/76)
- [Android Authority — Adaptive Brightness explained](https://www.androidauthority.com/adaptive-brightness-android-explained-3222961/) · [Android Police — Reset adaptive brightness](https://www.androidpolice.com/reset-adaptive-brightness-android/)
- [RetroHandhelds — AYN Thor setup guide](https://retrohandhelds.gg/ayn-thor-setup-guide/) · [DroiX — AYN Thor review](https://droix.net/blogs/ayn-thor-handheld-review/) · [Held Games — AYN Thor review](https://heldgames.com/reviews/ayn-thor)
- [Tasker — Write Secure Settings permission](https://tasker.joaoapps.com/userguide/en/help/ah_secure_setting_grant.html) · [XDA — Tasker beta adds Secure Settings permission](https://www.xda-developers.com/tasker-beta-secure-settings-permission-lock-screen-screenshot-action-android-p/)
- [MacroDroid on Play](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid) · [MacroDroid wiki overview](https://wiki.macrodroid.com/wiki/index.php/Overview) · [XDA — Automate your device for free with MacroDroid](https://www.xda-developers.com/automate-your-device-for-free-with-macroadroid/) · [Granting WRITE_SECURE_SETTINGS via ADB (forums)](https://www.tapatalk.com/groups/macrodroid/granting-write_secure_settings-permission-via-adb-t2923.html) · [walkthrough](https://dothanhlong.org/android-granting-write_secure_settings-permission-via-adb/) · [helper-package pitfall](https://xdaforums.com/t/cant-grant-macrodroid-helper-v1-8-write_secure_settings-permissions.4591077/)
- Automate (LlamaLab): [Screen brightness set](https://llamalab.com/automate/doc/block/screen_brightness_set.html) · [Audio volume set](https://llamalab.com/automate/doc/block/audio_volume_set.html) · [System setting set](https://llamalab.com/automate/doc/block/system_setting_set.html)
- [SamMobile — One UI can mute media volume in DND](https://www.sammobile.com/2019/01/09/one-ui-completely-mute-media-volume-do-not-disturb-mode/) (i.e. stock does not)
- Doze/trigger reliability: [MacroDroid forums — excluding from Doze](https://www.tapatalk.com/groups/macrodroid/how-to-exclude-macrodroid-from-doze-in-marshmallow-t1564.html) · [date/time triggers not firing](https://www.tapatalk.com/groups/macrodroid/date-time-triggers-not-firing-ever-t3182.html)
- This repo: `src/android/app/src/main/AndroidManifest.xml:84` (`configChanges` incl. `uiMode`), `:103` (`ForegroundService`, `specialUse`)
