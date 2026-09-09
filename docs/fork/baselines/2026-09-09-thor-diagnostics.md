# AYN Thor: crash-record diagnostics

Read-only `adb` session on the Thor (`7f87e972`), 2026-09-09 ~12:40–12:50 EDT, with Marty's
consent. Nothing installed, launched, stopped, or configured. Companion to
`2026-09-09-thor-device-snapshot.md`, which this note **corrects in two places**.

---

## 1. The melonDS crash stack: not recovered, and #1624 does not survive the evidence anyway

### Verdict on upstream #1624

**Not confirmed — and the available evidence argues against it.** The fix in `cc6c3ba3` is not
what Marty is hitting. A newer melonDS build is *not* established as the answer.

The reasoning does not depend on the stack we failed to recover:

`cc6c3ba3` ("Fix crash on dual-screen devices when returning from sleep") is a **one-line Kotlin
change** in `ExternalPresentation.kt`:

```kotlin
-            window?.systemGestureExclusionRects = touchScreenArea.orEmpty()
+            layoutView.systemGestureExclusionRects = touchScreenArea.orEmpty()
```

A failure on that line is a **Java/Kotlin exception**. Android records those as
`reason=4 (APP CRASH(EXCEPTION))`, DropBox tag `data_app_crash`. Every single melonDS loss on
this device is `reason=5 (APP CRASH(NATIVE))` — a fatal POSIX signal in native code, DropBox tag
`data_app_native_crash`. These are different `ApplicationExitInfo` reasons and the framework does
not conflate them; this very device demonstrates the distinction hours apart:

| Time | Process | Reason |
|---|---|---|
| 2026-09-08 21:08:11 | `org.azahar_emu.azahar.debug` | `reason=4 (APP CRASH(EXCEPTION))` |
| 2026-09-08 21:11:37 | `org.azahar_emu.azahar.thor` | `reason=5 (APP CRASH(NATIVE))` status 11 |

So melonDS is not dying from an uncaught Kotlin exception in the Presentation path.

**A second, independent argument.** `exit-info` holds **eight** melonDS native crashes, not the
two the snapshot recorded, and the fatal signals are **not all the same**: six SIGSEGV, one
SIGTRAP, one SIGABRT. One null dereference at one fixed source line does not produce three
different fatal signals. Mixed signals across a single workload is the signature of memory
corruption or a use-after-free — something a one-line null-guard cannot address.

### Stated plainly: what this does *not* settle

This rules out **the specific defect `cc6c3ba3` fixed**, not the **area**. The Thor's second panel
does carry `FLAG_PRESENTATION` (section 6), so melonDS's `ExternalPresentation` path is live on
this hardware. A native SIGSEGV during an EGL/GL surface teardown on the secondary display, on a
sleep or resume transition, is entirely plausible and would be a *different and still-unfixed*
bug in the same neighbourhood. **We have no stack, so we cannot place the fault.** Do not tell
Marty "just take a nightly" — that claim is not supported. Equally, do not claim the display path
is exonerated.

### Why the stack could not be recovered

Exhausted, in order:

- **`dumpsys dropbox`** — readable, 289 entries, but the oldest is **2026-09-06 12:43:12** against
  a dump taken 2026-09-09 12:42. That is the stock ~3-day retention (`dropbox_age_seconds` is
  `null`, i.e. AOSP default). The melonDS crashes are 2026-08-29 — **eleven days old**.
- Swept **every tag** (`--print` with no filter) plus `data_app_native_crash`, `data_app_crash`,
  `SYSTEM_TOMBSTONE`: **zero** records for any `me.magnum.*` process. The only `melon` hits in the
  corpus are `base.apk` mappings inside *other* processes' memory maps.
- **`/data/system/dropbox`** and **`/data/tombstones`** — `Permission denied` as `shell`.
- **`adb bugreport`** — captured (12.9 MB). Its tombstone ring holds `tombstone_00`–`tombstone_31`,
  **every one of them stamped 2026-09-09 09:02–09:12**, and all but one are `com.nendo.argosy`.
  Nothing from August survives anywhere on the device.

