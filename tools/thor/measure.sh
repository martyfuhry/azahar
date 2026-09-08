#!/usr/bin/env bash
# Copyright 2026 Citra Emulator Project / Azahar Emulator Project
# Licensed under GPLv2 or any later version
# Refer to the license.txt file included.
#
# Device measurement kit for the Azahar fork (docs/fork/improvement-plan.md §2.2, D-1..D-5, D-7).
# Runs on the host against one Android device over adb. See tools/thor/README.md.
#
#   measure.sh identity                 build/package/process identity, screen and scrcpy state
#   measure.sh cpu <secs>               per-thread CPU % over a window, total, oomAdj/procState
#   measure.sh mem [label]              PSS / GL / EGL / Native Heap, VmRSS/RssAnon/VmSwap, oom_score_adj
#   measure.sh boot [--warm] [secs]     boot timeline from the launch intent to the first frame
#   measure.sh frames <secs>            SurfaceFlinger timestats: present-to-present p50/p95/p99
#   measure.sh perf                     PerfStats lines the app logged (perf_log_interval > 0)
#   measure.sh exitinfo [save|diff A B] dumpsys activity exit-info, optionally diffed
#   measure.sh simpleperf <secs>        simpleperf record + report (needs the profileable manifest)
#   measure.sh soak <play_s> <wait_min> play, HOME, screen off, wait, wake, relaunch, report
#
# Environment: ANDROID_SERIAL (adb picks it up), PKG, ACTIVITY, ROM, OUT (raw capture dir),
# TOP (rows in the per-thread table, default 15).

set -euo pipefail

PKG=${PKG:-org.azahar_emu.azahar}
ACTIVITY=${ACTIVITY:-org.citra.citra_emu.activities.EmulationActivity}
ROM=${ROM:-/storage/emulated/0/roms/3ds/acnl.3ds}
TOP=${TOP:-15}
STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT:-measure-out/$STAMP}
CLK_TCK=100

