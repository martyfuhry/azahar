# Thor device measurement kit

`tools/thor/measure.sh` implements the on-device recipes of
`docs/fork/improvement-plan.md` §2.2 (D-1 … D-5, D-7) as one host-side bash script driven
over adb. Every subcommand prints a summary and keeps the raw dump it parsed under `OUT`
(default `measure-out/<timestamp>/`) so a number can always be traced back to its capture.

Requirements: `adb`, GNU coreutils (`join`, `sort`), awk (any POSIX awk; gawk works). Set
`ANDROID_SERIAL` when more than one device is attached; the script never guesses. The
lab phone is the Galaxy Z Fold5 `R3CW705DSTF`; the AYN Thor is measured with the same
commands once it is on adb.

```
export ANDROID_SERIAL=R3CW705DSTF
tools/thor/measure.sh identity
```

Environment overrides: `PKG` (default `org.azahar_emu.azahar`), `ACTIVITY`
(`org.citra.citra_emu.activities.EmulationActivity`), `ROM`
(`/storage/emulated/0/roms/3ds/acnl.3ds`), `OUT` (raw capture directory), `TOP`
(rows in per-thread tables, 15).

## Subcommands

### `install <apk> [expected-versionName]`

`adb install -r -d -t` followed by a check that `dumpsys package` now reports the APK's
`versionName` (read from the APK, or given explicitly). The Android `versionCode` is the
build's timestamp, and Android ignores `-d` for non-debuggable packages, so an older
build silently fails to replace a newer one with `INSTALL_FAILED_VERSION_DOWNGRADE`;
this subcommand turns that into a hard error instead of a mis-attributed measurement.

### `identity` — do this first, every time

Package `versionName`/`versionCode`/`lastUpdateTime`, the `Azahar Version:` banner from
logcat and from `/sdcard/azahar/log/azahar_log.txt`, pid, thread count, `oom_score_adj`,
wakefulness and fold state, battery, and whether `scrcpy` is running (its virtual display
and encoder confound both CPU and frame timing; the plan's first baseline was taken with
it on). Never attribute a number to a build without this line matching the build you
think you installed.

### `cpu <secs>` — D-1, the kill metric

