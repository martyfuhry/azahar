# Swarm contract (read fully before doing anything)

Repo: /home/martyfuhry/Development/azahar. Integration branch: `thor/main` (checked out in the main tree; never modify tracked files there yourself). Read `CLAUDE.md`, then `docs/fork/improvement-plan.md` (your item IDs refer to it), then `docs/fork/android-kill-root-cause.md` if your item touches lifecycle.

## Worktree
```
cd /home/martyfuhry/Development/azahar
git worktree add .claude/worktrees/<agent-name> -b <branch> thor/main
cd .claude/worktrees/<agent-name>
git submodule update --init --recursive   # required; takes a few minutes
```
Commit in the fork's style (see CLAUDE.md), one logical change per commit, license header years extended to 2026, clang-format on every C++ file you touch (`clang-format -i`), ktlint style for Kotlin (no trailing whitespace, lines <= 100). Never push. Never touch `spike/*`, `fix/clock-resync-on-resume`, or `master`.

## Builds: shared machine, hard limits
- ccache is NOT installed (no sudo). Do not try to install it.
- Host C++ (tests-only) build config, inside your worktree:
  ```
  cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DENABLE_QT=OFF -DENABLE_SDL2=OFF \
    -DENABLE_WEB_SERVICE=OFF -DENABLE_OPENAL=OFF -DENABLE_SCRIPTING=OFF -DENABLE_ROOM_STANDALONE=OFF -DENABLE_LTO=OFF
  flock /tmp/claude-1000/-home-martyfuhry-Development-azahar/81d208bf-d196-428d-85e5-d4a76924c180/scratchpad/build-slot-A.lock \
    cmake --build build --target tests -j6        # or ...build-slot-B.lock; try A, then B
  ```
  Exactly two host builds may run at once (slot A and slot B). Always `-j6`. Use a long timeout (up to 600000 ms) and run in the background if needed. A full tests build from scratch is ~20-30 minutes.
- Android APK builds: ONE at a time, machine-wide, and never while you also hold a host build slot:
  ```
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/usr/local/lib/android/sdk
  cd src/android && flock /tmp/claude-1000/-home-martyfuhry-Development-azahar/81d208bf-d196-428d-85e5-d4a76924c180/scratchpad/apk.lock \
    ./gradlew --no-daemon --no-configuration-cache assembleVanillaRelease -Pandroid.injected.build.abi=arm64-v8a
  ```
  Output: `src/android/app/build/intermediates/apk/vanilla/release/app-vanilla-release.apk` (package `org.azahar_emu.azahar`, debug-signed). Do NOT use relWithDebInfo (installs as a separate `.debug` package). ~10 min from scratch. Verify the APK's `versionName` (aapt2 in `/usr/local/lib/android/sdk/build-tools/36.0.0/`) equals your HEAD hash before installing; if not, delete `src/android/.gradle/configuration-cache` and `src/android/app/.cxx` in your worktree and rebuild.
- Kotlin-only check without a full APK: `./gradlew --no-daemon --no-configuration-cache compileVanillaReleaseKotlin` (fast, still take apk.lock).

## Device: Galaxy Z Fold5, serial R3CW705DSTF (the lab phone; the AYN Thor is NEVER touched by agents)
- Take the device lock for the whole session you need it, release promptly:
  `flock /tmp/claude-1000/-home-martyfuhry-Development-azahar/81d208bf-d196-428d-85e5-d4a76924c180/scratchpad/device.lock <your script>`
  (or `flock -w 1800 ...`). Do not hold it while building.
- `export ANDROID_SERIAL=R3CW705DSTF`. Package `org.azahar_emu.azahar`; user dir `/sdcard/azahar` (config at `/sdcard/azahar/config/config.ini`, log `/sdcard/azahar/log/azahar_log.txt`, states `/sdcard/azahar/states/`). ROM: `/storage/emulated/0/roms/3ds/acnl.3ds` (Animal Crossing New Leaf). Launch:
  `adb shell am start -a android.intent.action.VIEW -d file:///storage/emulated/0/roms/3ds/acnl.3ds -t application/octet-stream -n org.azahar_emu.azahar/org.citra.citra_emu.activities.EmulationActivity`
  Re-sending that intent to a running instance restarts the game (onNewIntent); `am force-stop` first if you want a cold start.
- Install: `adb install -r -d -t <apk>`. ALWAYS confirm identity before measuring: `adb shell dumpsys package org.azahar_emu.azahar | grep versionName` and the `Azahar Version:` line in the log must match the build you think you installed. When you are done, reinstall the baseline APK `/home/martyfuhry/Development/azahar-builds/azahar-thor-main-b8aa5f893.apk` unless the coordinator says otherwise.
- Config on the phone already has `autosave_mode = 2`. Screenshots need `screencap -d <display id>` (see `dumpsys SurfaceFlinger --display-id`). `adb shell kill` is not permitted; use `am force-stop`.
- The phone may be unplugged at any time; if adb loses it, report what you have and stop.

## Proof
Every code change ships with before/after numbers using the metric named in the plan (D-1..D-7 on device, H-A/H-B on host), same conditions, median of 3 where cheap, in the commit body and in your final report. If the metric does not move, say so; do not argue it. Full `tests` binary must pass. Final report: branch, worktree path, commits, before/after table, exact commands, caveats, and what remains.

## Roster (wave 0 + wave 1, started 2026-09-08 ~14:00)

| Agent | Branch | Worktree | Items |
|---|---|---|---|
| agent-harness-host | `harness/host-bench` | `.claude/worktrees/harness-host` | H-1 (no ccache), H-2 |
| agent-harness-device | `harness/device-kit` | `.claude/worktrees/harness-device` | H-3, C-2, §2.3 baseline |
| agent-audio | `fix/audio-pause` | `.claude/worktrees/audio` | B1, B2, B4 |
| agent-vk-memory | `perf/vk-upload-ring` | `.claude/worktrees/vk-memory` | M-1 |
| agent-vk-frame | `perf/vk-frame` | `.claude/worktrees/vk-frame` | G-2, G-4 |
| agent-vk-lifecycle | `fix/vk-surface` | `.claude/worktrees/vk-lifecycle` | R-1, R-3, #11, cheap half of R-2 |
| agent-lifecycle-kotlin | `fix/android-lifecycle` | `.claude/worktrees/lifecycle-kotlin` | B3, R-5, R-6, R-9, R-10 |

Resuming after a cutoff: `git worktree list`, then per branch `git log thor/main..<branch> --stat` and `git -C <worktree> status` to see committed vs in-flight work. Re-spawn an agent with the same item IDs and the branch as its starting point. Release target: merge into `thor/main` in the order vk-surface, android-lifecycle, audio-pause, vk-upload-ring, vk-frame, harness branches; build one APK; tag `thor-v1-rc1`.
