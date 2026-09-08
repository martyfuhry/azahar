# thor-v1-rc1 vs thor/main baseline — 2026-09-08

- BASELINE: `azahar-baseline-b8aa5f893-vc33730195.apk`, versionName `d246c02f4-vanilla`
  (commit `b8aa5f893` plus a gradle `versionCode` override only), versionCode 33730195
- CANDIDATE: `azahar-thor-v1-rc1-candidate.apk`, versionName `009cfe333-vanilla`,
  versionCode 33730195

Both APKs carry the same `versionCode`, so `adb install -r -d -t` replaces in either
direction and the pass measured the candidate first, then the baseline, then restored the
candidate. Identity was confirmed with `dumpsys package … | grep versionName` after every
install; the baseline's boot banner reads `UNKNOWN | UNKNOWN-UNKNOWN` (that build has no
git metadata baked in), which is itself a second, independent confirmation of which build
produced each timeline.

## Conditions

Galaxy Z Fold5 `R3CW705DSTF`, SM-F946U1, Android 16 (API 36). **scrcpy was not running**
for the whole session (`adb shell ps -A | grep scrcpy` empty), so this is the first
scrcpy-free before/after pair; the `improvement-plan.md` §2.2 baseline was taken with it
on and is not comparable. Phone **folded** (`cmd device_state print-state` = 0), the
2316x904 cover panel active, display refresh 120 Hz, `mWakefulness=Awake` throughout — no
`KEYCODE_SLEEP`/`POWER`/`WAKEUP` was ever sent, so state C (screen off) is out of scope.
Battery 30 % and charging, SoC 30.8 °C at the start. ROM
`/storage/emulated/0/roms/3ds/acnl.3ds`, resumed from the same autosave in both runs, so
the two builds ran the same scene. `/sdcard/azahar/config/config.ini` has
`autosave_mode = 2`. One run per metric, no repeats; medians of three were not affordable
inside the 25-minute device window, so treat differences under ~5 % as noise. Device lock
`scratchpad/device.lock` was held for the session. Raw captures:
`…/scratchpad/rc1b/out-cand/` and `…/scratchpad/rc1b/out-base/`.

## Results

| Metric | Baseline `b8aa5f893` | Candidate `009cfe333` | Delta |
|---|---|---|---|
| D-5 exit-info snapshot (pre) | no crash/ANR records | no crash/ANR records | — |
| D-3 cold boot, autosave ignored: intent → first emulated frame (ms) | 2162 | 2066 | −96 (−4.4 %) |
| D-3 cold boot, autosave resume: intent → first emulated frame (ms) | 3535 | 3374 | −161 (−4.6 %) |
| D-3 savestate load phase (ms) | 1664 | 1562 | −102 |
| D-3 pipeline disk cache load (KB) | 9429 | 9429 | 0 |
| D-2 TOTAL PSS @45 s (kB) | 1 702 451 | 1 243 443 | **−459 008 (−27.0 %)** |
| D-2 GL mtrack @45 s (kB) | 770 552 | 311 980 | **−458 572 (−59.5 %)** |
| D-2 EGL mtrack @45 s (kB) | 126 316 | 126 316 | 0 |
| D-2 Native Heap @45 s (kB) | 616 007 | 615 655 | −352 |
| D-2 VmRSS @45 s (kB) | 925 840 | 927 600 | +1 760 (+0.2 %) |
| D-2 threads @45 s | 55 | 55 | 0 |
| D-1 foreground CPU, 20 s (% of one core) | 62.73 | 59.31 | −3.42 pp |
| D-4 p50 present interval, 30 s (ms) | 16 | 16 | 0 |
| D-4 p95 present interval, 30 s (ms) | 25 | 25 | 0 |
| D-4 p99 present interval, 30 s (ms) | 33 | 33 | 0 |
| D-4 janky frames, 30 s (Azahar SurfaceView layer) | 0 / 1797 | 0 / 1798 | 0 |
| D-4 averageFPS, 30 s | 60.630 | 60.620 | −0.01 |
| D-1 paused CPU after HOME, 30 s (total %) | 8.19 | 0.33 | **−7.86 pp (−96 %)** |
| D-1 paused CPU: top 5 threads | `AAudio_1` 7.82 %, `NativeEmulation` 0.30 %, main 0.07 %, rest 0.00 % | `NativeEmulation` 0.36 %, rest 0.00 % | `AAudio_1` gone |
| D-2 TOTAL PSS after HOME (kB) | 1 667 326 | 1 209 976 | −457 350 (−27.4 %) |
| D-2 GL mtrack after HOME (kB) | 769 296 | 310 528 | −458 768 |
| Relaunch resumes in place (no restart markers) | yes (same pid 19862, "task brought to the front") | yes (same pid 16861) | same |
| Autosave written on pause | yes | yes | same |
| Autosave loaded after force-stop | yes ("Resuming from autosave", "Begin load of slot 1000") | yes (same two lines) | same |
| Pipeline disk cache loaded, non-zero, after kill | 9429 KB | 9429 KB | same |
| D-5 CRASH_NATIVE / ANR over 5 HOME/relaunch cycles | none (2 records, both the FORCE STOPs this pass issued) | none (same) | same |