die() { echo "measure.sh: $*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
hdr() { printf '\n== %s ==\n' "$*"; }
ash() { adb shell "$@"; }
now_ms() { ash date +%s%3N | tr -d '\r'; }
raw() { mkdir -p "$OUT"; printf '%s/%s' "$OUT" "$1"; }

pid_of() { ash pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
require_pid() {
    local pid
    pid=$(pid_of)
    [ -n "$pid" ] || die "$PKG is not running"
    printf '%s' "$pid"
}

launch_intent() {
    ash am start -a android.intent.action.VIEW -d "file://$ROM" -t application/octet-stream \
        -n "$PKG/$ACTIVITY" >/dev/null
}

screen_state() {
    # Awake/Asleep/Dozing plus the physical fold state (0 = folded on a Fold5)
    local wake fold
    wake=$(ash dumpsys power | tr -d '\r' | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)
    fold=$(ash cmd device_state print-state 2>/dev/null | tr -d '\r' | head -1)
    printf 'wakefulness=%s device_state=%s' "${wake:-?}" "${fold:-?}"
}

top_activity() {
    ash dumpsys activity activities | tr -d '\r' | grep -m1 -oE 'topResumedActivity=ActivityRecord\{[^}]*\}' \
        | sed -E 's/.*u0 ([^ ]+) .*/\1/'
}

adb_identity() {
    adb get-serialno | tr -d '\r'
}

# ---------------------------------------------------------------- identity ----

cmd_identity() {
    hdr "identity"
    say "serial:        $(adb_identity)"
    say "device:        $(ash getprop ro.product.model | tr -d '\r') / Android $(ash getprop ro.build.version.release | tr -d '\r') (API $(ash getprop ro.build.version.sdk | tr -d '\r'))"
    ash dumpsys package "$PKG" | tr -d '\r' | grep -E "versionName|versionCode|lastUpdateTime" \
        | sed -E 's/^ +/package:       /' | head -3
    local banner
    banner=$(adb logcat -d -s CitraNative:I 2>/dev/null | grep "Azahar Version" | tail -1 | sed -E 's/.*Azahar Version: //')
    say "log banner:    ${banner:-<none in logcat; app not booted since logcat was cleared>}"
    local logfile
    logfile=$(ash "grep -a 'Azahar Version' /sdcard/azahar/log/azahar_log.txt 2>/dev/null | tail -1" | tr -d '\r' | sed -E 's/.*Azahar Version: //')
    say "log file:      ${logfile:-<no Azahar Version line in /sdcard/azahar/log/azahar_log.txt>}"
    local pid
    pid=$(pid_of)
    if [ -n "$pid" ]; then
        say "pid:           $pid, threads=$(ash cat /proc/$pid/status | tr -d '\r' | awk '/^Threads:/{print $2}'), oom_score_adj=$(ash cat /proc/$pid/oom_score_adj | tr -d '\r')"
    else
        say "pid:           <not running>"
    fi
    say "screen:        $(screen_state), top=$(top_activity)"
    say "battery:       $(ash dumpsys battery | tr -d '\r' | awk '/^  level:/{l=$2} /^  status:/{s=$2} /^  temperature:/{t=$2/10} END{print l"% status="s" temp="t"C"}')"
    local scr
    scr=$(ash ps -A | tr -d '\r' | grep -i scrcpy || true)
    if [ -n "$scr" ]; then
        say "scrcpy:        RUNNING (confounds frame timing and CPU)"
        say "$scr" | sed 's/^/               /'
    else
        say "scrcpy:        not running"
    fi
    local vd
    vd=$(ash dumpsys SurfaceFlinger --display-id 2>/dev/null | tr -d '\r' | grep -i virtual || true)
    [ -n "$vd" ] && say "virtual disp:  $vd"
    return 0
}

# --------------------------------------------------------------------- cpu ----

# One line per thread: tid \t comm \t utime+stime (ticks); last line: TOTAL \t process \t ticks
snap_threads() {
    local pid=$1
    ash "cat /proc/$pid/task/*/stat 2>/dev/null; echo '=== '; cat /proc/$pid/stat" | tr -d '\r' \
        | awk '
            /^=== / { proc = 1; next }
            {
                tid = $1
                lp = index($0, "(")
                rp = 0
                for (i = length($0); i > 0; i--) if (substr($0, i, 1) == ")") { rp = i; break }
                comm = substr($0, lp + 1, rp - lp - 1)
                rest = substr($0, rp + 2)
                n = split(rest, f, " ")
                ticks = f[12] + f[13]
                if (proc) printf "TOTAL\tprocess\t%d\n", ticks
                else printf "%s\t%s\t%d\n", tid, comm, ticks
            }'
}

proc_state() {
    # oomAdj / procState / frozen / "run cpu over" for the package, from dumpsys activity processes
    local pid=$1 f
    f=$(raw "activity-processes-$(date +%H%M%S).txt")
    ash dumpsys activity processes > "$f" 2>/dev/null || true
    tr -d '\r' < "$f" | awk -v pid="$pid" -v pkg="$PKG" '
        $0 ~ "Proc # *[0-9]+:.* " pid ":" pkg "/" { p = 1; print; next }
        p && /Proc #/ { p = 0 }
        p && /oom:|state:|cached=|run cpu/ { print }
        $0 ~ "ProcessRecord\\{[^}]* " pid ":" pkg "/" { q = 1; next }
        q && /\*APP\*|\*PERS\*/ { q = 0 }
        q && /isFrozen|earliestFreezable|mHasForegroundServices/ { print }
    ' | sed 's/^ */    /'
}

cmd_cpu() {
    local secs=${1:-60}
    local pid t0 t1 f0 f1
    pid=$(require_pid)
    hdr "cpu: pid $pid, ${secs}s window, $(screen_state), top=$(top_activity)"
    f0=$(raw "threads-t0-$(date +%H%M%S).tsv"); f1=$(raw "threads-t1-$(date +%H%M%S).tsv")
    snap_threads "$pid" > "$f0"
    t0=$(now_ms)
    ash sleep "$secs"
    snap_threads "$pid" > "$f1"
    t1=$(now_ms)
    local elapsed
    elapsed=$(( t1 - t0 ))
    [ "$(pid_of)" = "$pid" ] || die "pid changed during the window (was $pid, now '$(pid_of)')"
    # CPU% = delta ticks / (elapsed_s * CLK_TCK) * 100 = delta / elapsed_s  (CLK_TCK is 100)
    local table
    table=$(join -t $'\t' -j 1 <(sort "$f0") <(sort "$f1") \
        | awk -F'\t' -v ms="$elapsed" -v tck="$CLK_TCK" '
            { d = $5 - $3; pct = d * 100.0 / (ms / 1000.0 * tck); printf "%.3f\t%d\t%s\t%s\n", pct, d, $1, $2 }')
    printf '  %7s  %6s  %s\n' "cpu" "ticks" "thread"
    printf '%s\n' "$table" | grep -v $'\tTOTAL\t' | sort -t$'\t' -k1,1rn -k2,2rn | head -n "$TOP" \
        | awk -F'\t' '{ printf "  %6.2f%%  %6d  %s\n", $1, $2, $4 }'
    printf '%s\n' "$table" | awk -F'\t' -v ms="$elapsed" '
        $3 == "TOTAL" { total = $1; next } { n++ }
        END { printf "  -------\n  %6.2f%%  total (process utime+stime over %.1f s, %d threads)\n", total, ms / 1000.0, n }'
    hdr "process state"
    proc_state "$pid"
}

# --------------------------------------------------------------------- mem ----

cmd_mem() {
    local label=${1:-} pid f
    pid=$(require_pid)
    hdr "mem${label:+: $label}: pid $pid, $(screen_state), top=$(top_activity)"
    f=$(raw "meminfo-${label:-now}-$(date +%H%M%S).txt")
    ash dumpsys meminfo "$pid" | tr -d '\r' > "$f"
    awk '
        /TOTAL PSS:/ { match($0, /TOTAL PSS: +[0-9]+/); split(substr($0, RSTART, RLENGTH), a, " +"); pss = a[3]
                       match($0, /TOTAL RSS: +[0-9]+/); split(substr($0, RSTART, RLENGTH), a, " +"); rss = a[3]
                       match($0, /TOTAL SWAP PSS: +[0-9]+/); split(substr($0, RSTART, RLENGTH), a, " +"); swp = a[4] }
        /^ *Native Heap +[0-9]/ && !nh { nh = $3; nhr = $4 }
        /^ *GL mtrack/ { gl = $3 }
        /^ *EGL mtrack/ { egl = $3 }
        /^ *Graphics:/ { gfx = $2 }
        /^ *\.so mmap/ { so = $3 }
        /^ *Dalvik Heap/ && !dh { dh = $3 }
        END {
            mib = 1024.0
            printf "  TOTAL PSS   %8d kB (%6.0f MiB)   TOTAL RSS %8d kB (%6.0f MiB)   SWAP PSS %d kB\n", pss, pss / mib, rss, rss / mib, swp
            printf "  GL mtrack   %8d kB (%6.0f MiB)   EGL mtrack %7d kB (%5.0f MiB)   GL+EGL %6.0f MiB\n", gl, gl / mib, egl, egl / mib, (gl + egl) / mib
            printf "  Native Heap %8d kB (%6.0f MiB)   Dalvik %6d kB   .so mmap %7d kB   Graphics(summary) %s kB\n", nh, nh / mib, dh, so, gfx
        }' "$f"
    ash "cat /proc/$pid/status; echo oom_score_adj=\$(cat /proc/$pid/oom_score_adj)" | tr -d '\r' \
        | awk '/^VmRSS|^RssAnon|^RssFile|^RssShmem|^VmSwap/ { printf "  %-9s %8s kB (%5.0f MiB)\n", $1, $2, $2 / 1024.0 }
               /^Threads/ { printf "  %-9s %8s\n", $1, $2 }
               /^oom_score_adj=/ { print "  " $0 }'
}

# -------------------------------------------------------------------- boot ----

# Prints "+ms  event" for the boot events found in an epoch-stamped logcat capture.
boot_timeline() {
    local log=$1 t0=$2 pid=$3
    awk -v t0="$t0" -v pid="$pid" -v pkg="$PKG" -v act="$ACTIVITY" '
        function ev(name) { printf "%7d  %s\n", (ts * 1000) - t0, name }
        { ts = $1 }
        $0 ~ "am_proc_start: \\[0," pid "," && !seen["fork"]++ { ev("am_proc_start (process fork)") }
        $2 == pid && /Logging backend initialised|LoadINI/ && !seen["cfg"]++ { ev("config loaded (first native log)") }
        $2 == pid && /Azahar Version/ && !seen["ver"]++ { sub(/.*Azahar Version: /, ""); ev("banner: " $0) }
        $2 == pid && /Azahar starting/ && !seen["run"]++ { ev("RunCitra: Azahar starting") }
        $0 ~ "Displayed " pkg "/" act && !seen["disp"]++ { match($0, /\+[0-9]+ms/); ev("ActivityTaskManager Displayed " substr($0, RSTART, RLENGTH)) }
        $2 == pid && /VK_DRIVER/ && !seen["vk"]++ { sub(/.*VK_DRIVER: /, ""); ev("VK_DRIVER " $0) }
        $2 == pid && /Upload buffer created|Creating upload buffer/ && !seen["upl"]++ { ev("Vulkan upload buffer created") }
        $2 == pid && /LoadDriverPipelineDiskCache/ && !seen["pc"]++ { match($0, /size [0-9]+ KB/); ev("pipeline disk cache load begins (" substr($0, RSTART, RLENGTH) ")") }
        $2 == pid && /Init(VS|FS|GS|PL)Cache/ && !seen["sc"]++ { ev("shader disk cache load begins") }
        $2 == pid && /RegisterClient/ && !seen["srv"]++ { ev("Service.SRV RegisterClient (guest OS up)") }
        $2 == pid && /Resuming from autosave/ && !seen["as"]++ { ev("autosave: resuming") }
        $2 == pid && /Ignoring autosave|Skipping autosave/ && !seen["as"]++ { sub(/.*<Info> /, ""); sub(/.*<Warning> /, ""); sub(/.*: /, ""); ev("autosave: " $0) }
        $2 == pid && /Begin load of slot/ && !seen["ld0"]++ { ev("savestate load begins") }
        $2 == pid && /Load completed/ && !seen["ld1"]++ { ev("savestate load completed") }
        $2 == pid && $0 ~ "SurfaceView\\[" pkg "/" act "\\].*first frame is available" && !seen["ff"]++ { ev("FIRST EMULATION FRAME (SurfaceView BLASTBufferQueue)") }
        $2 == pid && /DSP firmware|Loaded DSP/ && !seen["dsp"]++ { ev("DSP firmware loaded") }
    ' "$log"
}

cmd_boot() {
    local warm=0 wait_s=15
    while [ $# -gt 0 ]; do
        case "$1" in
            --warm) warm=1 ;;
            *) wait_s=$1 ;;
        esac
        shift
    done
    hdr "boot: $( [ $warm = 1 ] && echo warm re-intent || echo cold ), ROM=$ROM, $(screen_state)"
    if [ $warm = 0 ]; then
        ash am force-stop "$PKG"
        sleep 2
    fi
    adb logcat -b all -c
    local t0 t_start
    t0=$(now_ms)
    launch_intent
    t_start=$(now_ms)
    say "intent sent (adb round trip $(( t_start - t0 )) ms); waiting ${wait_s}s for the boot to settle"
    sleep "$wait_s"
    local pid log
    pid=$(pid_of)
    [ -n "$pid" ] || die "$PKG did not start"
    log=$(raw "boot-$(date +%H%M%S).log")
    adb logcat -d -v epoch -b main,system,events | tr -d '\r' > "$log"
    echo "$t0" > "$log.t0"
    say "T0 (device epoch ms) = $t0, pid = $pid, raw log: $log"
    printf '%7s  %s\n' "+ms" "event"
    boot_timeline "$log" "$t0" "$pid"
    local ff
    ff=$(boot_timeline "$log" "$t0" "$pid" | awk '/FIRST EMULATION FRAME/ {print $1}')
    say "intent -> first emulation frame: ${ff:-<not seen within ${wait_s}s>} ms"
}

# ------------------------------------------------------------------ frames ----

# Percentile from a SurfaceFlinger "Nms=count ..." histogram line; buckets are lower bounds.
hist_percentiles() {
    awk -v pcts="$1" '
        { n = split($0, kv, " "); total = 0
          for (i = 1; i <= n; i++) { split(kv[i], p, "="); ms[i] = p[1] + 0; c[i] = p[2] + 0; total += c[i] }
          m = split(pcts, want, ",")
          out = ""
          for (j = 1; j <= m; j++) {
              target = total * want[j] / 100.0; acc = 0; val = "n/a"
              for (i = 1; i <= n; i++) { acc += c[i]; if (acc >= target && total > 0) { val = ms[i] "ms"; break } }
              out = out sprintf("p%s=%s ", want[j], val)
          }
          printf "%s(n=%d)", out, total }'
}

cmd_frames() {
    local secs=${1:-60} pid f
    pid=$(require_pid)
    hdr "frames: pid $pid, ${secs}s of SurfaceFlinger timestats, $(screen_state)"
    ash dumpsys SurfaceFlinger --timestats -clear >/dev/null
    ash dumpsys SurfaceFlinger --timestats -enable >/dev/null
    ash sleep "$secs"
    f=$(raw "timestats-$(date +%H%M%S).txt")
    ash dumpsys SurfaceFlinger --timestats -dump | tr -d '\r' > "$f"
    ash dumpsys SurfaceFlinger --timestats -disable >/dev/null
    awk '/^displayRefreshRate|^renderRate/ && !seen[$1]++ { printf "  %s\n", $0 }' "$f"
    # Per-layer blocks start at "uid = "; keep the ones whose layerName mentions the package
    awk -v pkg="$PKG" '
        /^uid = / { inblk = 1; name = ""; keep = 0; next }
        inblk && /^layerName = / { name = $0; sub(/^layerName = /, "", name); keep = index(name, pkg) > 0; order[++k] = name; next }
        inblk && keep && /^totalFrames = / { frames[name] = $3 }
        inblk && keep && /^(totalFrames|droppedFrames|lateAcquireFrames|jankyFrames|appBufferStuffingJankyFrames|averageFPS) = / { stats[name] = stats[name] "  " $0 "\n" }
        inblk && keep && /^present2present histogram/ { getline h; p2p[name] = h }
        END {
            for (i = 1; i <= k; i++) {
                n = order[i]
                if (!(n in stats)) continue
                if (frames[n] < 10) { printf "  layer (skipped, %d frames): %s\n", frames[n], n; continue }
                printf "  layer: %s\n%s", n, stats[n]
                nz = ""; m = split(p2p[n], kv, " ")
                for (j = 1; j <= m; j++) if (kv[j] !~ /=0$/) nz = nz " " kv[j]
                printf "  present2present buckets:%s\n", nz
                printf "P2P\t%s\n", p2p[n] > "/dev/stderr"
            }
        }' "$f" 2> "$f.hist"
    while IFS=$'\t' read -r tag hist; do
        [ "$tag" = P2P ] && say "  present-to-present:       $(printf '%s' "$hist" | hist_percentiles 50,95,99)"
    done < "$f.hist"
    say "  (bucket lower bounds; SurfaceFlinger bins are 1 ms up to 34 ms, then coarser)"
    say "  raw: $f"
    cmd_perf --since-secs "$secs" || true
}

# -------------------------------------------------------------------- perf ----

cmd_perf() {
    local since=""
    [ "${1:-}" = "--since-secs" ] && since=$2
    hdr "perf: PerfStats lines from logcat (perf_log_interval > 0 in config.ini [Debugging])"
    local lines
    lines=$(adb logcat -d -v epoch -s CitraNative:I | tr -d '\r' | grep "perf: game_fps" || true)
    if [ -z "$lines" ]; then
        say "  <none>"
        return 0
    fi
    if [ -n "$since" ]; then
        local cutoff
        cutoff=$(( $(now_ms) / 1000 - since ))
        lines=$(printf '%s\n' "$lines" | awk -v c="$cutoff" '$1 >= c')
    fi
    printf '%s\n' "$lines" | sed -E 's/^ *([0-9]+)\.[0-9]+ +[0-9]+ +[0-9]+ +I CitraNative: \[ *([0-9.]+)\].*perf: /  t=\2s /' | tail -n 30
    printf '%s\n' "$lines" | sed -E 's/.*perf: //' | awk '
        { for (i = 1; i <= NF; i++) { split($i, kv, "="); v = kv[2]; gsub(/[%ms]/, "", v); sum[kv[1]] += v; cnt[kv[1]]++ } }
        END { printf "  mean over %d samples:", cnt["game_fps"]; for (k in sum) printf " %s=%.2f", k, sum[k] / cnt[k]; printf "\n" }'
}

# ---------------------------------------------------------------- exitinfo ----

exitinfo_entries() {
    # One line per exit record: "<timestamp> pid=<pid> reason=<...> [sub=<...>] <description>"
    ash dumpsys activity exit-info "$PKG" | tr -d '\r' | awk '
        function grab(re) { return match($0, re) ? substr($0, RSTART, RLENGTH) : "" }
        /^ *ApplicationExitInfo #/ { if (cur != "") print cur; cur = "" }
        /^ *timestamp=/ { cur = substr(grab("timestamp=[^ ]+ [^ ]+"), 11) " " grab("pid=[0-9]+") }
        /^ *process=/ { cur = cur " " grab("reason=[0-9]+ \\([^)]*\\)"); s = grab("subreason=[0-9]+ \\([^)]*\\)"); if (s != "") cur = cur " " s }
        /^ *importance=/ { cur = cur " " grab("rss=[^ ]+") " " grab("state=[a-z]+"); d = $0; sub(/.*description=/, "", d); sub(/ state=.*/, "", d); cur = cur " desc=\"" d "\"" }
        END { if (cur != "") print cur }'
}

cmd_exitinfo() {
    case "${1:-}" in
        save)
            local f; f=$(raw "exit-info-${2:-$(date +%H%M%S)}.txt")
            exitinfo_entries > "$f"; say "$f" ;;
        diff)
            hdr "exit-info: new records since $2"
            local new; new=$(grep -Fxv -f "$2" "$3" || true)
            [ -n "$new" ] && printf '%s\n' "$new" | sed 's/^/  /' || say "  <no new exit records>" ;;
        *)
            hdr "exit-info: $PKG (newest first)"
            exitinfo_entries | head -n "${1:-8}" | sed 's/^/  /' ;;
    esac
}

