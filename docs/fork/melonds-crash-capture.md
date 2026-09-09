# Capturing a melonDS crash on the AYN Thor

How to make the next melonDS crash diagnosable, and what it costs Marty when it happens.

Written 2026-09-09, after `2026-09-09-thor-diagnostics.md` established that the eight
August crashes are **unrecoverable** — DropBox keeps about three days, the crashes were
eleven days old, and Argosy 2.15.0 crashing thirty times in three days had churned both the
DropBox ring and the 32-slot tombstone ring past everything else.

That note's closing recommendation was to spend nothing more on the melonDS fork until a
fresh crash is captured. This is the apparatus for capturing it.

---

## 1. Verdict: the current fork APK **is** diagnosable

`melonds-thor-c86e8147.apk` can be fully symbolicated today, down to function names and
source line numbers. Nothing had to be rebuilt.

The initial read — that `merged_native_libs` held only an unrelated CameraX helper — was
wrong, for two understandable reasons. The library is called
**`libmelonDS-android-frontend.so`**, not `libmelonDS.so`; and it sits in that directory
beside `libimage_processing_util_jni.so` and `libsurface_util_jni.so`, two genuine
AndroidX/CameraX helpers, so a glance at the directory can read as "nothing of ours here".

What is actually true of the `gitHubThorRelease` variant:

| Intermediate | |
|---|---|
| `merged_native_libs/gitHubThorRelease/.../lib/arm64-v8a/libmelonDS-android-frontend.so` | 60 MB, **unstripped**, `.symtab` (49,535 entries) + `.debug_info` + `.debug_line` |
| `cxx/RelWithDebInfo/<hash>/obj/arm64-v8a/libmelonDS-android-frontend.so` | the same binary from CMake |
| `native_symbol_tables/.../libmelonDS-android-frontend.so.sym` | 8 MB, symbol table only |
| `stripped_native_libs/.../lib/arm64-v8a/libmelonDS-android-frontend.so` | 5.5 MB, what ships |

Gradle strips only on the way into the APK. **The unstripped copy is kept automatically**,
so this build type already emits what we need and the honest action was to archive rather
than rebuild.

### Proof, by build id rather than by filename

Matching on name, size or timestamp proves nothing; two builds minutes apart produce
identically named libraries with different code. The check that counts:

```
readelf -n <lib> | grep 'Build ID'
```

| | `arm64-v8a` | `x86_64` |
|---|---|---|
| `.so` inside `melonds-thor-c86e8147.apk` | `3c198eb003d3f6542d01a0a88d307ccdd41031c6` | `5dac40b7974feef34c751f931325cc42d9a1c593` |
| Archived unstripped library | **same** | **same** |

And the stripped intermediate is **byte-identical** to the APK's copy — sha256
`375cc25ab8aad92f28e9ddf58d374f8ac17265658d6314650d3739846ffae367` — which confirms the
unstripped file is the same link, not merely a similar one.

Verified end to end against a synthetic tombstone: addresses resolve to
`Java_me_magnum_melonds_impl_emulator_EmulatorMessageQueue_closeMessagePipe` at
`app/src/main/cpp/EmulatorMessageQueueJNI.cpp:110`. Both the plain `/lib/arm64/` mapping
form and the `base.apk!/lib/arm64-v8a/` form the Thor will actually produce
(`extractNativeLibs=false`) symbolicate correctly.

### The archive

`~/Development/azahar-builds/melonds-symbols-c86e8147/` — unstripped libraries for both
ABIs, the stripped copy for comparison, `BUILDID.txt`, `SHA256SUMS`, and a README.

**Do not `clean` `~/Development/melonds-android`, and do not rebuild that variant, while
this APK is the installed one.** Those intermediates are the source of the archive; a
rebuild replaces them with a **different build id** and the shipped APK's crashes become
unsymbolicatable forever. This is not hypothetical — it is exactly how the Azahar rc1 crash
of 2026-09-08 lost frame #03, and that note had to record the frame as inferred rather than
proven.

