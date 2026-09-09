# thor-v1-rc3

Release candidate 3 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## Read this first

**rc2 shipped a crash and this build fixes it.** It hit the autosave resume — the moment a
boot restores your session — which is the path this fork puts you on by default, so on rc2 it
was reachable every time you launched a game. It is the crash that killed Pokémon X on the
Froakie selection screen.

**Nothing else about your setup changes.** The settings work below will not alter a single
value on your device. That is the intended outcome, not a failure; see (b).

## What this is

Everything in `thor-v1-rc2`, plus the two changes below. Upstream Azahar `master` at
`073110cb4` plus the fork's own work on the `thor/main` integration branch — the same upstream
base as rc1 and rc2. Nothing here is upstream Azahar code that has been version-bumped or
re-branded: the fork commits sit on top of an unmodified upstream tree.

Package `org.azahar_emu.azahar.thor`, label "Azahar Thor", `thor` flavour, release build,
signed with the Android debug key. Installs over rc2 and keeps its settings and user folder.
Installs side by side with an official Azahar, which is a different package.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc3.apk` |
| Built from | `5b87e77cf` (clean tree) |
| versionName | `thor-v1-rc3-thor` |
| versionCode | `33737355` |
| sha256 | `b516854aa4122d8ab40940a4a8f087d407ab37239ba81c8b25665f3bd3ae0c42` |
| Size | 50 284 391 bytes |
| ABIs | arm64-v8a, x86_64 |

`5b87e77cf` is this commit before the artifact rows above were filled in — a checksum of an
artifact cannot be committed into the tree the artifact is built from. The `thor-v1-rc3` tag is
on the amended commit, so the shipped APK's `Azahar Version:` log banner reads `5b87e77cf`
rather than the tagged commit's hash. The two trees differ only in this file; no code, resource
or build file changed between them. rc1 and rc2 were published the same way.

Unstripped symbols for this build are kept at `~/Development/azahar-builds/azahar-symbols-rc3/`,
build id `18ac6256c2bd6348346f3562a7906243f5248245`, matching the shipped `libcitra-android.so`
byte for byte — so a crash report from this build can be symbolicated exactly.

## What changed

### (a) The rc2 crash: driver objects freed under the shader workers

The abort, from the owner's device at 13:29:28 on 2026-09-09, 2.5 seconds after a launch that
booted and resumed:

    Fatal signal 6 (SIGABRT) in tid 15865 (Pipeline worker), pid 15676
    Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex'
      #07 qglinternal::vkCreateGraphicsPipelines(...)   vulkan.adreno.so
      #08 Vulkan::GraphicsPipeline::Build(bool)          libcitra-android.so

Two defects, both ours, both in `PipelineCache`.

**We freed objects while worker threads were inside the driver.** `vkDestroyPipelineCache` and
`vkDestroyShaderModule` are the two calls here that Vulkan requires to be externally
synchronised, and three paths called them with the worker pools still running:
`LoadDriverPipelineDiskCache()` reassigning the `vk::UniquePipelineCache` (the assignment is a
destroy), and `LoadDiskCache()`/`SwitchDiskCache()` destroying the `ShaderDiskCache`s that own
the pipelines and shader modules the workers had captured. Only the destructor drained the
workers — the drain was known to be necessary in one place and missing in the other three. A
destroyed mutex is not lock contention; it means the driver's own object was freed and then
locked, which is exactly this.

**And a pipeline could name a cache that no longer existed.** `GraphicsPipeline` copied the
`VkPipelineCache` **handle by value** at construction and used it much later on a worker, so a
pipeline created before a cache swap named a destroyed cache even with a perfect drain. It now
holds a reference and dereferences at build time, and member declaration order guarantees the
pipelines die before the cache.

**Why this fires on the Thor specifically, and why async compilation is not the culprit.** The
fork's own boot resume takes the path twice: `OfferAutoSaveOnBoot()` loads the autosave, whose
`System::serialize` calls `SwitchDiskResources()`, and `RunCitra()` then calls
`LoadDiskResources()` again a few slices later — a second cache load that frees the cache while
the first load's pipelines are still compiling. The Thor profile sets autosave to Always, so
the device took this path on every boot. Asynchronous shader compilation did not create the
bug: preload builds are queued regardless of that setting. What it changes is how much work is
in flight when the reload lands, because with it off the emulation thread is throttled behind
each pipeline. **The setting stays on**, which is the right answer now that the lifetime is
fixed.

Two things checked and found *not* to be problems, recorded so they are not re-opened: sharing
one `VkPipelineCache` across concurrent `vkCreateGraphicsPipelines` calls is explicitly legal,
and so is rc2's periodic cache flush reading the cache during a build. Neither parameter is
marked `externsync` in the Vulkan registry. The first theory of this crash blamed both; the
registry says otherwise.

### (b) Settings that should never have been wrong

`use_hw_shader = false` sat in the owner's `config.ini` for months next to `resolution_factor
= 4` — software vertex shading at the resolution where it costs the most — and survived every
upgrade. He found it by asking why a setting was not on by default. No measurement pass in this
project found it.

The mechanism that let it survive is the real bug. `ThorDefaults.applyOnFirstRun()` ran at most
once per install, against the profile as it stood that day; its non-forcing pass skipped any
key `config.ini` already had a value for, so a blank key was filled and an explicitly wrong one
was skipped forever; and its one-shot flag was set whether or not anything was written. Every
opinion this fork has formed since it started was therefore unable to reach the device it was
formed for.

The repair pass now runs on every launch and sorts settings into three tiers. **Tier 1** —
`use_hw_shader` and `use_cpu_jit` — is repaired even over an explicit change, because neither
has anybody for whom the other value is right. **Tier 2**, ten keys with a right answer on this
hardware but a legitimate other side, is repaired only subject to provenance: if the live value
is what we last wrote, it is ours and we update it; if it differs, you moved it and we cede the
key permanently. **Tier 3** is taste, written once and never touched again. The provenance
record lives in `fork-profile.json` beside `config.ini` rather than in app storage, so it
survives a reinstall — a record that vanished on uninstall would make every key look
"not ours" forever.

The switch that turns the whole pass off is the only escape from tier 1, so it is now recorded
in two places and read as the OR of them. It used to live only in the sidecar, where every way
that file can fail — unwritable, a rename that does not take, left unparseable, written by a
newer build — read back as "not opted out" and silently resumed repairing, with the switch
still showing off.

**On your device this build changes nothing.** You already set `use_hw_shader` back to `true`
by hand, and `use_cpu_jit` was never in your config at all, so it takes the correct default.
All ten tier-2 keys are already at the value we would pick. The entire visible effect is one
new file recording that we own those keys; `config.ini` is not modified and no notification
appears. What changes is that those two settings can never silently go wrong again, and that
the next opinion this fork forms will actually reach you.

## What is not in this build

- **The autosave loss bug.** rc2 can silently discard a valid autosave and then overwrite it —
  no crash involved. It is understood, reproduced from the owner's own logs, and being fixed on
  `fix/autosave-loss` for rc4, along with rotation so an overwrite cannot destroy the only copy
  and a configurable periodic autosave. Deferred deliberately to keep rc3 small.
- **The Argosy relaunch work.** Failed review with two confirmed critical defects: the
  replacement activity would silently resume the *previous* game instead of booting the one you
  picked, and the outgoing one still released both screen surfaces underneath it. The separate
  finding it produced is real and still open — a launcher relaunch can leave the process running
  with no foreground service — but the remedy needs a redesign, and the device logs showed both
  activity orderings occur, so it has to be correct under each.

Also in this build, not worth a section: two pre-existing ktlint violations fixed, so
`thor/main` passes the style gate cleanly again.

## Standard of evidence

**Neither change in this build was verified on hardware.** The lab phone was not attached for
any of this work, and the owner's Thor is not a test device. Both changes were reviewed against
the code and, where it mattered, against primary sources — the Vulkan registry in `externals/`
for the synchronisation rules, and AOSP for the Android lifecycle claims — but reviewed is not
measured. rc2 shipped a crash that had passed exactly this bar. The crash fix has a complete
argument behind it and the reviewer could not find another producer of the same abort
signature, but it cannot be proven to be the only one without the device.

Save in-game before installing.