# -------------------------------------------------------------- simpleperf ----

cmd_simpleperf() {
    local secs=${1:-10} freq=${2:-500} pid f
    pid=$(require_pid)
    hdr "simpleperf: pid $pid, ${secs}s at ${freq} Hz cpu-clock"
    ash "simpleperf record -p $pid -e cpu-clock -f $freq --duration $secs -o /data/local/tmp/perf.data" 2>&1 | tr -d '\r' | tail -3
    f=$(raw "simpleperf-$(date +%H%M%S).txt")
    ash "simpleperf report -i /data/local/tmp/perf.data --sort comm,dso,symbol -n" 2>&1 | tr -d '\r' > "$f"
    grep -q "Samples:" "$f" || { cat "$f"; die "simpleperf failed; is <profileable android:shell=\"true\"/> in the manifest?"; }
    grep -m1 "Samples:" "$f" | sed 's/^/  /'
    hdr "top symbols"
    awk 'NR > 1 && /^[ 0-9.]+%/' "$f" | head -n "$TOP" | sed 's/^/  /'
    hdr "cpu share per thread"
    ash "simpleperf report -i /data/local/tmp/perf.data --sort comm -n" 2>&1 | tr -d '\r' | awk 'NR > 1 && /^[ 0-9.]+%/' | head -n "$TOP" | sed 's/^/  /'
    say "  raw: $f"
}