The candidate additionally logs `perf:` summaries (mean over the 30 s frame window:
`game_fps=59.52 speed=99.33 % frametime=5.70 ms gpu=1.41 ms swap=0.13 ms ipc=0.05 ms
svc=0.06 ms rem=4.06 ms`). The baseline logs none — that instrumentation is part of the
candidate — so D-7 has no before/after pair this round.

## Reading the numbers

**The two wins are memory and paused CPU.** GL mtrack drops 448 MiB, which is the whole of
the TOTAL PSS difference (native heap, EGL, `.so mmap` and VmRSS are unchanged), and it is
the same 448 MiB whether the app is in the foreground or parked behind HOME — a smaller
steady-state graphics allocation, not a deferred one. Paused CPU falls from 8.19 % to
0.33 % because `AAudio_1`, which burned 7.82 % of a core with the game paused on the
baseline, stops entirely; the candidate leaves only `NativeEmulation` at 0.36 %. Against
the "< 0.5 % while paused" target in the kit README, the baseline misses by 16x and the
candidate meets it, which is the metric that governs Android's excessive-CPU kill.

**Frame pacing is unchanged and already clean.** Identical p50/p95/p99 buckets, zero janky
and zero dropped frames on both builds, 60.6 fps average against a 120 Hz panel — the
present2present histogram is the expected 8 ms/25 ms two-mode split of a 60 fps guest on a
120 Hz display. There is no frame-pacing regression, and no headroom visible here either.

**Boot is ~4.5 % faster in both scenarios**, which is at the edge of what a single run can
claim. The savestate load phase is 102 ms shorter and the pre-load phases are within a few
ms of each other, so what improvement there is sits in the load, not in startup.
Foreground CPU is 3.4 pp lower, also close to single-run noise.

## Anomalies and caveats

- **Baseline's first boot after install was 4608 ms**, versus 3535 ms for the identical
  scenario one boot later. That first run pays cold page-cache faults for the freshly
  written APK, so it is excluded from the table; the candidate's numbers were both taken
  after it had already been resident. Any future pass should discard the first boot after
  an install.
- **`measure.sh cpu` per-thread rows sum to far less than the process total** in the
  foreground window (≈19 % of rows against a 59–63 % process total). The per-thread table
  is a join of two `/proc/<pid>/task/*/stat` snapshots, so threads created or destroyed
  inside the window contribute to `/proc/<pid>/stat` but drop out of the join. The totals
  are sound; the thread breakdown understates short-lived work. Only the paused windows,
  where the thread set is stable, should be read thread-by-thread.
- Both builds keep a foreground service after HOME (`Proc # 2: fg +50 M/S/FGS`,
  `oom_score_adj=50`, `isFrozen=false`), so neither was frozen or cached during the paused
  window; the 8.19 % vs 0.33 % difference is real work, not a freezer artifact.
- The autosave decision depends on whether the autosave is newer than `.lastboot`, so
  consecutive boots alternate between "resuming" and "Ignoring autosave … that predates the
  previous boot". Both scenarios were captured for both builds rather than letting the
  sequence pick one.
- 5 HOME/relaunch cycles on each build produced no crash, no ANR, and no new process: the
  pid was stable across all five on both builds.

## Device state left behind

Candidate installed (`versionName=009cfe333-vanilla`, versionCode 33730195), app
force-stopped, `/sdcard/azahar` untouched apart from the autosave the app itself rewrote on
each pause.

## First attempt (aborted)

An earlier pass the same day produced no numbers. The baseline APK then in hand had an
older `versionCode` than what the phone was carrying, the downgrade install failed, and
`pm uninstall -k` was tried as a workaround — it keeps the version record, so it cannot fix
a downgrade, and it left the app gone with the block still in place. The full `adb
uninstall` that followed dropped the SAF grant and the stored user-directory path from the
app's private SharedPreferences, which dumped the phone into the first-run wizard and
invalidated the rest of the session. The fix used here was to rebuild the baseline commit
with the candidate's `versionCode` (`azahar-baseline-b8aa5f893-vc33730195.apk`), so
installs replace cleanly in either direction and no uninstall is ever needed.
