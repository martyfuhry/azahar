# thor-v1-rc1 vs thor/main baseline — 2026-09-08 (ABORTED, no numbers)

**Status: no measurements were taken.** The session was lost to an install-ordering
mistake that wiped the app's private data and dropped the phone into the first-run
setup wizard. This document records the conditions, the failure, and the exact recovery
step so the next measurement pass does not repeat it. Every cell below is `n/a` on
purpose: publishing a half-run or a number from the wrong build would be worse than
publishing nothing.

- BASELINE: `azahar-thor-main-b8aa5f893.apk`, versionName `b8aa5f893-vanilla`, versionCode 33728303
- CANDIDATE: `azahar-thor-v1-rc1-candidate.apk`, versionName `009cfe333-vanilla`, versionCode 33730195

## Conditions

Galaxy Z Fold5 `R3CW705DSTF`, SM-F946U1, Android 16 (API 36). **scrcpy was not running**
(`adb shell ps -A | grep scrcpy` returned nothing) — this would have been the first
scrcpy-free comparison, unlike the confounded baseline in `improvement-plan.md` §2.2.
Phone **folded** (`cmd device_state print-state` = 0), so the cover panel (2316x904) was
the active display; screen **on and awake** (`mWakefulness=Awake`) for the whole session.
Battery 26 % and charging. No `KEYCODE_SLEEP`/`POWER`/`WAKEUP` was ever sent, per the
swarm contract, so state C (screen off) was out of scope anyway. ROM
`/storage/emulated/0/roms/3ds/acnl.3ds`; `/sdcard/azahar/config/config.ini` has
`autosave_mode = 2`. Device lock `scratchpad/device.lock` was held for the session.

## Results

| Metric | Baseline `b8aa5f893` | Candidate `009cfe333` | Delta |
|---|---|---|---|
| D-5 exit-info snapshot (pre) | n/a | n/a | n/a |
| D-3 cold boot: intent → first emulated frame (ms) | n/a | n/a | n/a |
| D-2 TOTAL PSS @45 s (kB) | n/a | n/a | n/a |
| D-2 GL mtrack @45 s (kB) | n/a | n/a | n/a |
| D-2 EGL mtrack @45 s (kB) | n/a | n/a | n/a |
| D-2 Native Heap @45 s (kB) | n/a | n/a | n/a |
| D-2 VmRSS @45 s (kB) | n/a | n/a | n/a |
| D-1 foreground CPU, 20 s (% of one core) | n/a | n/a | n/a |
| D-4 p50 present interval, 30 s (ms) | n/a | n/a | n/a |
| D-4 p95 present interval, 30 s (ms) | n/a | n/a | n/a |
| D-4 janky frames, 30 s | n/a | n/a | n/a |
| D-1 paused CPU after HOME, 30 s (total %) | n/a | n/a | n/a |
| D-1 paused CPU: top 5 threads | n/a | n/a | n/a |
| D-2 TOTAL PSS after HOME (kB) | n/a | n/a | n/a |
| Relaunch resumes in place (no restart markers) | n/a | n/a | n/a |
| Autosave written on pause | n/a | n/a | n/a |
| Autosave loaded after force-stop | n/a | n/a | n/a |
| Pipeline disk cache loaded, non-zero, after kill | n/a | n/a | n/a |
| D-5 CRASH_NATIVE / ANR over 5 HOME/relaunch cycles | n/a | n/a | n/a |

The one number the session did produce is not a build comparison and is recorded only so
the capture is not lost: with the baseline APK freshly installed and the app parked on
`MainActivity` (no game running, first-run wizard pending), TOTAL PSS was 98 370 kB,
GL mtrack 5 664 kB, EGL mtrack 34 960 kB, Native Heap 10 611 kB, VmRSS 174 752 kB,
25 threads, `oom_score_adj=0`, and the 20 s foreground CPU sample was 0.00 % on every
thread. That is an idle game-list screen, not an emulation workload, and it must not be
read as a D-1/D-2 baseline.

## What went wrong

The APK `versionCode` is the build timestamp, so the candidate (33730195) is newer than
the baseline (33728303), and the phone happened to be carrying a still newer unrelated
build (33729922, versionName `106994756-vanilla`). Installing the *older* baseline first
therefore hit `INSTALL_FAILED_VERSION_DOWNGRADE`; Android ignores `adb install -d` for a
non-debuggable package.

`adb shell pm uninstall -k` does **not** help here: `-k` keeps the data *and the version
record*, so the downgrade is still refused, now with the package in the
"uninstalled with DELETE_KEEP_DATA" state — strictly worse, because the app is gone and
the block remains. Clearing the record needs a full `adb uninstall`, which drops the
app's private data. The Azahar *user* directory lives in shared storage
(`/sdcard/azahar`, reached through MANAGE_EXTERNAL_STORAGE) and survived intact —
`states/`, `config/config.ini` with `autosave_mode = 2`, and the existing
`0004000000086300.autosave.cst` are all still there — but the SAF grant and the stored
user-directory path live in the app's private SharedPreferences and did not. Re-granting
`appops set … MANAGE_EXTERNAL_STORAGE allow` restores the permission but not the stored
path, so the launch intent to `EmulationActivity` is redirected to `MainActivity` and
the app shows the "Welcome!" setup wizard. Every subsequent metric in the run came back
`is not running` or `Activity class … does not exist`.

## Ordering rule for the next pass

**Install the oldest build first, then walk forward.** For this pair: install
`b8aa5f893` while nothing newer is present, measure it, then install `009cfe333` over the
top (an upgrade, which always succeeds) and measure that. Never `pm uninstall -k` to
work around a downgrade — it cannot fix one. If the phone is already carrying a build
newer than both, the only options are a full `adb uninstall` (accepting the wizard, and
budgeting time to complete it) or rebuilding the older commit so its `versionCode` is
current.

`tools/thor/measure.sh install` already turns a mis-attributed build into a hard error,
which worked; the failure was in the recovery path, not the check. Worth adding: make
`measure.sh install` refuse the `-k` fallback and say "install the older build first".

## Device state left behind

The **candidate is installed** (`versionName=009cfe333-vanilla`, versionCode 33730195)
with `MANAGE_EXTERNAL_STORAGE` allowed. The app is parked on the first-run wizard at the
**"Data Folders"** step, which needs "Select User Folder" → `/sdcard/azahar` chosen in
the system SAF picker. That is a handful of taps in the document picker and was not
driven blind here. `/sdcard/azahar` itself is untouched, so completing the picker should
restore the previous setup, including the autosave and `autosave_mode = 2`.

Raw command log and captures:
`/tmp/claude-1000/-home-martyfuhry-Development-azahar/81d208bf-d196-428d-85e5-d4a76924c180/scratchpad/rc1/`
(`baseline.log`, `out-baseline/` with the boot timeline, meminfo and exit-info dumps, and
`s.png`/`s2.png`/`s3.png`/`s4.png` showing the wizard state).
