#!/usr/bin/env bash
# Copyright 2026 Citra Emulator Project / Azahar Emulator Project
# Licensed under GPLv2 or any later version
# Refer to the license.txt file included.
#
# Capture everything Android knows about a melonDS native crash, before it ages out.
# Run this on the host, over adb, AS SOON AS a crash is reported. See
# docs/fork/melonds-crash-capture.md.
#
#   tools/thor/catch-melonds-crash.sh                 capture into melonds-crash-<stamp>/
#   tools/thor/catch-melonds-crash.sh --out DIR       capture into DIR
#   tools/thor/catch-melonds-crash.sh --no-bugreport  skip the slow bugreport fallback
#   tools/thor/catch-melonds-crash.sh --root          also read /data/tombstones (eng builds)
#
# The capture is READ-ONLY against the device: only dumpsys, logcat, bugreport, ls/cat and
# pull. It never installs, launches, force-stops, or changes a setting. The one exception is
# --root, which restarts adbd as root on an eng/userdebug build and is off by default.
#
# Environment: ANDROID_SERIAL (required when more than one device is attached),
# SYMBOLS (symbol archive, default ~/Development/azahar-builds/melonds-symbols-c86e8147),
# NDK (Android NDK root), OUT (same as --out).

set -uo pipefail

SYMBOLS=${SYMBOLS:-$HOME/Development/azahar-builds/melonds-symbols-c86e8147}
NDK=${NDK:-/usr/local/lib/android/sdk/ndk/28.0.13004108}
STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT:-melonds-crash-$STAMP}
DO_BUGREPORT=1
DO_ROOT=0

# Every melonDS package that has ever been on this device. The fork is the .thor one.
CANDIDATE_PKGS="me.magnum.melonds.thor me.magnum.melonds me.magnum.melonds.nightly \
me.magnum.melonds.dev me.magnum.melonds.thor.dev me.magnum.melonds.nightly.dev \
me.magnum.melondualds"

# DropBox tags that can hold a native stack.
CRASH_TAGS="data_app_native_crash SYSTEM_TOMBSTONE data_app_crash data_app_anr"

SO_NAME=libmelonDS-android-frontend.so

die() { printf 'catch-melonds-crash: %s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
hdr() { printf '\n== %s ==\n' "$*"; }
note() { printf '  %s\n' "$*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT=$2; shift 2 ;;
        --symbols) SYMBOLS=$2; shift 2 ;;
        --ndk) NDK=$2; shift 2 ;;
        --no-bugreport) DO_BUGREPORT=0; shift ;;
        --root) DO_ROOT=1; shift ;;
        -h|--help) sed -n '6,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $1 (try --help)" ;;
    esac
done

command -v adb >/dev/null || die "adb not on PATH"

# --- device selection -------------------------------------------------------
# Never guess. Two devices attached and no ANDROID_SERIAL is how a capture ends up
# describing the wrong phone.
n_dev=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
if [ "${ANDROID_SERIAL:-}" = "" ] && [ "$n_dev" -gt 1 ]; then
    adb devices -l >&2
    die "$n_dev devices attached; set ANDROID_SERIAL to the one that crashed"
fi
adb get-state >/dev/null 2>&1 || die "no device (set ANDROID_SERIAL)"

mkdir -p "$OUT" || die "cannot create $OUT"
OUT=$(cd "$OUT" && pwd)

ash() { adb shell "$@" 2>&1 | tr -d '\r'; }