# -------------------------------------------------------------------- soak ----

cmd_soak() {
    local play_s=${1:-300} wait_min=${2:-25} cpu_s=${CPU_WINDOW:-60}
    cmd_identity
    local before; before=$(cmd_exitinfo save before)
    cmd_exitinfo 3
    local pid
    if [ -z "$(pid_of)" ]; then
        hdr "soak: launching"
        launch_intent
        sleep 8
    fi
    pid=$(require_pid)
    hdr "soak: playing ${play_s}s (pid $pid)"
    ash sleep "$play_s"
    cmd_mem "foreground"
    hdr "soak: HOME"
    ash input keyevent KEYCODE_HOME
    sleep 20
    cmd_cpu "$cpu_s"
    cmd_mem "home-screen-on"
    hdr "soak: screen off"
    ash input keyevent KEYCODE_SLEEP
    sleep 20
    cmd_cpu "$cpu_s"
    local remaining=$(( wait_min * 60 - cpu_s - 20 ))
    if [ $remaining -gt 0 ]; then
        hdr "soak: waiting ${remaining}s more with the screen off"
        ash sleep "$remaining"
    fi
    cmd_cpu "$cpu_s"
    cmd_mem "screen-off-${wait_min}min"
    hdr "soak: wake + relaunch"
    local t_wake; t_wake=$(now_ms)
    ash input keyevent KEYCODE_WAKEUP
    sleep 1
    ash wm dismiss-keyguard || true
    sleep 2
    launch_intent
    sleep 8
    local after; after=$(cmd_exitinfo save after)
    cmd_exitinfo diff "$before" "$after"
    hdr "soak: resumed in place?"
    local newpid restarts
    newpid=$(pid_of)
    restarts=$(adb logcat -d -v epoch -s CitraNative:I | tr -d '\r' | awk -v t="$t_wake" '$1 * 1000 >= t' | grep -cE "Azahar starting|Cleaning up process" || true)
    if [ "$newpid" = "$pid" ] && [ "${restarts:-0}" = 0 ]; then
        say "  YES: same pid $pid, no 'Azahar starting'/'Cleaning up process' after wake"
    else
        say "  NO: pid before=$pid after=${newpid:-<none>}, restart log lines after wake=$restarts"
    fi
    cmd_mem "after-relaunch"
}

# -------------------------------------------------------------------- main ----

usage() { sed -n '8,20p' "$0"; exit 1; }

[ $# -ge 1 ] || usage
adb get-state >/dev/null 2>&1 || die "no device (set ANDROID_SERIAL)"
cmd=$1; shift
case "$cmd" in
    identity) cmd_identity "$@" ;;
    cpu) cmd_cpu "$@" ;;
    mem) cmd_mem "$@" ;;
    boot) cmd_boot "$@" ;;
    frames) cmd_frames "$@" ;;
    perf) cmd_perf "$@" ;;
    exitinfo) cmd_exitinfo "$@" ;;
    simpleperf) cmd_simpleperf "$@" ;;
    soak) cmd_soak "$@" ;;
    *) usage ;;
esac