**The proximate cause of the evidence loss is his launcher.** `com.nendo.argosy` 2.15.0 crashed
**30 times in three days** (`abort` → `art::Runtime::Abort` → `AssertNoPendingException` →
`art::JNI::FindClass` — a JNI class lookup with a pending exception). That flood churned both the
DropBox ring and the 32-slot tombstone ring past everything else. He updated Argosy to 2.15.1 on
2026-09-09 09:13:42, minutes after the last burst, so the churn should now stop.

### The corrected melonDS record (supersedes the snapshot's table)

`me.magnum.melonds` 2.0.1 GH, installed 2026-08-20 22:27:58. All sixteen `exit-info` slots:

| When | Reason | Signal | Importance | PSS / RSS |
|---|---|---|---|---|
| 2026-08-24 07:56:17 | APP CRASH (NATIVE) | **6 SIGABRT** | 100 fg | 436 / 556 MB |
| 2026-08-25 07:50:03 | APP CRASH (NATIVE) | **5 SIGTRAP** | 100 fg | 407 / 541 MB |
| 2026-08-25 14:24:21 | APP CRASH (NATIVE) | 11 SIGSEGV | 100 fg | 368 / 500 MB |
| 2026-08-25 14:33:02 | APP CRASH (NATIVE) | 11 SIGSEGV | 100 fg | 466 / 580 MB |
| 2026-08-26 22:02:04 | DEPENDENCY DIED | — | 400 | 164 / 191 MB |
| 2026-08-28 14:42:32 | APP CRASH (NATIVE) | 11 SIGSEGV | 100 fg | 396 / 523 MB |
| 2026-08-28 14:45:54 | APP CRASH (NATIVE) | 11 SIGSEGV | 100 fg | 320 / 172 MB |
| 2026-08-29 17:09:45 | SIGNALED | 9 SIGKILL | 230 | 380 / 505 MB |
| 2026-08-29 20:22:06 | APP CRASH (NATIVE) | 11 SIGSEGV | 100 fg | 317 / 438 MB |
| 2026-08-29 20:24:37 | APP CRASH (NATIVE) | 11 SIGSEGV | 100 fg | 424 / 547 MB |
| 2026-09-02 07:29:38 | SIGNALED | 9 SIGKILL | 400 | 364 / 276 MB |
| 2026-09-02 07:29:59 / 07:47:31 | SIGNALED ×2 | 9 SIGKILL | 230 | 0 |
| 2026-09-07 09:18:42 / 09:19:07 / 09:34:15 | SIGNALED ×3 | 9 SIGKILL | 230 | 0 |

Reading it:

- **Eight foreground native crashes, not two.** The snapshot saw only the newest pair.
- **Three distinct fatal signals** — the argument above.
- **Crash-relaunch-crash pairs**: 14:24/14:33, 14:42/14:45, 20:22/20:24. He restarts and it dies
  again within minutes.
- **No `reason=3 LOW_MEMORY` record, and no excessive-CPU record.** The snapshot was right about
  this, and it still contradicts the maintainer's memory explanation.
- **Nothing since 2026-08-29.** The `SIGNALED`/`rss=0`/`state=empty` entries on 09-02 and 09-07 are
  ordinary reclaim of already-dead processes, not lost sessions.
- **Rate**: 8 native crashes across a lifetime total of **2 h 36 m** of foreground use over 59
  launches. Roughly three crashes per hour of play; 2.6 minutes per launch on average.

---

## 2. Azahar: stack recovered, texture-filter race confirmed on the Thor

The 2026-09-08 21:11:37 crash **is** in DropBox, and it is the documented race. Package
`org.azahar_emu.azahar.thor` v33730851 (`thor-v1-rc1-1-thor`), foreground, process uptime 228 s.

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000000000b4
Cause: null pointer dereference
pid: 24604, tid: 25311, name: VulkanWorker  >>> org.azahar_emu.azahar.thor <<<

