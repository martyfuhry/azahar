# thor-v1-rc8

Release candidate 8 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## What this is

**rc7 plus 82 upstream commits** (to `270c9d523`, 2026-10-29), the Kotlin port that upstream's
settings refactor made necessary, two Android start-up crashes found on the Fold5 the first time
this tree ran, and a savestate compatibility bump that the upstream changes force.

**Read this before installing: your rc7 savestates and autosaves will not load in rc8.** Save
in-game first. The reason is in the savestate section below.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc8.apk` |
| Built from | `1013d5181` (clean tree) |
| versionName | `thor-v1-rc8-thor` |
| versionCode | `34167957` |
| sha256 | `5b28a7aba37fa92927ee4ec98021eaa581e3572cc8cac669fe3e17559394b646` |
| Size | 50 876 123 bytes |
| ABIs | arm64-v8a, x86_64 |
| Upstream base | `270c9d523` |

`1013d5181` is this commit before the artifact rows were filled in; the tag is on the
amended commit, as with rc1 through rc7. Unstripped symbols are at
`~/Development/azahar-builds/azahar-symbols-rc8/`, build id
`8fc197d2e7df61a5405197e2452359f345bddba8`, matching the shipped library.

## Savestates: rc8 refuses states written by rc7 and earlier

rc7's notes explained the trade this fork makes: every fork build reports the same release
version, which is what lets an autosave carry across release candidates, and which is also why
an unversioned change to the serialized layout is dangerous here when it is harmless upstream.
rc7 handled one such change (the DSP mixer) by versioning it.

This merge brings in six more, none of them versioned upstream: new fields in the GSP service
(`right_waiters`, `deferred_switches`, `force_mode`, `gpu_core_reset_by_force`), the FS service
(program and product info maps, the gamecard path), the HTTP service (`open_contexts`), PTM
(`charge_level`), the HLE DSP's sleep state, and the entire CSND sound path, which is new.
Versioning each of them individually is not a reasonable amount of work for a merge, and a state
read with the wrong layout fails somewhere far from the cause, if it fails at all.

So the header's build-version field, which upstream fills with its release version, now carries
a **fork format epoch** (`Core::GetSaveStateFormatVersion()`, currently `thor-2`). rc7 and
earlier wrote `0` there. A state whose epoch does not match is a build mismatch: the autosave
offer skips it with the existing "written by an incompatible build" notice, and loading it from
the slot menu is refused with the same error upstream shows across its own releases. Nothing is
deleted; the files stay where they were. The epoch is bumped by hand, with the reason recorded
next to it in `savestate.cpp`, and only when the layout actually changes, so the next RC that
does not touch the layout will load rc8's states as before.

## Two Android start-up crashes, both upstream's, both fixed here

Both were found on the Fold5 on the first launch of this tree.

**The folder prompt never appeared.** Upstream's main screen initialises the multiplayer
announcer in `onCreate`, before it knows whether the data folder is usable. That reads the CFG
username off the emulated NAND, and the first read formats the config savegame and writes it
back. With no usable folder the native side falls back to `/` as its user path, the write fails,
and `ASSERT_MSG` in `Service::CFG::Module::UpdateConfigNANDSavegame` kills the app on the UI
thread. A revoked folder grant, which an app update or a keep-data reinstall can cause, became a
crash loop that only a data wipe got out of. The fork now defers that call until
`DirectoryInitialization` reports the folders ready, and repeats the attempt in `onResume` so it
still happens right after the user picks the folder.

**The app died the moment the folder was set.** Upstream's configurable battery state (#2513)
added `battery_state_source`, `battery_charging` and `battery_level` to the shared key list and
the Android config reader, but not to the Android default config template, and upstream's own
template validator asserts on the first key it cannot find. Upstream master is in this state as
of `270c9d523`; the fork declares the three keys.

## The settings refactor and what it meant for the fork

Upstream replaced the Kotlin settings API (#1856, "PR 1 of the Android Settings Overhaul"):
enum members no longer carry their own value, sections moved to `CitraSettings`, and the
per-key save and the section-aware file reader are gone. The fork's Thor defaults, graphics
presets, settings repair and first-run profile were built on that API and were ported with the
same behaviour. Two things are worth knowing:

- A graphics preset now flips to **Custom** whenever the whole configuration stops matching the
  preset, rather than only when one of its keys is edited. In the normal case that is the same
  outcome; it additionally covers the long-press "reset to default" path, which the old hook
  missed.
- Settings repair reads config.ini through a small fork-local reader instead of upstream's
  removed one, because the typed loader cannot tell "absent" from "default", which the
  provenance rules need. Upstream's `SettingsFile.kt` is untouched, so future merges of it stay
  clean.

## What upstream changed

**Android**

- **Input latency** (#2700, #2706). OpenGL frames are presented from a dedicated thread instead
  of the UI thread's Choreographer callback, HID timing was tightened, and Android performance
  hints were added. This replaced the fork's own present path.
- **Shader cache crash guard** (#2709). A flag file is written while the Vulkan pipeline cache
  loads; if the process died during the last load, the next boot warns instead of crashing the
  same way again.
- **Relaunch from a shortcut** (#2656, #2658). Upstream now ignores a relaunch of the running
  game by comparing launch URIs. The fork keeps its title-id based version, which also handles a
  launcher that sends a content URI with no game extra (Argosy); upstream's secondary-surface
  wait and fragment lifecycle fixes are in.
- **Hidden dotfiles** are skipped when scanning for games (#2362). **Turbo** resets when a game
  starts (#2657).

**Input**

- **Hat inputs** were not detecting press and release correctly (#2457). That is a gamepad d-pad
  reported as a hat, which is what the Thor's own controls are. Worth a minute in a menu.

**Audio**

- **CSND support** (#2680). The second sound path some titles use for effects, mixed into the
  DSP output. **DSP sleep** completes at the next audio frame and its timings were fixed (#2731).
- **cubeb** updated to the latest commit (#2714).

**Graphics**

- **GSP accuracy** fixes (#2678). **Color blend min/max** mode on Vulkan (#2427), **xBRZ**
  appearance on Vulkan, **proctex LUT** upload (#2627), **stride aliasing** validation (#2451),
  **stale async custom texture uploads** skipped (#2688), **Anime4K** limited to OpenGL.
- **Shader JIT** masked-write optimisations (#2597).

**Core**

- **Savestates with LLE modules** fixed, plus an RPC command for savestates (#2730).
- **Configurable battery state** (#2513), with the Android template fix above.
- **dynarmic** bumped.

**Project**

- Upstream relicensed to **GPLv3+** and converts headers only on files it touches. The fork's
  own files keep whatever header they have.

**Not relevant on this device:** free camera, bundle ROM formats, the Qt on-screen keyboard,
Windows console and theme fixes, GDB protocol work, system-library build options.

## Standard of evidence

Host: the Catch2 suite on `thor/main` (91 cases, 86 passed, 5 skipped, 1661 assertions) and the `assembleThorRelease` that
produced the artifact.

Fold5 (2026-10-29, this tree at `13a544112` with the two Android fixes, before the savestate
epoch): Animal Crossing New Leaf boots on Vulkan with hardware shaders and accurate
multiplication on; Home pauses the emulator to 0.00% CPU over a 60 s window with the foreground
service holding the process; the first Home wrote an autosave, a forced restart resumed from it,
and a second Home wrote the next generation; re-sending the launch intent brought the running
game back on the same process with audio resuming. No renderer errors or criticals in the
session log. The Vulkan disk cache rebuilt itself once because upstream changed the pipeline
layout; expect the same on the Thor, one slow start per title.

Not run on the Thor. Lid sleep, the clock resync after hours asleep, the d-pad after the hat fix,
and the real battery number while backgrounded are the Thor's to answer.

Save in-game before installing.