---

## 2. What to install

```
adb -s 7f87e972 install -r ~/Development/azahar-builds/melonds-thor-c86e8147.apk
```

| | |
|---|---|
| Package | `me.magnum.melonds.thor` |
| Label on the launcher | **melonDS Thor** |
| versionName / versionCode | `2.0.1 GH (THOR)` / 41 |
| Signing | Android debug key |
| Coexistence | different package id from stock `me.magnum.melonds`, so **both stay installed** |

Keeping stock melonDS installed is deliberate. If the fork crashes too, the two histories
side by side in `exit-info` are evidence; and if the fork turns out worse, he has not lost
the app he plays.

One consequence worth knowing: the APK is a release build, so it is **not debuggable**, and
`adb shell run-as me.magnum.melonds.thor` will not work. That closes the easy route to the
app's private `files/` directory — see §4 for the two ways around it.

---

## 3. The one command to run after a crash

```
export ANDROID_SERIAL=7f87e972
cd ~/Development/azahar
tools/thor/catch-melonds-crash.sh
```

That is the whole procedure. It takes a few minutes, mostly the bugreport; add
`--no-bugreport` to make it seconds.

The script is **read-only** against the device: `dumpsys`, `logcat`, `bugreport`,
`ls`/`cat`, `pull`, and nothing else. It never installs, launches, force-stops, or changes a
setting. It refuses to run when several devices are attached unless `ANDROID_SERIAL` names
one, because a capture that silently describes the wrong phone is worse than no capture.

It writes a timestamped directory:

| | |
|---|---|
| `00`–`01` | capture identity; every installed `melon*` package with version and update time |
| `02` | `dumpsys activity exit-info` per package — the record *that* a crash happened |
| `03`–`04` | `dumpsys dropbox`, and `--print` for `data_app_native_crash`, `SYSTEM_TOMBSTONE`, `data_app_crash`, `data_app_anr` — where the *stack* lives |
| `05` | `logcat -b crash -d`, plus the filtered main buffer (`F/DEBUG`, `EmulatorRecovery`) |
| `06` | the app's own `files/emulator-recovery/` journal — `session.json`, `journal.jsonl` |
| `07` | any `melonds-diagnostics-*.zip` found on `/sdcard`, unpacked |
| `08` | `adb bugreport` and the tombstones extracted from it |
| `09` | symbolicated stacks |
| `SUMMARY.md` | what was found — and **whether the stack was already gone** |

### It distinguishes the three outcomes, and says which

- **A stack was captured** — symbolicated frames in `09-symbolicated/`.
- **THE STACK IS ALREADY GONE** — `exit-info` still records `reason=5 APP CRASH (NATIVE)`,
  but DropBox and the tombstone ring no longer hold it. The summary says so in as many
  words, rather than reporting an empty capture as if nothing had happened. This is the
  August situation, and nothing can undo it.
- **No melonDS crash is recorded at all** — either it has not crashed, or the wrong device
  was captured.

### Symbolication, and the one way it could lie

Two passes run over every captured file that mentions the library:

1. `ndk-stack -sym`, which groups frames per crash but **only recognises blocks carrying
   the `*** ***` tombstone header** — it silently produces nothing for logcat-prefixed
   stacks, which is a quiet failure worth knowing about.
2. A per-frame `llvm-symbolizer` pass that does not care about the surrounding format, and
   is what actually resolves the `logcat -b crash` copy.

Before believing either, the script compares the **build id in the crash record** against
the archive's. On a mismatch it does not quietly produce a worse answer — it produces a
*fabricated* one, so both `SUMMARY.md` and the top of every stack file open with a warning
that the names and line numbers below are fiction, and the raw addresses are what should be
reported instead.

### `--root`, if the tombstone is what matters