#00 pc 0000000000228df0  /vendor/lib64/hw/vulkan.adreno.so (!!!0000!c6d71dcf...+48)
#01 pc 000000000022f980  /vendor/lib64/hw/vulkan.adreno.so (!!!0000!4d72fd7f...+784)
#02 pc 000000000022cb3c  /vendor/lib64/hw/vulkan.adreno.so
                          (qglinternal::vkCmdEndRenderPass(VkCommandBuffer_T*)+60)
#03 pc 00000000021d92e4  libcitra-android.so   [unsymbolised]
#04 pc 00000000021a00ec  libcitra-android.so
                          (Vulkan::Scheduler::WorkerThread(std::stop_token)+748)
#05 pc 00000000021a061c  libcitra-android.so
#06 pc 00000000000ba650  libc.so (__pthread_start(void*)+208)
#07 pc 0000000000053ffc  libc.so (__start_thread+68)
```

This is an **exact match** for signature #1 in `docs/fork/research/texture-filter-crash.md` —
same thread name `VulkanWorker`, same `vkCmdEndRenderPass` entry point, same
`Scheduler::WorkerThread` caller, and the **same fault address `0xb4`**. That note's stack has
`Vulkan::RenderManager::EndRendering()::$_0` at frame #03, exactly where this one is
unsymbolised; the shape is identical, so frame #03 is almost certainly that lambda — inferred
from the match, not proven from symbols.

**Symbolisation of #03 was not possible.** The crashed binary's BuildId is
`78302c445299e3f216835204423323453698c7cc`; no `libcitra-android.so` in the working tree or any
worktree carries it (they have all been rebuilt since rc1). Per instruction, nothing was rebuilt.

**This is the first confirmation on the Thor's own driver.** The research note was measured
entirely on the Fold5's Adreno driver 512.676.1; the Thor runs **512.676.53**. The newer driver
does not fix it.

### What the session was doing

The 21:11:37 session's own log is gone — `azahar_log.old.txt` is the **relaunch** that started
~21:18:45 (mtime 22:30, last entry at +4285 s), not the crash session. It is still informative,
because it reads back the config that was persisted at crash time:

```
Renderer_GraphicsAPI: Vulkan       Renderer_UseResolutionFactor: 4
Renderer_TextureFilter: xBRZ       Renderer_UseHwShader: false
Renderer_AsyncShaders: false       Renderer_AsyncPresentation: true
```

**A texture filter (xBRZ) at `resolution_factor = 4`** — precisely the documented trigger, and the
same ingredients as the third variant in the research note (res 4 + Bicubic). The timeline fits
tightly: rc1 first installed 21:07:47, launched immediately, dead 228 s later at 21:11:37.

`config.ini` today has `texture_filter = 0`, so he reverted to no filter afterwards.

The relaunch log also shows the fork's own work exercising correctly — `Secondary Surface
Destroyed` / `Secondary Surface changed to 1240x1080`, `Surface cleared while emulation paused`,
`Autosave written`, and `Advanced system clock by 5 s to resync with host` (the clock-resync
branch).

---

## 3. Complete `exit-info` for every emulator package

`me.magnum.melonds` — see the sixteen-row table in section 1.

**`me.magnum.melondualds` (WatermelonDS 0.7.0.rc5) — the section is empty. Zero exit records.**
The process has never been killed by the system, because it has barely ever run (section 4).

**`org.azahar_emu.azahar.thor`** — one record:

```
#0  2026-09-08 21:11:37.352  pid=24604  reason=5 (APP CRASH(NATIVE)) status=11
    importance=100  pss=897MB rss=1.0GB  description=crash  state=empty
```

Note the footprint: **897 MB PSS / 1.0 GB RSS**, roughly double any melonDS session.

**`org.azahar_emu.azahar.debug`** — one record:

```
#0  2026-09-08 21:08:11.168  pid=21124  reason=4 (APP CRASH(EXCEPTION)) status=0
    importance=400  pss=824MB rss=0.93GB  description=crash  state=empty
```

**`com.retroarch.aarch64`** — eight records; the interesting ones are three ANRs, all input
dispatch:

```
#0  2026-09-09 08:39:28  reason=1  (EXIT_SELF)        pss=173MB rss=287MB
#1  2026-09-09 08:37:56  reason=6  (ANR)  description=user request after error: Input dispatching
      timed out (Application does not have a focused window)