# --- 00 capture identity ----------------------------------------------------
serial=$(adb get-serialno | tr -d '\r')
{
    echo "captured_at_host   $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "captured_at_device $(ash date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "serial             $serial"
    echo "fingerprint        $(ash getprop ro.build.fingerprint)"
    echo "model              $(ash getprop ro.product.model)"
    echo "android            $(ash getprop ro.build.version.release) (SDK $(ash getprop ro.build.version.sdk))"
    echo "build_type         $(ash getprop ro.build.type)"
    echo "uptime             $(ash uptime)"
    echo "symbols            $SYMBOLS"
    echo "ndk                $NDK"
} > "$OUT/00-capture-info.txt"

say "catch-melonds-crash: capturing from $serial into $OUT"
sed 's/^/  /' "$OUT/00-capture-info.txt"

# Reboot detection. A reboot does not clear DropBox, but it does mean logcat's ring
# buffers only cover the time since boot, so a crash from before it is gone from logcat.
boot_ago=$(ash cat /proc/uptime | awk '{printf "%d", $1}')
if [ -n "$boot_ago" ] && [ "$boot_ago" -lt 3600 ] 2>/dev/null; then
    REBOOT_WARN="device booted ${boot_ago}s ago - logcat cannot contain anything older"
else
    REBOOT_WARN=""
fi

if [ "$DO_ROOT" = 1 ]; then
    hdr "adb root (opt-in; restarts adbd)"
    adb root 2>&1 | sed 's/^/  /'
    adb wait-for-device
fi

# --- 01 which melonDS packages are installed --------------------------------
hdr "melonDS packages installed"
PKGS=""
installed_list=$(ash pm list packages | sed 's/^package://')
for p in $CANDIDATE_PKGS; do
    if printf '%s\n' "$installed_list" | grep -qx "$p"; then
        PKGS="$PKGS $p"
        ver=$(ash dumpsys package "$p" | grep -m1 versionName | tr -d ' ')
        upd=$(ash dumpsys package "$p" | grep -m1 lastUpdateTime | sed 's/^ *//')
        note "$p  $ver  $upd"
    fi
done
# Anything else with "melon" in the name that we did not anticipate.
for p in $(printf '%s\n' "$installed_list" | grep -i melon || true); do
    case " $PKGS " in *" $p "*) continue ;; esac
    PKGS="$PKGS $p"
    note "$p  (not in the expected list)"
done
[ -n "$PKGS" ] || note "none - nothing named melon* is installed"
for p in $PKGS; do
    ash dumpsys package "$p" > "$OUT/01-package-$p.txt"
done

# --- 02 exit-info: did Android record a crash at all? -----------------------
hdr "ApplicationExitInfo (the record that a crash happened)"
NATIVE_CRASHES=0
NEWEST_CRASH=""
for p in $PKGS; do
    f="$OUT/02-exit-info-$p.txt"
    ash dumpsys activity exit-info "$p" > "$f"
    # reason=5 is APP CRASH (NATIVE): a fatal signal in native code.
    # grep -c prints the count but exits 1 when it is zero, so swallow the status, not the number.
    hits=$(grep -c 'reason=5 ' "$f" 2>/dev/null || true); hits=${hits:-0}
    NATIVE_CRASHES=$((NATIVE_CRASHES + hits))
    if [ "$hits" -gt 0 ]; then
        note "$p: $hits native crash record(s)"
        grep -B1 'reason=5 ' "$f" | grep timestamp= | sed 's/^ */    /' | head -8
        newest=$(grep -B1 'reason=5 ' "$f" | grep -m1 -oE 'timestamp=[0-9-]+ [0-9:.]+' | cut -d= -f2-)
        [ -n "$newest" ] && [ -z "$NEWEST_CRASH" ] && NEWEST_CRASH="$newest"
    else
        note "$p: no native crash records"
    fi
done
# Also keep the unfiltered table; a SIGNALED/LOW_MEMORY kill is a different story worth seeing.
ash dumpsys activity exit-info > "$OUT/02-exit-info-all.txt"