The Thor is an `eng` build (`eng.Thor.20260206.163241`), so `adb root` works and opens
`/data/tombstones` and `/data/data` directly. It is **off by default** because it restarts
adbd and is the one thing in the script that changes device state. Add `--root` when the
DropBox copy has aged out but the tombstone ring might still hold the file:

```
tools/thor/catch-melonds-crash.sh --root
```

---

## 4. Getting melonDS's own diagnostics out

The fork inherits PR #1666's recovery machinery, which keeps its own record in
`/data/data/me.magnum.melonds.thor/files/emulator-recovery/`:

| File | |
|---|---|
| `session.json` | the session record — schema 3, including `stopReason` and `automaticRecoveryAttempted` |
| `journal.jsonl` | append-only event log: `session_started`, `device_sleep_started`, `checkpoint_committed`, `emulator_stopped`, … |
| `journal.jsonl.old` | rotated once the journal passes 512 KiB |
| `checkpoint-<millis>.mln` | the committed savestate, sha256-verified against `session.json` |

Because the APK is a release build, `run-as` cannot read that directory. Two ways in:

**Ask him to export it.** After a crash, melonDS shows *"Emulation session ended
unexpectedly"*. The **Export diagnostics** button is behind **More options** when a
checkpoint exists, and is the direct neutral button when there is not. It writes a ZIP —
suggested name `melonds-diagnostics-<epoch-ms>.zip` — through the system file picker, so
**he chooses where it lands; ask him to save it to Downloads**, which is where the script
looks. The ZIP contains `summary.json`, `journal.jsonl`, `session.json` and, on API 31+,
`exit-trace.bin`.

`exit-trace.bin` is worth knowing about: it is Android's **own native tombstone**, in
protobuf form, obtained from `ApplicationExitInfo.getTraceInputStream()`. That is the same
data DropBox holds, but retrieved by the app itself, which makes it the one existing path
by which a tombstone escapes the three-day window. Decoding it needs AOSP's `pbtombstone`;
the DropBox text copy remains the easier read when it is still there.

**Or use `--root`.** On this device that reads the directory directly and needs nothing
from him.

---

## 5. How to make a crash count

Four things, in order of how much they matter.

**Report it within three days.** This is the whole constraint. DropBox retention on this
device is the AOSP default of roughly three days (`dropbox_age_seconds` is unset), and the
tombstone ring holds 32 files. A crash reported on day four is a crash we know happened and
can never explain — which is precisely the position the August crashes left us in. There is
no way to extend the window after the fact.

**Do not reboot first.** A reboot does not clear DropBox, but it does clear logcat's ring
buffers, so the `logcat -b crash` copy of the stack — often the quickest one to read —
disappears. The script warns when the device booted less than an hour ago. If he has
already rebooted, still run it; DropBox and the tombstones usually survive.

**A crash flood from another app evicts the record.** Both rings are shared across the whole
device, so an app crashing repeatedly pushes everything else out. That is not a theory: it
is what destroyed the melonDS evidence. Argosy 2.15.0 crashed **30 times in three days**
(`art::Runtime::Abort` → `AssertNoPendingException` → `art::JNI::FindClass`) and its
tombstones filled all 32 slots on 2026-09-09 between 09:02 and 09:12. He updated to **2.15.1**
at 09:13, minutes after that last burst, so this should now be better — but it is worth
knowing that if Argosy starts crashing again, the melonDS record has a shelf life measured
in hours rather than days.

**Note what he was doing.** The device has two internal panels and `Screen-2` carries
`FLAG_PRESENTATION`, so melonDS's `ExternalPresentation` path is live on this hardware.
Whether the crash followed a lid close, a sleep/wake, a Home press, or happened mid-play
narrows the search a great deal — the stack alone will not say.

### What we are looking for, and why one crash may not settle it