#2  2026-09-09 08:36:48  reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
      description=stop com.retroarch.aarch64 due to from pid 3043
#3  2026-09-09 08:36:34  reason=1  (EXIT_SELF)        pss=128MB rss=239MB
#4  2026-09-09 08:23:50  reason=6  (ANR)  description=user request after error: Input dispatching
      timed out (df66be6 .../RetroActivityFuture (server) is not responding. Waited 5001ms for KeyEvent)
#5  2026-09-08 22:57:45  reason=6  (ANR)  description=user request after error: Input dispatching
      timed out (e51c31 .../RetroActivityFuture (server) is not responding. Waited 5001ms for KeyEvent)
#6  2026-09-08 22:57:29  reason=1  (EXIT_SELF)        pss=0 rss=0
#7  2026-09-08 22:56:30  reason=1  (EXIT_SELF)        pss=252MB rss=377MB
```

RetroArch has a recurring **input-dispatch ANR** (7 `data_app_anr` records in DropBox, 22:39
through 09:38). Unrelated to our fork, but it is a real and repeated problem on this device.

---

## 4. Which melonDS he actually plays

From `dumpsys usagestats` (yearly bucket; no writes, `appLaunchCount` is exposed directly):

| Package | Version | Installed | Launches | Total foreground | Last used |
|---|---|---|---|---|---|
| `me.magnum.melonds` | 2.0.1 GH | 2026-08-20 22:27:58 | **59** | **2:36:05** | 2026-09-08 22:34:54 |
| `me.magnum.melondualds` | 0.7.0.rc5 | 2026-08-16 20:00:12 | **4** | **0:01:02** | **2026-08-20 22:26:11** |

**He plays stock melonDS. WatermelonDS has effectively never run** — four launches totalling
sixty-two seconds, abandoned on 2026-08-20.

The install timeline is the striking part: he last touched WatermelonDS at **22:26:11** on
2026-08-20 and installed stock melonDS 2.0.1 at **22:27:58** — **107 seconds later**. He tried the
Vulkan fork, bounced off it inside two minutes, and went to stock instead. Android's own launch
predictor agrees it is dead weight: `nextEstimatedLaunchTime=+352d` for `melondualds` versus
`+6d9h` for `melonds`.

Also worth noting: melonDS's last use on 2026-09-08 22:34:54 lasted **nine seconds** — a glance,
not a session. His actual play has moved to Azahar (`azahar.thor`: 34 launches, 45 m in the
monthly bucket, last used **2026-09-09 12:35:55**, i.e. minutes before this dump, with 3 h 35 m of
foreground-service time recorded).

---

## 5. Device health

| Item | Value |
|---|---|
| Storage | **206 GB free of 227 GB** (`/data`, 9 % used). Not a factor. |
| RAM | `MemTotal` **11 535 400 kB (~11.0 GiB)**; `MemAvailable` **4 397 344 kB (~4.2 GiB)** |
| Swap | `SwapTotal` 4 194 300 kB, `SwapFree` 2 187 032 kB — **~2 GB of swap already in use** |
| Thermal | `Thermal Status: 0` (NONE). **`HAL Ready: false`, `Cached temperatures:` empty** — the thermal HAL is not reporting, so *no throttling history is available at all*. Absence of throttling records here is not evidence of absence. |
| Battery | level 18 %, status 2 (charging), temperature 30.0 °C |
| CPU | 3 clusters, governor **`walt`** on all: `policy0` cpu0-2 max 2 016 MHz · `policy3` cpu3-6 max 2 707 MHz (running 2 803 MHz) · `policy7` cpu7 max 2 956 MHz (running 3 187 MHz). `scaling_min_freq`/`max_freq` are not readable as `shell`. |
| GPU | Adreno 740 v2, devfreq governor **`msm-adreno-tz`**, max 680 MHz |
| Cached-app freezer | **enabled** (`use_freezer=true`, `freeze_debounce_timeout=600000`) |
| `background_process_limit` | `null` — platform default, no manual restriction |
| App standby | **globally disabled** (`mAppIdleEnabled=false`). All 157 packages sit in `bucket=5` (EXEMPTED, `reason=d`); only 7 are at `bucket=10`. No emulator is being standby-restricted. |
| Doze allowlist | **`com.nendo.argosy` is the only user-allowlisted app.** No emulator is exempt from Doze — including Azahar. |

Nothing here explains the melonDS crashes. Memory is comfortable, storage is comfortable, no
throttling is visible (though the HAL is silent), and no power-management policy is restricting
the emulators.

---

## 6. The second display

Two panels, and the notable fact is that **both are internal**:

| | Display 0 | Display 4 |
|---|---|---|
| Name | `Built-in Screen` | `Screen-2` |
| `uniqueId` | `local:4630946441858561667` | `local:4630946482288158084` |
| Physical port | 131 | 132 |
| Native resolution | 1080 × 1920 (landscape 1920 × 1080) | 1080 × 1240 (landscape 1240 × 1080) |
| Modes | 60.000004 / 120.00001 Hz | 120.00001 / 60.000004 Hz |
| Active mode | id 1 = **60 Hz** (default mode id 2 = 120 Hz) | id 4 = **60 Hz** (default mode id 3 = 120 Hz) |
| Density | 369 (318.976 × 320.842 dpi) | 369 (318.976 × 321.387 dpi) |
| HDR | types [2,3], max lum 420.0 | types [2,3], max lum 500.0 |
| `type` | `INTERNAL` | **`INTERNAL`** |
| `isInternal` | true | **true** |
| layerStack | 0 | 4 |
| Flags | `ALLOWED_TO_BE_DEFAULT_DISPLAY`, `ROTATES_WITH_CONTENT`, `SECURE`, `SUPPORTS_PROTECTED_BUFFERS` | same **plus `FLAG_PRESENTATION`** |
| State at dump | `OFF` | `OFF` |

Two things matter for us:

1. **`Screen-2` carries `FLAG_PRESENTATION`**, so it is a legitimate target for
   `android.app.Presentation` — melonDS's `ExternalPresentation` really does run on this panel.
   That is why the #1624 *area* cannot be dismissed even though the specific fix is ruled out.

2. **The platform contradicts itself about the panel's type.** `DisplayManager`'s
   `DisplayDeviceInfo` and `StaticDisplayInfo` both say `type INTERNAL` / `isInternal=true`, but
   the *input* subsystem's `DisplayViewport` for the same display reports `type=EXTERNAL`:

   ```
   DisplayViewport{type=EXTERNAL, displayId=4, uniqueId='local:4630946482288158084',
                   physicalPort=132, logicalFrame=Rect(0, 0 - 1240, 1080)}
   ```

   Code keying off `Display.getType()` sees `TYPE_INTERNAL`; code keying off the input viewport
   sees external. Any Thor display-detection logic needs to know which one it is asking.

Both panels default to 120 Hz but were running at 60 Hz at dump time.

---

## 7. Azahar's config as actually deployed

`/sdcard/azahar/config/config.ini`. **No sign of hand-editing** — every non-empty value sits in
the section the app's writer uses, and there are no stray keys. Non-default values:

**`[Renderer]`**

| Key | Value | Note |
|---|---|---|
| `graphics_api` | `2` | Vulkan |
| `resolution_factor` | **`4`** | 4× — high, and half of the texture-filter trigger |
| `texture_filter` | `0` | NoFilter — **reverted since the crash**, which ran xBRZ |
| `use_hw_shader` | **`false`** | **Software shaders.** Confirmed in the log as `Renderer_UseHwShader: false`. The template documents `1 (default): Hardware`. This is a surprising and probably unintended setting at 4× resolution. |
| `async_shader_compilation` | `true` | (the rc1 log showed `false`, so this changed since) |
| `use_disk_shader_cache` | `true` | |
| `use_vsync` | `false` | |
| `use_frame_limit` / `frame_limit` | `true` / `100` | |
| `filter_mode` | `true` | linear |
| `use_integer_scaling` | `false` | |
| `texture_sampling` | `0` | game controlled |

**`[Layout]`**

| Key | Value | Note |
|---|---|---|
| `layout_option` | `1` | **Single Screen Only** (the template says Large Screen is the Android default) |
| `screen_orientation` | `2` | automatic |
| `enable_secondary_display` | `true` | |
| `secondary_display_layout` | `4` | = `OppositeScreenOnly`. **Valid**, but the in-file comment only documents 0–3 — the template text is stale against `SecondaryDisplayLayout` in `src/common/settings.h`, which has eight values. Worth fixing so the file stops looking corrupt. |
| `swap_screen` | `false` | |

**`[Core]`** `cpu_clock_percentage = 100`. **`[Audio]`** `enable_audio_stretching = true`.
**`[System]`** `autosave_mode = 2`. **`[Debugging]`** `perf_log_interval = 0`.

### A template bug worth fixing

The generated file emits the `[Storage]` header **in the middle of the layout key block**. Every
layout key documented after it — `screen_gap`, `large_screen_proportion`, `small_screen_position`,
all `custom_top_*` / `custom_bottom_*` / `custom_portrait_*`, `portrait_layout_option`,
`expand_to_cutout_area`, `layouts_to_cycle`, the `cardboard_*` keys, and duplicate copies of
`screen_orientation`, `swap_screen`, `enable_secondary_display` and `secondary_display_layout` —
appears under `[Storage]`, while `config.cpp` reads every one of them from **`"Layout"`**.

Harmless today, because those `[Storage]` copies are all empty and the real values landed in
`[Layout]`. But it is a live trap: **anyone hand-editing one of those keys where it appears in the
file will have it silently ignored.** The header sits at `default_ini.h:281`.

---

## 8. What to change in the repo

- **`2026-09-09-thor-device-snapshot.md`** is wrong twice: melonDS has **eight** native crashes,
  not two, and its conclusion that "the primary fix is the #1624 crash fix" is not supported.
  Both should be corrected against this note.
- **`docs/fork/upstream-candidates.md`**: remove the claim that Marty's melonDS crash is already
  fixed upstream and only needs a nightly. It is not established, and the crash class argues
  against it.
- **`melonds-android/RELEASE-NOTES.md`**: the snapshot already flagged that framing the foreground
  service as the fix is wrong. That still stands — but so does the reason the snapshot gave for it
  being wrong, which was itself based on the incomplete two-crash picture.
- **`docs/fork/research/texture-filter-crash.md`**: add the Thor confirmation — same signature,
  same `0xb4`, on driver 512.676.53 rather than the Fold5's 512.676.1.

## 9. What it would take to actually get a melonDS stack

Nothing on the device will yield one; every August record is gone from every store. The options
are all forward-looking, and all need Marty's say-so because they are writes:

1. Reproduce it. He plays with the lid/sleep on the dual screen; eight crashes in 2.5 h suggests it
   is not hard to hit. Then read it out of DropBox **within three days** — that retention window is
   the whole constraint.
2. The device is an `eng` build (`eng.Thor.20260206.163241`), so `adb root` would open
   `/data/tombstones` directly. It would not have helped here — the ring had already rotated
   twelve times over — but it makes a future capture trivial.

Given melonDS is now a nine-second-a-week app for him and Azahar is where he actually plays, the
honest recommendation is to **spend nothing more on the melonDS fork until a fresh crash is
captured**. The one concrete, cheap thing worth doing is asking him to reproduce it once.

---

### Provenance

`adb` serial `7f87e972` only. Commands used: `dumpsys dropbox|activity exit-info|usagestats|
display|thermalservice|deviceidle|package|battery`, `adb bugreport`, and `cat`/`ls`/`df` on
`/sdcard/azahar/**`, `/proc/meminfo`, `/sys/devices/system/cpu/cpufreq/*`, `/sys/class/kgsl/*`.
`settings get` only. No `pm`, `am`, `settings put`, `appops`, input events, or power changes.
