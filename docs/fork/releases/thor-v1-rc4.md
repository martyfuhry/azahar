# thor-v1-rc4

Release candidate 4 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## Read this first

**rc2 and rc3 could silently lose an autosave, with no crash involved.** This build fixes
that. If a game ever started from the beginning when you expected it to resume, this is why —
and the state was still on disk at the time, valid, and about to be overwritten.

## What this is

Everything in `thor-v1-rc3`, plus the autosave work below. Upstream Azahar `master` at
`073110cb4` plus the fork's own work on the `thor/main` integration branch.

Package `org.azahar_emu.azahar.thor`, label "Azahar Thor", `thor` flavour, release build,
signed with the Android debug key. Installs over rc3 and keeps its settings and user folder.

**Your existing autosave survives.** It is read as the first generation and the new session
writes to a different one, so it cannot be overwritten. Under rc3 it would have been.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc4.apk` |
| Built from | `1b329988c` (clean tree) |
| versionName | `thor-v1-rc4-thor` |
| versionCode | `33739240` |
| sha256 | `55955361c18b242ecfae32f61287658573055a3f85927cb853a7edc538fcfc66` |
| Size | 50 307 371 bytes |
| ABIs | arm64-v8a, x86_64 |

`1b329988c` is this commit before the artifact rows were filled in — a checksum of an artifact
cannot be committed into the tree the artifact is built from. The `thor-v1-rc4` tag is on the
amended commit. rc1 through rc3 were published the same way.

Unstripped symbols are kept at `~/Development/azahar-builds/azahar-symbols-rc4/`, build id
`2ddf430420896d27bb661a839134de7d7a541424`, matching the shipped library exactly.

## What changed

### (a) The autosave could be discarded and then overwritten

Observed on the owner's device, on rc2, with autosave set to Always:

    14:09:26  Autosave written                                          (state S)
    14:09:33  process killed
    14:09:37  Resuming from autosave written at 1788977366              (correct)
    14:09:45  killed again, 8.5 s in, before a new autosave was written
    14:09:49  Ignoring autosave from 1788977366 that predates the previous boot
    14:10:12  Autosave written        <- the cold-started session overwrote S

State S was valid and about twenty seconds old. The staleness rule — which exists for a real
reason, since silently resuming a state older than your last session would rewind you past
in-game saves you made since — is keyed on "the previous boot". So **one launch that failed to
produce an autosave poisoned a good one permanently**, and the resulting fresh-start session
then wrote over the only copy.

Three changes:

**Rotation.** Autosaves now occupy a ring of three generations per title. A session picks one
slot at boot and writes only there for its whole life, so there is no window in which a kill
catches the ring mid-rotation, and the ring holds the last three *sessions* rather than the
last three saves. A session can therefore never overwrite the state it resumed from, nor the
one you declined. Reads pick the newest generation this build can load, so a build change
costs one generation instead of the history.

**A stale state is offered, not discarded.** Under every autosave mode including Always, a
state that predates the previous boot now raises a dialog naming its age rather than being
ignored. Auto-loading it would be the rewind the rule exists to prevent; throwing it away was
never the right response.

**Periodic autosave**, as an Off / 1 / 3 / 5 / 10 / 15-minute setting, **shipping Off.** It is
the only one of the three with a runtime cost, and it only insures against a hard kill
mid-play — the rarest case, now that the other two stop a save being lost. The pause-time save
already covers the lid, Home and backgrounding, and is nearly free because the emulation
thread is parking anyway.

### (b) Savestates compress at zstd level 1 instead of 3

Where the measured win is. On the host: 346 → 273 ms for one title, 362 → 255 ms for another,
for about 7% more file. Old savestates still load; the level a frame was written at does not
affect reading it.

**A correction to the record, since it was wrong twice in this project's own notes.** Save cost
is driven by the volume of *emulated memory*, not by the size of the state file. The compressor
is fed 310-314 MB across every title measured — a spread under 1% — because that is FCRAM and
VRAM, which are fixed-size. Serialize time is correspondingly flat at 117-125 ms. A game with a
larger savestate can be *faster* to save. Earlier per-megabyte reasoning in this repository
divided by the compressed output, which is not what the work scales with.

Where the time goes: **serialize 35-41%, compress 57-63%, write 2%.** A `log_savestate_breakdown`
option prints it, off by default.

**Off-thread saving was built, measured, and reverted.** The synchronous path copies those
310 MB exactly once — the serializer writes straight into the compressor's input buffer — so
handing bytes to a worker adds three more passes over the same memory and costs about what the
compression it hides costs. Measured back to back: 250 ms synchronous, 245-278 ms threaded. It
would also have meant a savestate that is not on disk when the save call returns, which is new
surface across five code paths on the one path where a mistake means a missing save. The route
that would actually help — not serializing 300 MB of mostly zeros in the first place — is sized
and written up, not started.

## Standard of evidence

**No runtime behaviour in this build has been verified on hardware.** The device figures quoted
are from the owner's own logs on rc2/rc3, not from this build. The autosave logic is covered by
the host test suite (87 cases, 1624 assertions) and was reviewed twice; the first review found,
by executing the code rather than reading it, that a session could still overwrite the state it
resumed from when the only loadable generation was also the oldest. That is fixed, and the
regression test was demonstrated to fail without the fix.

Save in-game before installing.