Eight crashes across **three different fatal signals** — 6× SIGSEGV, 1× SIGTRAP, 1× SIGABRT
— is the signature of memory corruption or a use-after-free, not of one null dereference at
one line. Corruption crashes where the damage was done, not where it was caused, so the
first stack may point at innocent code. It is still worth far more than nothing: it names a
subsystem, and the SIGABRT in particular may carry an abort message that says exactly what
was detected. If we get two stacks that disagree, that disagreement is itself the finding.

---

## 6. What the fork already does for him if it crashes

Stated precisely, because the recovery feature does not do quite what "autosave" suggests.

The fork carries upstream PR #1666's session recovery. On the next launch after an abnormal
exit, melonDS reads Android's own `ApplicationExitInfo`, recognises `REASON_CRASH_NATIVE`,
and shows a dialog — *"Emulation session ended unexpectedly"* — rather than dropping him back
at the ROM list with no explanation. Whether that dialog can also offer to **restore** state
depends entirely on how he was interrupted; see the table below. It also fixes a
save-corruption bug in that PR (`dfc6a854`, review finding B1),
where a stale checkpoint stayed restorable after the device woke — and because restoring a
savestate writes its cart SRAM back over the game's `.sav`, that could have overwritten real
progress with old data. That fix matters more than the recovery prompt does.

**What the checkpoint is not** is a periodic autosave, and this needs saying plainly because
it changes the answer for the crashes Marty actually gets.

There is exactly one place in the codebase that commits a checkpoint:
`EmulatorViewModel.prepareForDeviceSleep()` (line 545), reached only from
`EmulatorActivity.onPause()` and only inside `if (isScreenOff() && …)` (line 1276). An
ordinary app switch or Home press takes the `else` branch and writes **no** checkpoint.
There is no timer, no `WorkManager` job, and no save-on-exit setting anywhere. (Rewind is
periodic, but it is off by default and lives purely in heap buffers, so process death takes
the whole ring with it.)

And the checkpoint is **deleted on wake**, unconditionally —
`EmulatorRecoveryRepository.markDeviceSleepResumed()` calls `deleteCheckpoints()` (line 165)
and `RecoverySession.finishDeviceSleep()` nulls the filename and hash. That is correct and
deliberate: it *is* the B1 fix. A savestate carries cart SRAM, and loading one writes that
SRAM back over the `.sav`, so a stale checkpoint is a live save-corruption hazard rather
than a safety net.

The consequence:

| When it crashes | What the recovery dialog can offer |
|---|---|
| Asleep, or on the sleep/resume transition | **Restore checkpoint** — current, costs him seconds |
| **Mid-play, screen on** | **No checkpoint exists at all.** Only "Restart session" and "Export diagnostics" |

`getPendingRecovery()` gates on `session.sleeping`, so in the mid-play case
`checkpointAvailable` is false and the restore option is never shown.

**All eight of his August crashes were `importance=100`, foreground, screen-on play.** So
for the crash he actually experiences, the fork's recovery feature protects his *diagnosis*
and gives him an explanatory dialog instead of a silent disappearance — but it does **not**
protect his progress.

What protects progress mid-play is the ordinary DS save file, unchanged from stock melonDS:
the game writes SRAM, `SaveManager` debounces, and a background thread flushes the `.sav`
about **2–3 seconds after the last write** (`SaveManager.cpp:155`, polling every 100 ms). So
a crash costs him everything since his **last in-game save point**, less up to ~3 seconds of
SRAM that had not yet flushed.

So the accurate line to give him is: **a crash around sleep costs him seconds; a crash
mid-play costs whatever the game itself had not yet saved, exactly as on stock melonDS.**
"The autosave means you lose seconds, not a save" is true only of the sleep path, and
repeating it unqualified would be an overclaim about the very case he keeps hitting.

If we want that to be true mid-play too, the change is a **periodic checkpoint** — cheap in
principle, but it inherits the whole B1 hazard (any checkpoint that can be restored can
overwrite a `.sav`), so it needs the same care about when a checkpoint stops being valid.
That is a separate piece of work, not something this note assumes.