Snapshots `/proc/<pid>/task/*/stat` twice, `<secs>` apart (device-side sleep), joins by
tid and prints `Δ(utime+stime) / window` per thread, sorted, plus the process total from
`/proc/<pid>/stat` (which also covers threads that exited inside the window). Threads are
identified by `comm`, so several rows may share a name (`NativeEmulation`,
`VulkanPresent`, binder pool). Also prints the process's `Proc #` line from `dumpsys
activity processes` (oomAdj, procState, cached/empty flags, and — for background
processes — the `run cpu over … used … (N%)` line that is the input to Android's
excessive-CPU kill), plus the freezer state and whether a foreground service is holding
importance.

Three states matter; put the device in the state, wait ~20 s for it to settle, then run:

```
tools/thor/measure.sh cpu 60                          # A: foreground, running
adb shell input keyevent KEYCODE_HOME; sleep 20;  tools/thor/measure.sh cpu 60   # B: HOME, screen on
adb shell input keyevent KEYCODE_SLEEP; sleep 20; tools/thor/measure.sh cpu 60   # C: screen off
```

Target while paused: total < 0.5 % (the OS limit is 2 % of a 5-minute window once the
process is cached).

### `mem [label]` — D-2

`dumpsys meminfo`: TOTAL PSS/RSS, GL mtrack, EGL mtrack, GL+EGL, Native Heap, Dalvik,
`.so mmap`, the `Graphics` summary; `/proc/<pid>/status`: VmRSS, RssAnon, RssFile,
RssShmem, VmSwap, Threads; and `oom_score_adj`. The label only names the raw file. Take
it at 60 s after the first frame (running), 30 s after HOME, and 5 min after HOME; the
Vulkan upload ring is lazily faulted, so early-session GL numbers understate it.

### `boot [--warm] [secs]` — D-3

Cold boot: `am force-stop`, clear every logcat buffer, read `date +%s%3N` on the device
(T0), send the VIEW intent, wait `secs` (default 15), then pull `logcat -v epoch` for the
`main`, `system` and `events` buffers and print a `+ms` timeline relative to T0:
`am_proc_start`, the first native log line, the version banner, `RunCitra`, framework
`Displayed +Nms`, `VK_DRIVER`, pipeline/shader disk cache load, `Service.SRV
RegisterClient` (guest OS up), autosave decision (`Resuming from autosave` / `Ignoring` /
`Skipping`), `Begin load of slot` / `Load completed`, and **the first frame of the
`SurfaceView[<pkg>/EmulationActivity]` layer** (BLASTBufferQueue "first frame is
available" from the app's pid), which is the headline "intent → first emulation frame"
number. `--warm` skips the force-stop, i.e. measures the `onNewIntent` restart of a
running instance.

For the "boot with autosave resume" scenario (autosave_mode = 2 on the phone): play
~60 s, press HOME (the autosave is written on pause), `am force-stop`, then
`measure.sh boot`. The timeline then shows the load and how long it adds.

### `frames <secs>` — D-4

Wraps `dumpsys SurfaceFlinger --timestats`: `-clear`, `-enable`, sleep, `-dump`,
`-disable`. Prints, for every layer whose name contains the package (activity window
layers with < 10 frames are skipped): totalFrames, droppedFrames, lateAcquireFrames,
jankyFrames, appBufferStuffingJankyFrames, averageFPS, the non-zero
`present2present` buckets, and p50/p95/p99 computed from that histogram. Buckets are
lower bounds (1 ms wide up to 34 ms, coarser above), so "p95=25ms" means the 95th
percentile present interval fell in the 25–26 ms bin. `dumpsys gfxinfo` is useless for
this app (HWUI only). Finishes by printing any `perf:` lines the app logged during the
window (see `perf`).

For per-frame detail (which vsync each frame hit, jank classification, scheduling of
`NativeEmulation`/`VulkanPresent` around it) use the Perfetto config in this directory:

```
adb push tools/thor/frametimeline.pbtxt /data/misc/perfetto-configs/azahar.pbtxt
adb shell perfetto --txt -c /data/misc/perfetto-configs/azahar.pbtxt -o /data/misc/perfetto-traces/azahar.pftrace
adb pull /data/misc/perfetto-traces/azahar.pftrace
```

and query `actual_frame_timeline_slice` (`layer_name LIKE '%azahar%'`) in
`trace_processor` or ui.perfetto.dev.

In-app frame times: set `[Debugging] record_frame_times = 1` in
`/sdcard/azahar/config/config.ini`; the app writes `<userdir>/log/<date>_<TITLEID>.csv`
with one system-frame time (ms) per line, flushed every ~10 s of frames and on shutdown,
so a process that Android kills still leaves the recording up to the last flush.

### `perf` — D-7

Prints the `perf: game_fps=… system_fps=… speed=…% frametime=…ms gpu=…ms swap=…ms
ipc=…ms svc=…ms rem=…ms` lines from `logcat -s CitraNative` and their mean. The app
logs one every `perf_log_interval` seconds (Settings → Debug → "Log performance
summary", or `[Debugging] perf_log_interval = 5` in config.ini; 0 = off). The counters
are the same ones the on-screen overlay reads, so with both enabled each sees shorter
windows; the values are rates and per-frame means, so they stay correct. The window is
restarted after a pause so the first line after resume does not count paused wall time.

### `exitinfo [N | save <label> | diff <before> <after>]` — D-5

`dumpsys activity exit-info <pkg>` condensed to one line per record: timestamp, pid,
reason, subreason, rss, state, description. The description string of an
`EXCESSIVE RESOURCE USAGE` record carries the CPU numbers (`excessive cpu <ms> during
<window ms> dur=<lifetime ms> limit=<%>`). `save` writes the current list to a file and
prints its path; `diff` prints the records present in the second file but not the first.

### `simpleperf <secs> [hz]` — D-6

`simpleperf record -p <pid> -e cpu-clock -f <hz> --duration <secs>` (simpleperf ships in
`/system/bin` on Android 10+), then `simpleperf report` sorted by `comm,dso,symbol` and
by `comm`. Works on the release APK because the manifest declares
`<profileable android:shell="true"/>`; without it the record step fails with a
permission error. The vanilla release build is stripped, so symbols in `libcitra-android.so`
show as the nearest exported symbol or an address; for full symbols build
`assembleVanillaRelWithDebInfoLite` and point `simpleperf report --symfs` at the
unstripped `.so` under `app/build/intermediates/merged_native_libs/`.

Manual recipe:

```
PID=$(adb shell pidof org.azahar_emu.azahar)
adb shell simpleperf record -p $PID -e cpu-clock -f 500 --duration 10 -o /data/local/tmp/perf.data
adb shell simpleperf report -i /data/local/tmp/perf.data --sort comm,dso,symbol -n | head -40
```

### `soak <play_s> <wait_min>` — D-5 protocol

`identity`; `exitinfo save`; launches the ROM if it is not running; plays `play_s`
seconds; `mem` (foreground); HOME; 20 s; `cpu 60` (state B); `mem`; `KEYCODE_SLEEP`;
20 s; `cpu 60` (state C); waits out the rest of `wait_min`; `cpu 60` again (the late
window, after the 15-minute cached tier would have applied); `mem`; `KEYCODE_WAKEUP` +
`wm dismiss-keyguard`; re-sends the launch intent; `exitinfo diff`; and reports whether
the game **resumed in place** (same pid and no `Azahar starting` / `Cleaning up process`
in `CitraNative` after the wake) or was restarted. `CPU_WINDOW` overrides the 60 s
windows. The phone must not be locked with a PIN that `dismiss-keyguard` cannot clear.

## Conventions

- Report the median of three runs for anything cheap (`cpu`, `mem`, `frames`); boot
  timelines are one run each but note the autosave decision the log shows.
- Same conditions for before/after: scrcpy off, same fold state, same ROM and scene,
  same screen state, and the identity block pasted next to the numbers.
- Raw captures under `OUT` are the evidence; keep them with the baseline document.

## `catch-melonds-crash.sh` — melonDS crash capture

A separate, single-shot script with a different job from `measure.sh`: it does not measure
anything, it **rescues a native crash record before Android rotates it away**. Run it on the
host as soon as Marty reports that melonDS died. Full procedure and rationale in
`docs/fork/melonds-crash-capture.md`.

```
export ANDROID_SERIAL=7f87e972          # the AYN Thor
tools/thor/catch-melonds-crash.sh
```

It is read-only against the device — `dumpsys`, `logcat`, `bugreport`, `ls`/`cat`, `pull`,
and nothing else. It never installs, launches, force-stops, or changes a setting. `--root`
is the single exception (it restarts adbd as root to read `/data/tombstones` directly on the
Thor's eng build) and is off by default.

What it collects, into a timestamped directory:

| | |
|---|---|
| `00`–`01` | capture identity, and every installed `melon*` package with its version |
| `02` | `dumpsys activity exit-info` per package — the *record that* a crash happened |
| `03`–`04` | `dumpsys dropbox`, plus `--print` for `data_app_native_crash`, `SYSTEM_TOMBSTONE`, `data_app_crash`, `data_app_anr` — where the *stack* lives |
| `05` | `logcat -b crash -d` and the filtered main buffer |
| `06` | the app's own `files/emulator-recovery/` journal (`session.json`, `journal.jsonl`) |
| `07` | any `melonds-diagnostics-*.zip` the app's "Export diagnostics" wrote to `/sdcard` |
| `08` | `adb bugreport` and the tombstones extracted from it (`--no-bugreport` skips it) |
| `09` | symbolicated stacks |
| `SUMMARY.md` | what was found, and **whether the stack was already gone** |

Symbolication runs `ndk-stack` and then a per-frame `llvm-symbolizer` pass, because
`ndk-stack` only recognises blocks that carry the `*** ***` tombstone header and silently
skips logcat-prefixed stacks. Both use the archived unstripped library in
`~/Development/azahar-builds/melonds-symbols-c86e8147/`.

**It checks the build id before it believes a symbol.** If the crashed
`libmelonDS-android-frontend.so` does not carry the build id the archive was built from, the
summary and the stack file both open with a warning that every name and line below is
fiction. A stack symbolicated against the wrong binary is not a weaker answer, it is a
fabricated one.