# --- 03/04 DropBox: where the stack actually lives --------------------------
hdr "DropBox"
ash dumpsys dropbox > "$OUT/03-dropbox-index.txt"
oldest=$(grep -m1 -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+' "$OUT/03-dropbox-index.txt" || true)
entries=$(grep -m1 'Drop box contents' "$OUT/03-dropbox-index.txt" || true)
note "${entries:-unavailable}"
oldest_kept=$(grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+' "$OUT/03-dropbox-index.txt" | sort | head -1 || true)
[ -n "$oldest_kept" ] && note "oldest entry retained: $oldest_kept  (retention is ~3 days)"

DROPBOX_HITS=0
for tag in $CRASH_TAGS; do
    f="$OUT/04-dropbox-$tag.txt"
    ash dumpsys dropbox --print "$tag" > "$f"
    hit=0
    for p in $PKGS; do
        if grep -q "$p" "$f" 2>/dev/null; then hit=1; fi
    done
    sz=$(wc -c < "$f")
    if [ "$hit" = 1 ]; then
        DROPBOX_HITS=$((DROPBOX_HITS + 1))
        note "$tag: MELONDS ENTRY PRESENT (${sz} bytes)"
    else
        note "$tag: nothing for melonDS (${sz} bytes captured anyway)"
    fi
done

# --- 05 logcat -------------------------------------------------------------
hdr "logcat"
adb logcat -b crash -d > "$OUT/05-logcat-crash.txt" 2>&1
note "crash buffer: $(wc -l < "$OUT/05-logcat-crash.txt") lines"
# The main buffer carries the F/DEBUG tombstone echo and melonDS's own EmulatorRecovery tag.
adb logcat -b main -d > "$OUT/05-logcat-main.txt" 2>&1
grep -E 'DEBUG|libc |tombstone|EmulatorRecovery|melonDS|melonds' "$OUT/05-logcat-main.txt" \
    > "$OUT/05-logcat-filtered.txt" 2>/dev/null || true
note "main buffer: $(wc -l < "$OUT/05-logcat-main.txt") lines, $(wc -l < "$OUT/05-logcat-filtered.txt") relevant"
[ -n "$REBOOT_WARN" ] && note "WARNING: $REBOOT_WARN"

# --- 06 the app's own recovery journal --------------------------------------
# files/emulator-recovery/{session.json, journal.jsonl, journal.jsonl.old, checkpoint-*.mln}
hdr "melonDS recovery journal (files/emulator-recovery/)"
mkdir -p "$OUT/06-recovery"
RECOVERY_FILES=0
for p in $PKGS; do
    d="$OUT/06-recovery/$p"
    # A release build is not debuggable, so run-as fails; with --root we can read it directly.
    listing=$(ash "run-as $p ls -la files/emulator-recovery/ 2>&1")
    src="run-as"
    if printf '%s' "$listing" | grep -qiE 'not debuggable|unknown package|Permission denied|No such file'; then
        listing=$(ash "ls -la /data/data/$p/files/emulator-recovery/ 2>&1")
        src="direct"
    fi
    if printf '%s' "$listing" | grep -qiE 'Permission denied|not debuggable|unknown package|No such file'; then
        note "$p: not readable ($src) - ${listing%%$'\n'*}"
        mkdir -p "$d"; printf '%s\n' "$listing" > "$d/UNREADABLE.txt"
        continue
    fi
    mkdir -p "$d"
    printf '%s\n' "$listing" > "$d/ls.txt"
    for name in session.json journal.jsonl journal.jsonl.old; do
        if [ "$src" = "run-as" ]; then
            content=$(ash "run-as $p cat files/emulator-recovery/$name 2>/dev/null")
        else
            content=$(ash "cat /data/data/$p/files/emulator-recovery/$name 2>/dev/null")
        fi
        if [ -n "$content" ]; then
            printf '%s\n' "$content" > "$d/$name"
            RECOVERY_FILES=$((RECOVERY_FILES + 1))
            note "$p: pulled $name ($(wc -c < "$d/$name") bytes)"
        fi
    done
    # Checkpoints are savestates; record that they exist, do not pull megabytes of them.
    printf '%s' "$listing" | grep -E 'checkpoint-.*\.mln' | sed 's/^/    /' || true
done
[ "$RECOVERY_FILES" = 0 ] && note "no journal recovered - use the app's Export diagnostics instead (see below)"

# --- 07 exported diagnostics ZIPs -------------------------------------------
# "Export diagnostics" in the recovery dialog writes melonds-diagnostics-<epoch_ms>.zip
# through the system file picker, so it lands wherever Marty chose - usually Downloads.
hdr "Exported diagnostics ZIPs (melonds-diagnostics-*.zip)"
mkdir -p "$OUT/07-diagnostics"
found_zips=$(ash "find /sdcard -maxdepth 4 -iname 'melonds-diagnostics-*.zip' 2>/dev/null")
if [ -n "$found_zips" ]; then
    printf '%s\n' "$found_zips" | while read -r z; do
        [ -n "$z" ] || continue
        note "pulling $z"
        adb pull "$z" "$OUT/07-diagnostics/" >/dev/null 2>&1 && note "  ok"
    done
    for z in "$OUT/07-diagnostics"/*.zip; do
        [ -e "$z" ] || continue
        unzip -o -q "$z" -d "${z%.zip}-unpacked" 2>/dev/null && note "unpacked $(basename "$z")"
    done
else
    note "none on /sdcard - ask Marty to tap 'More options' > 'Export diagnostics' in the"
    note "recovery dialog next time, and save it to Downloads"
fi

# --- 08 bugreport fallback (tombstones) -------------------------------------
if [ "$DO_BUGREPORT" = 1 ]; then
    hdr "adb bugreport (fallback source of tombstones; this takes a few minutes)"
    adb bugreport "$OUT/08-bugreport.zip" >/dev/null 2>&1
    if [ -f "$OUT/08-bugreport.zip" ]; then
        note "$(du -h "$OUT/08-bugreport.zip" | cut -f1)"
        mkdir -p "$OUT/08-tombstones"
        unzip -o -q -j "$OUT/08-bugreport.zip" '*tombstone*' -d "$OUT/08-tombstones" 2>/dev/null || true
        nt=$(ls -1 "$OUT/08-tombstones" 2>/dev/null | wc -l)
        note "$nt tombstone file(s) extracted"
        for p in $PKGS; do
            m=$(grep -rl "$p" "$OUT/08-tombstones" 2>/dev/null | wc -l)
            [ "$m" -gt 0 ] && note "  $m mention $p"
        done
    else
        note "bugreport failed"
    fi
else
    hdr "adb bugreport"
    note "skipped (--no-bugreport)"
fi

if [ "$DO_ROOT" = 1 ]; then
    hdr "/data/tombstones (root)"
    mkdir -p "$OUT/08-tombstones-root"
    adb pull /data/tombstones "$OUT/08-tombstones-root" >/dev/null 2>&1 \
        && note "pulled $(find "$OUT/08-tombstones-root" -type f | wc -l) file(s)" \
        || note "pull failed"
fi

# --- 09 symbolication --------------------------------------------------------
hdr "Symbolication"
mkdir -p "$OUT/09-symbolicated"

EXPECTED_BUILDID=""
[ -f "$SYMBOLS/BUILDID.txt" ] && EXPECTED_BUILDID=$(awk '$1=="arm64-v8a"{print $2}' "$SYMBOLS/BUILDID.txt")
SYMDIR="$SYMBOLS/symbols/arm64-v8a"
SYMLIB="$SYMDIR/$SO_NAME"

if [ ! -f "$SYMLIB" ]; then
    note "NO SYMBOLS at $SYMLIB - cannot symbolicate (see the archive README)"
    SYMBOLICATED=0
else
    note "symbols: $SYMLIB"
    note "expected build id: ${EXPECTED_BUILDID:-<unknown>}"

    # Which build ids does the captured evidence actually attribute to OUR library?
    MELON_SEEN=$(grep -rhE "$SO_NAME" "$OUT"/0[3-8]* 2>/dev/null \
                 | grep -oE 'BuildId: [0-9a-f]{8,}' | awk '{print $2}' | sort -u)
    BUILDID_STATE="none"
    if [ -n "$MELON_SEEN" ]; then
        note "build ids seen for $SO_NAME:"
        for b in $MELON_SEEN; do
            if [ "$b" = "$EXPECTED_BUILDID" ]; then
                note "  $b  MATCH - our symbols are correct"
                [ "$BUILDID_STATE" = "none" ] && BUILDID_STATE="match"
            else
                note "  $b  MISMATCH - this is a different build; our symbols would LIE"
                BUILDID_STATE="mismatch"
            fi
        done
    else
        note "no $SO_NAME frames with a build id in the capture"
    fi

    SYMBOLICATED=0
    for f in "$OUT"/04-dropbox-*.txt "$OUT"/05-logcat-crash.txt "$OUT"/05-logcat-filtered.txt \
             "$OUT"/07-diagnostics/*-unpacked/* \
             "$OUT"/08-tombstones/* "$OUT"/08-tombstones-root/*; do
        [ -f "$f" ] || continue
        grep -q "$SO_NAME" "$f" 2>/dev/null || continue
        base=$(basename "$f")
        outf="$OUT/09-symbolicated/$base.stack"

        # A stack symbolicated against the wrong build id is not a weaker answer, it is a
        # fabricated one: every name and line number below would be invented. Say so loudly,
        # in the artifact itself, so the warning travels with the file.
        if [ "$BUILDID_STATE" = "mismatch" ]; then
            {
                echo "############################################################"
                echo "# DO NOT TRUST THE NAMES BELOW."
                echo "# The crashed library's build id does not match these symbols."
                echo "#   crashed: $(echo $MELON_SEEN)"
                echo "#   symbols: $EXPECTED_BUILDID"
                echo "# Every function and line below is therefore FICTION. Find the"
                echo "# matching unstripped library, or report the raw addresses only."
                echo "############################################################"
            } > "$outf"
        else
            : > "$outf"
        fi

        banner_lines=$(wc -l < "$outf")

        # ndk-stack first: it groups frames per crash, but needs the "*** ***" tombstone header.
        if [ -x "$NDK/ndk-stack" ]; then
            "$NDK/ndk-stack" -sym "$SYMDIR" -dump "$f" >> "$outf" 2>/dev/null || true
        fi

        # Format-agnostic fallback: symbolize every frame line ourselves. This is what
        # handles logcat-prefixed stacks, which ndk-stack silently skips.
        SYMBOLIZER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-symbolizer"
        if [ -x "$SYMBOLIZER" ]; then
            {
                echo "--- per-frame symbolication of $base ---"
                grep -oE "#[0-9]+ +pc +[0-9a-f]+ +[^ ]*$SO_NAME" "$f" 2>/dev/null \
                | while read -r frame; do
                    addr=$(printf '%s' "$frame" | awk '{print $3}')
                    printf '%s  ' "$frame"
                    "$SYMBOLIZER" --obj "$SYMLIB" --demangle --inlines --output-style=GNU \
                        "0x$addr" 2>/dev/null | head -2 | paste -sd' | ' -
                done
            } >> "$outf"
        fi

        # "Did we actually resolve anything" means lines beyond the mismatch banner.
        if [ "$(wc -l < "$outf")" -gt "$((banner_lines + 1))" ]; then
            SYMBOLICATED=$((SYMBOLICATED + 1))
            note "symbolicated $base -> 09-symbolicated/$base.stack"
        else
            rm -f "$outf"
        fi
    done
fi

# --- 10 summary -------------------------------------------------------------
STACK_FOUND=0
[ "${DROPBOX_HITS:-0}" -gt 0 ] && STACK_FOUND=1
[ "${SYMBOLICATED:-0}" -gt 0 ] && STACK_FOUND=1
if ls "$OUT"/08-tombstones/* >/dev/null 2>&1; then
    for p in $PKGS; do grep -rl "$p" "$OUT/08-tombstones" >/dev/null 2>&1 && STACK_FOUND=1; done
fi

{
    echo "# melonDS crash capture $STAMP"
    echo
    echo "Device \`$serial\`, captured $(date '+%Y-%m-%d %H:%M:%S %Z'). Read-only."
    echo
    echo "| Item | Result |"
    echo "|---|---|"
    echo "| melonDS packages installed | ${PKGS:-none} |"
    echo "| Native crash records in exit-info | $NATIVE_CRASHES |"
    echo "| Newest native crash | ${NEWEST_CRASH:-none} |"
    echo "| DropBox tags holding a melonDS entry | ${DROPBOX_HITS:-0} |"
    echo "| Recovery journal files pulled | ${RECOVERY_FILES:-0} |"
    echo "| Files symbolicated | ${SYMBOLICATED:-0} |"
    echo "| Expected build id | ${EXPECTED_BUILDID:-unknown} |"
    echo "| Build id of the crashed library | ${BUILDID_STATE:-none} |"
    [ -n "$REBOOT_WARN" ] && echo "| Reboot warning | $REBOOT_WARN |"
    echo
    if [ "${BUILDID_STATE:-none}" = "mismatch" ]; then
        echo "## WARNING: the symbols do not match the crashed binary"
        echo
        echo "The library that crashed has build id \`$(echo $MELON_SEEN)\`, but the archived"
        echo "symbols are for \`$EXPECTED_BUILDID\`. Any function name or line number in"
        echo "\`09-symbolicated/\` is **invented** and must not be reported. Find the unstripped"
        echo "library with the crashed build id, or work from the raw addresses."
        echo
    fi
    if [ "$STACK_FOUND" = 1 ]; then
        echo "## A stack was captured"
        echo
        echo "See \`09-symbolicated/\` for the resolved frames and \`04-dropbox-*.txt\` for the raw record."
    elif [ "$NATIVE_CRASHES" -gt 0 ]; then
        echo "## THE STACK IS ALREADY GONE"
        echo
        echo "Android still records that melonDS crashed natively ($NATIVE_CRASHES time(s), newest"
        echo "${NEWEST_CRASH:-unknown}), but the stack itself is no longer on the device: DropBox keeps"
        echo "roughly three days and the tombstone ring holds 32 files, so a crash older than that,"
        echo "or one followed by a burst of other apps' crashes, is unrecoverable."
        echo
        echo "Nothing can bring it back. The next crash has to be captured within three days."
    else
        echo "## No melonDS crash is recorded on this device"
        echo
        echo "\`exit-info\` holds no \`reason=5 APP CRASH (NATIVE)\` record for any melonDS package."
        echo "Either it has not crashed since the records were last rotated, or the wrong device"
        echo "was captured. Check \`00-capture-info.txt\`."
    fi
} > "$OUT/SUMMARY.md"

hdr "Summary"
sed 's/^/  /' "$OUT/SUMMARY.md"

if [ "${SYMBOLICATED:-0}" -gt 0 ]; then
    hdr "Symbolicated stack"
    head -80 "$OUT"/09-symbolicated/*.stack | sed 's/^/  /'
fi

say
say "Capture complete: $OUT"
say "Send the whole directory, or at minimum SUMMARY.md and 09-symbolicated/."