---

## 7. Should the fork install its own native crash handler?

**Recommendation: no. Do the much cheaper thing instead — persist the exit trace
automatically at next launch.** Not implemented here; this is the argument for a decision.

### What the handler would buy, and what it would not

Almost nothing in *content*. Android's tombstone already contains more than a hand-rolled
handler realistically produces: backtraces for **every** thread, registers, the full memory
map, the fault address and signal code, the abort message, and — most relevant to a
suspected use-after-free — scudo/GWP-ASan allocation metadata naming the allocation and,
when it fires, the allocating and freeing stacks. A custom handler would produce a subset.

The genuine gain is **retention**: a file in our own directory outlives Android's three days
and cannot be evicted by Argosy.

### Why a handler is a bad way to buy retention here

- **melonDS already installs a `SIGSEGV`/`SIGBUS` handler**, and it is load-bearing.
  `ARMJIT_Memory::SigsegvHandler` (`melonDS-android-lib/src/ARMJIT_Memory.cpp:199`) is the
  JIT's fastmem fault path: it services the fault, adjusts `CONTEXT_PC`, and returns. Under
  fastmem, **SIGSEGV is a routine event that fires constantly during normal play**. A crash
  handler must therefore chain correctly with it and distinguish handled faults from fatal
  ones. Install ours after the JIT's and we never see the fatal ones; install it before and
  we fire on every ordinary fastmem fault. Get the chaining wrong in the other direction and
  we displace `debuggerd` and **suppress the tombstone entirely** — leaving us strictly
  worse off than today.
- **The process is already corrupt.** That is the hypothesis the signal pattern supports. A
  handler must be async-signal-safe — no `malloc`, no stdio, no locks — and unwinding a
  corrupted stack is exactly where naive handlers hang or fault again. A hang is worse than
  a crash: no tombstone, and an ANR instead.
- **Cost.** Breakpad or Crashpad is a new native dependency, APK size, and a day or two of
  integration and testing; a hand-rolled handler is smaller but is precisely the kind of
  code that is only exercised when it is most needed.

### The cheap alternative, which gets the retention without the risk

The fork **already** reads Android's own tombstone back after the fact.
`EmulatorRecoveryRepository.addExitTrace` (line 336) pulls
`ApplicationExitInfo.traceInputStream` — the native tombstone in protobuf on API 31+ — and
writes it as `exit-trace.bin`. The only limitation is that this happens **only when the user
taps Export diagnostics**.

Making that automatic is a handful of lines: on the next launch after a
`REASON_CRASH_NATIVE` exit, copy the trace into `files/emulator-recovery/` alongside the
journal. That runs in a **healthy** process, after the crash, with no signal-handler
constraints and no interaction with the JIT's handler — and it gives us exactly the property
we wanted, a tombstone whose retention is ours. It also captures crashes Marty never thinks
to report.

If that is done and still proves insufficient, the next step is **not** a crash handler but
`android:allowNativeHeapPointerTagging` / MTE or scudo hardening options to make the
corruption fail earlier and more informatively, closer to its cause.

---

## Provenance

Host-side only. Nothing was installed on or changed on the Thor; the only device contact was
`adb devices`. The script was validated against a local Android emulator (read-only
`dumpsys`/`logcat`) and against a stub `adb` fixture exercising every branch: a captured
stack, an aged-out stack, a build-id mismatch, and an unreadable recovery directory. The
lab phone was not used. Symbols archived and verified by build id, `readelf -n` and
`sha256sum`; symbolication proven with `ndk-stack` and `llvm-symbolizer` from NDK
`28.0.13004108`.

Facts about the fork's behaviour are cited to
`~/Development/melonds-android` at `c86e8147` (working tree a few docs-only commits ahead).
Crash history, retention figures and the Argosy churn come from
`baselines/2026-09-09-thor-diagnostics.md`.
