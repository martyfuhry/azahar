# Adversarial review — melonDS `thor/main`

Repo `~/Development/melonds-android`, branch `thor/main`. Reviewed at
`abd83067` for the emulator changes, with `203168df` (build flavour) and
`9dbdfc81` (release notes) present on the tip but out of scope.

Read-only review. No device was touched, nothing was built, nothing in the
melonDS tree was modified. Everything below is derived from the source; where a
claim needs hardware to settle it, that is said explicitly rather than assumed.

## Verdict

**One blocking item, and it is not in the code written here.** It is in
upstream PR #1666 — the autosave/restore mechanism this fork exists to carry —
and it is a path where this build can destroy progress that stock melonDS would
have kept. It is a one-line fix. See **B1**.

Everything the agent wrote is sound in the ways that matter. In particular the
two things most likely to have been wrong are not:

- The **#1664/#1666 conflict resolution is complete and correct.** Diffed
  against both parents: every hunk of #1664 survives, no #1666 logic was
  deleted, the emulator-thread exit path does its cleanup exactly once, and the
  RTC sync *is* still reached on resume despite #1666's recovery early return.
- **`isLaunchTargetAlreadyRunning` cannot go wrong in the dangerous
  direction.** It uses exact URI equality, so it can never mistake a different
  ROM for the running one. Its problem is the harmless direction — it may fail
  to recognise the *same* ROM, leaving #1659 unfixed on the Thor (**S8**).

Beyond B1 there are eight things worth fixing, none of which can lose a save,
and most of which are two-line changes. The two that matter most are **S1** (the
foreground service can be resurrected without an emulator, crash in the
background doing it, and — via a chain documented there — cause a recovery
checkpoint to be discarded) and **S2** (a new path that ends in a permanently
black screen with no retry). Both are in code with no device evidence behind it.

The single most valuable thing you could do is not a code change: it is running
the three unverified tests THOR-NOTES already lists. Two of the four
agent-written commits — the entire renderer half — have no hardware evidence at
all, and `f5f448ae`'s central assumption is roughly a coin flip on the actual
Thor launcher.

---

## Blocking

### B1 — a stale checkpoint stays restorable forever, and restoring it rewrites your `.sav` with the checkpoint's older SRAM

Upstream PR #1666, not fork code. Both halves verified in source.

**Half one: melonDS savestates carry cart SRAM, and loading one writes it back
to the save file.** `melonDS-android-lib/src/NDSCart.cpp:459-489`:

```cpp
void CartRetail::DoSavestate(Savestate* file)
{
    ...
    if (SRAMLength)
    {
        file->VarArray(SRAM.get(), SRAMLength);
    }
    ...
    if ((!file->Saving) && SRAM)
        Platform::WriteNDSSave(SRAM.get(), SRAMLength, 0, SRAMLength, UserData);
}
```

`Platform::WriteNDSSave` (`app/src/main/cpp/PlatformAndroid.cpp:454-459`) →
`MelonInstance::requestNdsSaveWrite` → `SaveManager::RequestFlush` → the real
`.sav` on disk, within about two seconds. So restoring a checkpoint does not
just rewind the emulator, it **rewinds the on-disk save file**. Nothing anywhere
in the tree backs up the pre-restore `.sav`.

**Half two: the checkpoint is never invalidated when the device wakes.** The
only thing `markDeviceSleepResumed` does to the session is
`RecoveryPolicy.kt:100-102`:

```kotlin
internal fun RecoverySession.finishDeviceSleep(): RecoverySession {
    return copy(sleeping = false, sleepStartedAt = null)
}
```

`checkpointFileName`, `checkpointSha256` and `checkpointCreatedAt` are left
alone, and the `.mln` file is left on disk. Checkpoints are deleted only at
`beginRomSession`/`beginFirmwareSession`, at `markClean`, and when superseded by
a new commit (`EmulatorRecoveryRepository.kt:94, 123, 223, 253`). There is no
age cap and `session.active` has no expiry.

And `getPendingRecovery` (`EmulatorRecoveryRepository.kt:43-70`) offers the
prompt on any process-token mismatch that is not a force-stop —
`checkpointAvailable = isCheckpointValid(session)`, which checks only file
existence, the SHA-256, and `appVersionCode`. **It does not check `sleeping`,
and it does not check age.** (`sleeping` is only required for the *silent*
auto-restore path, `RecoveryPolicy.kt:60-82` — that part is correctly guarded.)

**The failure, end to end:**

1. 10:00 — screen off. Checkpoint committed. Correct behaviour.
2. 10:05 — screen on. `sleeping = false`; the 10:00 checkpoint stays on disk,
   valid and offerable.
3. 10:05–11:00 — you play. The game writes its `.sav` repeatedly. No new
   checkpoint is taken, because the screen never went off.
4. 11:00 — the process dies (LMK, native crash, ANR — the exact events this
   feature exists for).
5. Relaunch. `session.active` is still true, the process token differs, the
   cause is not a force-stop, and `isCheckpointValid` passes. You get the
   recovery dialog with **"Restore checkpoint" as the default positive button**.
6. You tap it. 55 minutes of play reverted, *and* the 11:00 `.sav` overwritten
   with the 10:00 SRAM.

Stock melonDS in step 4 loses the session but leaves the `.sav` intact and
current. This build offers you a button that destroys it. That is the one place
where the fork is strictly worse than what it replaces, which is why it is
blocking rather than a "should fix".

**Three things make it easy to walk into:**

- The dialog never says which game it means. `showRecoveryDialog`
  (`EmulatorActivity.kt:1138-1189`) builds its message from the exit cause plus
  `R.string.recovery_checkpoint_available` — *"Choose Restore checkpoint to
  continue from %1$s…"* — and never reads `prompt.session.romName`, which is
  persisted at `EmulatorRecoveryRepository.kt:81` and never used by the UI.
- The dialog never says the save file gets rolled back. The only warning string
  in the whole flow is about RetroAchievements Hardcore
  (`strings.xml:112-141`).
- **A pending recovery pre-empts an explicit ROM launch.**
  `EmulatorViewModel.initializeSession` (`:193-217`) checks recovery *before*
  `launchArgs` and discards the launch args entirely when one is pending. So
  launching *Game B* days later shows a dialog about an unnamed session, whose
  every button acts on *Game A*. (The fork's `f5f448ae` compounds this slightly:
  `onNewIntent` early-returns while the state is `RecoveryPending`, so a
  re-sent intent is dropped too.)

**Fix — one line, and it matches the design intent.** Make
`finishDeviceSleep()` clear `checkpointFileName`/`checkpointSha256`/
`checkpointCreatedAt`, and have `markDeviceSleepResumed` call
`deleteCheckpoints()`. A checkpoint is meant to cover the interval the device is
asleep; once it is awake, it has outlived its purpose. That collapses the whole
scenario.

Second-best, if you would rather keep post-wake recovery: refuse to offer a
checkpoint older than a few minutes, and compare `checkpointCreatedAt` against
the `.sav`'s mtime.

Worth doing either way, cheaply: put `session.romName` in the dialog title and a
line saying the in-game save will be rolled back too.

**If you install before fixing it**, the behavioural rule that keeps you safe is
simple: *when the recovery dialog appears, read the timestamp. If it is not from
the last few minutes, choose "Restart session", not "Restore checkpoint".*

---

## Should fix

### S1 — the foreground service can be resurrected by Android without an emulator, and may crash in the background doing it

`app/src/main/java/me/magnum/melonds/ui/emulator/EmulatorForegroundService.kt:38-42`
returns `START_STICKY`, and `:31-34` calls `showRunningNotification()` —
i.e. `ServiceCompat.startForeground(...)` — from `Service.onCreate()` with no
try/catch.

```kotlin
override fun onCreate() {
    super.onCreate()
    showRunningNotification()      // ServiceCompat.startForeground, uncaught
}

override fun onStartCommand(...): Int {
    showRunningNotification()
    return START_STICKY
}
```

Failure scenario, which is the scenario the fork was written for (though on Marty's Thor it is
not the scenario that actually cost him sessions — see
`../baselines/2026-09-09-thor-diagnostics.md`; the S1 finding below is unaffected either way):

1. Game running, `EmulatorForegroundService` foreground, user closes the lid.
2. Memory pressure; the low memory killer takes the process anyway (the service
   raises the bar, it does not make the process immortal — the measured
   `oom_score_adj=50` is better than `700`, not infinite).
3. `START_STICKY` tells ActivityManager to recreate the service. The process is
   restarted **in the background, with no activity and no emulator**.
4. `onCreate` runs `startForeground`. Two outcomes, both wrong:
   - It succeeds: the user now has a permanent "Emulation is running"
     notification and a process pinned at perceptible importance with nothing
     running in it. `stopWithTask="true"` only clears it if the task still
     exists to be swiped.
   - It throws `ForegroundServiceStartNotAllowedException` (Android 12+ blocks
     background FGS starts; whether a system-initiated sticky restart is
     exempted is not something I can establish from the source, and it is not on
     the documented exemption list). Nothing catches it, so the exception
     escapes `Service.onCreate()` and kills the process. AM retries the restart
     with backoff, so you get a short burst of background crashes on a device
     the user has just woken up.

The `start()` helper at `:88-96` does have a try/catch, and the KDoc at `:84-87`
claims "if Android refuses the start, emulation carries on without the
protection instead of taking the game down". That is true for
`Context.startForegroundService()` and false for the `startForeground()` call
inside the service, which is where Android 14+ throws
`InvalidForegroundServiceTypeException`, `MissingForegroundServiceTypeException`
and `SecurityException` as well.

**And it can cost you the recovery checkpoint.** This is the part that lifts S1
above "annoying". `RecoveryPolicy.classifyPreviousExit` (`:47-58`) picks the
*most recent* entry from `ActivityManager.getHistoricalProcessExitReasons`:

```kotlin
val exit = exits.firstOrNull { it.timestamp >= session.startedAt }
```

and `getPendingRecovery` (`EmulatorRecoveryRepository.kt:54-57`) discards the
entire recovery when that exit reads as a force-stop:

```kotlin
if (shouldDiscardRecovery(cause)) {
    markClean("android_user_exit")   // deletes the checkpoint
    return@synchronized null
}
```

Chain: LMK kills the process mid-sleep (checkpoint on disk, exactly as
designed) → `START_STICKY` resurrects a background process with an "Emulation is
running" notification for a game nobody is playing → the user, reasonably,
swipes it away or force-stops it → that writes a `USER_REQUESTED` exit record
*newer* than the LMK one → next launch, `classifyPreviousExit` sees the
force-stop, `shouldDiscardRecovery` fires, `markClean` deletes the checkpoint,
and the session the fork went to all this trouble to save is gone with no
prompt.

That is a degradation of the new feature rather than a regression against stock
melonDS — you end up where you would have been anyway — which is why this is not
in the blocking section. But it defeats the entire point of `af7ad06a` in the
one scenario `af7ad06a` was written for, and it has never been tested.

The sticky restart also runs `MelonDSApplication.onCreate`
(`MelonDSApplication.kt:35-41`: migrations, `MelonDSAndroidInterface.setup`) in
the background, a process lifecycle #1666's recovery detection was never
designed to see.

**Fix:** return `START_NOT_STICKY` (the service has no work to resume — the
activity restarts it), and wrap `showRunningNotification()` in a try/catch that
calls `stopSelf()` on failure. Two lines, and the whole chain above disappears.

### S2 — a surface can now end up with no renderer at all, permanently, with no retry

`app/src/main/java/me/magnum/melonds/ui/emulator/render/FrameRenderCoordinator.kt:184-191`

```kotlin
private fun attachRenderer(surface: EmulatorSurfaceView) {
    synchronized(surfacesLock) {
        if (!managedSurfaces.contains(surface) || !makeContextCurrent()) {
            return
        }
        surface.setSurfaceRenderer(createRendererForStrategy(currentRenderStrategy), glContext)
    }
}
```

Before `abd83067`, `addSurface` attached a renderer unconditionally on the UI
thread. Now the attach can decline. If `makeContextCurrent()` fails —
`glContext.useWithoutSurface()` throwing, which is exactly what happens when the
EGL context was lost while the GPU was suspended, i.e. the screen-off case this
whole commit is about — the message is consumed, `surfaceRenderer` stays `null`,
`EmulatorSurfaceView.doFrame` (`EmulatorSurfaceView.kt:64-77`) does nothing
forever, and nothing ever re-posts `MSG_ATTACH_RENDERER`. `addSurface` is only
called from `EmulatorActivity.onStart` (`EmulatorActivity.kt:732`) and
`ExternalPresentation` init (`ExternalPresentation.kt:71`), so the only way back
is another `onStop`/`onStart` cycle.

The same early return is in `handleModeSwitch` (`:205-214`), and there it also
leaves state inconsistent: `setRenderStrategy` (`:32-38`) has *already*
committed `currentRenderStrategy = newRenderStrategy` before posting the
message, so a failed switch can never be re-requested for that value — the
coordinator believes it is in front-buffer mode while every surface still holds
a `SurfaceBackRenderer`.

`destroySurfaces` (`:193-203`) has a milder version: on failure it returns with
`surfacesPendingRemoval` still populated and the message consumed, leaking those
renderers' FBOs/textures/HardwareBuffers until the next `removeSurface`
re-posts, or forever if the failure is a lost context.

Failure scenario: screen off long enough for the driver to reset the context;
on wake, `onStart` → `addSurface` → `attachRenderer` → `useWithoutSurface`
throws `EGL_CONTEXT_LOST` → warning logged, black screen with working sound,
permanently, and the user's only recourse is to kill the app. On a build whose
entire purpose is not losing sessions, "you must restart" is close to as bad as
a crash.

**Fix:** on `makeContextCurrent()` failure, re-post the message with a short
delay and a bounded retry count rather than dropping it; and in
`setRenderStrategy`, only commit `currentRenderStrategy` once
`handleModeSwitch` has actually applied it.

### S3 — `renderFrame`'s catch is narrower than its own comment claims

`FrameRenderCoordinator.kt:170-180`

```kotlin
try {
    MelonEmulator.presentFrame(deadline, frameRenderCallback)
} catch (e: GlContext.GlContextException) {
    // The surface can be pulled from under the render thread at any point during a resume. Dropping
    // the frame is always better than taking the process, and the session with it, down
```

Only `GlContextException` is caught. Everything else thrown anywhere inside
`presentFrame` → `frameRenderCallback.renderFrame` → `EmulatorSurfaceView.doFrame`
→ the renderer propagates out of `Handler.handleMessage` on `FrameRenderThread`
and takes the process down — which is the loss of session the comment says it
prevents.

The plausible non-`GlContextException` throwers on the resume path are
`SurfaceControl.Transaction` calls on a released control
(`SurfaceFrontRenderer.kt:121-133`, `IllegalStateException`/
`IllegalArgumentException`) and anything the emulator renderer throws from
`renderer?.drawFrame(...)`. I cannot name a guaranteed trigger from source
alone, so treat this as a defensive gap rather than a proven crash — but the
whole point of the commit is defence in depth on a path that is known to be
racy.

Related: the catch is placed *outside* the JNI boundary, so the exception
propagates through `MelonDSAndroidJNI.cpp:503`'s
`env->CallVoidMethod(...)` with the exception pending. That happens to be safe
here (no JNI call follows before `presentFrame` returns), but it means the
`managedSurfaces.forEach` loop at `:120-122` is abandoned partway — on a
dual-screen setup, one surface throwing skips the other's frame, every frame,
and skips `renderStatistics.trackRenderEvent`.

**Fix:** catch `Throwable` (log, count, back off) and move the catch inside
`frameRenderCallback.renderFrame`, around the per-surface `it.doFrame(...)`, so
one bad surface cannot starve the other.

### S4 — `EGL_CONTEXT_LOST` is detected and then nothing is done about it

`GlContext.kt:23-25, 115-121` add `isContextLost`, and
`FrameRenderCoordinator.kt:175-176` logs it. There is no recovery: no context
recreation, no user-visible message, no attempt to restart the renderer. The
rendering loop is Choreographer-paced (`ChoreographerVSyncFrameRenderer`), so it
does not busy-spin — but it will log at `ERROR` at 60 Hz forever while the user
looks at a black screen and hears the game.

The KDoc says "Emulation must be restarted to render again". Nothing tells the
user that, and nothing does it.

Also note the flag is only ever set from `consumeEglError()`, which is reached
only from `useWithoutSurface`, `use` and `createWindowSurface`. A context lost
while the *emulator's* own context is in use will not set it.

**Fix:** at minimum, surface it once — a toast or the existing recovery-prompt
mechanism — so the user knows to restart rather than assuming the app has hung.

### S5 — the RTC is written into live emulator state from an arbitrary thread, with no synchronisation

Path: `EmulatorActivity.onResume` (`EmulatorActivity.kt:838`) →
`EmulatorViewModel.syncRtcOnAppResume` (`EmulatorViewModel.kt:649-657`) →
`AndroidEmulatorManager.syncRtcToSystem` (`AndroidEmulatorManager.kt:189-191`,
`withContext(Dispatchers.IO)`) → `MelonEmulator.syncRtcToSystem` →
`MelonDSAndroidJNI.cpp:742-747` → `MelonDS.cpp:199-203` →
`MelonInstance::syncRtcToSystem` (`MelonInstance.cpp:480-483`) →
`setDateTime()` (`:713-719`) → `nds->RTC.SetDateTime(...)`.

Compare the lid-key path, which does the same thing correctly
(`MelonDSAndroidJNI.cpp:698-705`):

```cpp
Java_me_magnum_melonds_MelonEmulator_onKeyRelease(JNIEnv* env, jobject thiz, jint key)
{
    if (key != 16 + 7) {
        MelonDSAndroid::releaseKey(key);
    } else if (beginSynchronizedOperation()) {   // parks the emu thread at a safe point
        MelonDSAndroid::releaseKey(key);
        endSynchronizedOperation();
    }
}
```

The lid handler is wrapped in `beginSynchronizedOperation()`/
`endSynchronizedOperation()` specifically because `SetLidClosed` +
`setDateTime()` must not race the emulator thread. The new
`syncRtcToSystem` JNI entry point takes no lock at all, and runs on
`Dispatchers.IO` while the emulator thread is inside `MelonDSAndroid::loop()` →
`nds->RunFrame()`.

Concretely, `melonDS-android-lib/src/RTC.cpp:197-205`:

```cpp
State.DateTime[0] = BCD(year);
...
State.DateTime[6] = BCD(second);

State.StatusReg1 &= ~0x80;
```

- `State.DateTime[0..6]` is read by the emulator thread in `ReadDateTime`
  (`:139-155`) and compared in the alarm/IRQ path (`:281`, `:292`, `:303`).
  A game polling the RTC across the write gets a torn timestamp.
- `State.StatusReg1 &= ~0x80` is a read-modify-write on the same byte that the
  emulator thread read-modify-writes in `SetIRQ` (`:224-225`,
  `State.StatusReg1 |= irq`). Two unsynchronised RMWs on one byte from two
  threads is a lost update, not just a torn read: an RTC IRQ raised at that
  instant can vanish.

Two mitigations keep this off the blocking list. First, it is gated on
`isRtcSyncOnLidOpenEnabled()`, which defaults to **false**
(`SharedPreferencesSettingsRepository.kt:187-189`; the pref is
`sync_rtc_on_lid_open`, `android:defaultValue="false"` in `pref_system.xml`), so
it does nothing unless you turn it on. Second, the consequence is a wrong clock
reading or a missed RTC interrupt, not save corruption.

But note what the fork changed about *frequency*. Upstream #1664 fires this on
lid open. The fork fires it from `onResume` — every unlock, every return from
the notification shade, every dismissed dialog, every permission prompt. And on
a Thor, with the pref on (which is the whole reason to want #1664), that is
dozens of times a session.

There is also a narrower use-after-free: `MelonDS.cpp:199-203` reads the global
`instance` pointer unguarded, and `stopEmulation` →
`MelonDSAndroid::cleanup()` deletes it under `coreOperationMutex` — which the
RTC path does not take. Ending a session while an in-flight sync coroutine is
inside the JNI call is a small but real window.

**Fix:** one line — wrap the body of
`Java_me_magnum_melonds_MelonEmulator_syncRtcToSystem` in
`beginSynchronizedOperation()` / `endSynchronizedOperation()`, exactly as
`onKeyRelease` does. Until then, leaving the pref off is a complete mitigation.

### S6 — the foreground service is stopped at the top of `onDestroy`, before session teardown

`EmulatorActivity.kt:1285-1292`

```kotlin
override fun onDestroy() {
    super.onDestroy()
    EmulatorForegroundService.stop(this)
    ...
    frameRenderCoordinator.stop()
    presentation?.dismiss()
}
```

`super.onDestroy()` is what clears the `ViewModelStore` and runs
`EmulatorViewModel.onCleared()`, so any teardown coroutine launched from there
is already in flight when `stop()` drops the process out of the foreground tier.
The appendix confirms the checkpoint write is not on this path, so this is not a data-loss
bug today — but it is a fragile ordering to leave in a build whose purpose is
protecting in-flight state, and it costs nothing to move.

Separately, `onDestroy` also runs on the configuration changes that
`android:configChanges` does *not* cover. The manifest
(`AndroidManifest.xml:63-74`) handles
`keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|keyboard|uiMode`
— so rotation and fold are safe — but **not `density`**, `colorMode`, `locale`
or `fontScale`. A density or colour-mode change (plausible when docking a Thor
to an external display) destroys and recreates the activity, which stops and
restarts the service.

**Fix:** move `EmulatorForegroundService.stop(this)` to the end of `onDestroy`,
after the emulator teardown.

### S7 — a failed foreground-service start is never retried, so the protection is silently lost for the whole session

`EmulatorForegroundService.start(context)` is called from exactly two places:
`EmulatorActivity.startForegroundService()` (`EmulatorActivity.kt:701-712`,
called once from `onCreate` at `:388`) and the `POST_NOTIFICATIONS` grant
callback (`:275-278`). The `catch (e: Exception)` at
`EmulatorForegroundService.kt:90-92` swallows the failure with a `Log.w`.

So if the start is refused once — the activity being recreated while the app is
momentarily not considered foreground, a restricted app standby bucket, a
system-restored task — the process runs the entire game session at cached
priority with no indication, and the one measured benefit of `af7ad06a`
(`oom_score_adj` 50 instead of 700) is gone for that session.

**Fix:** call `EmulatorForegroundService.start(this)` from `onStart` as well.
`startForegroundService` on an already-running service is idempotent (it just
re-runs `onStartCommand` → re-posts the notification), and `onStart` is
guaranteed to be a foreground moment, so it self-heals.

### S8 — "same ROM" is raw URI equality, and the Thor path for it is untested

`EmulatorViewModel.kt:223-233`

```kotlin
is EmulatorState.RunningRom -> when (args) {
    is LaunchArgs.RomObject -> args.rom.hasSameFileAsRom(state.rom)
    is LaunchArgs.RomUri   -> args.uri == state.rom.uri
    is LaunchArgs.RomPath  -> romsRepository.getRomAtPath(args.path)?.hasSameFileAsRom(state.rom) == true
    is LaunchArgs.Firmware -> false
}
```

and `Rom.kt:21-23`:

```kotlin
fun hasSameFileAsRom(other: Rom): Boolean {
    return uri == other.uri
}
```

**The good news, which was the main thing to check: this cannot go wrong in the
dangerous direction.** Two different ROMs cannot share a URI, so it cannot
silently ignore a genuinely different ROM. Firmware sessions are handled
correctly (`RunningFirmware` only matches `LaunchArgs.Firmware` of the same
console type; a `RunningRom` never matches `Firmware` and vice versa). Every
mismatch falls through to the old confirmation dialog. There is no data-loss
risk in this commit.

The problem is the other direction — false negatives — plus a commit message
that overstates what the code does:

> The comparison uses the ROM file the running session was started from, so a
> path or a URI that resolves to the same file counts as the same game.

That is true only for `LaunchArgs.RomPath`, which resolves through
`FileSystemRomsRepository.getRomAtPath` (`:97-102`). For `RomUri` and
`RomObject` it is raw `Uri` equality. And `LaunchArgs.fromIntent`
(`LaunchArgs.kt`) maps a launcher's `intent.data` to `RomUri`, so `RomUri` is
the case that actually fires on the Thor.

Meanwhile the *launch* side resolves fuzzily:
`FileSystemRomsRepository.getRomAtUri` (`:104-128`) falls back to filename +
absolute path + file size matching, so `state.rom.uri` is the **library's**
scanned URI, not the URI the intent carried. If the Thor launcher sends
anything but the byte-identical scanned URI — a `file://` path, a
single-document URI where the library holds a tree-derived one, different
percent-encoding, a different provider authority — the comparison returns
false, the "Stop emulation and load new ROM?" dialog appears on every lid open,
and the user is one OK-tap from losing the session. That is the status quo, not
a regression, but it means #1659 may simply not be fixed on the device this was
written for.

THOR-NOTES lists this as unverified ("Re-sent launch intent while running →
must resume in place"). It is the single highest-value test remaining.

**Fix:** route `RomUri` through `romsRepository.getRomAtUri(args.uri)` — the
same resolver the launch path uses — and compare the resolved `Rom`, instead of
comparing URIs directly. Then the commit message becomes true.

Two smaller things in the same commit:

- `onNewIntent` is now asynchronous (`EmulatorActivity.kt:797-806`,
  `lifecycleScope.launch`). `getRomAtPath` calls `getRoms().first()`, which
  suspends until the ROM library flow emits. If a scan is in progress or the
  cache load stalls, the launch intent is swallowed with no dialog and no
  relaunch. Previously the dialog was synchronous and guaranteed.
- The dialog can now be shown while the activity is stopped rather than only
  while it is at least started, since the coroutine can resume after a
  background transition. `viewModel.pauseEmulator(false)` fires with it.

---

## Nits

- **N1 — `SurfaceFrontRenderer` rebuild retries every frame with no backoff.**
  `SurfaceFrontRenderer.kt:69-79` enters the rebuild path whenever
  `renderBuffers[0].hardwareBuffer == null`, and `initializeResources`
  (`:142-221`) resets that on any failure via its `catch (e: Exception)` →
  `releaseGlResources`. So while the parent `surfaceView.surfaceControl` is
  invalid but `surface` is non-null, every frame allocates and frees two
  full-resolution `HardwareBuffer`s (≈16 MB per frame at 1080p, 60 Hz). The
  window is normally a few frames, but `0b3cf4d5` adds a *new* way to enter this
  path (a generation change), so it is worth a "don't retry more than once per
  generation" guard. Ironic failure mode for a build built to avoid the LMK.

- **N2 — `surfaceView.surfaceControl` is read from the render thread.**
  `SurfaceFrontRenderer.kt:199`. The view's `surfaceLock` is held (via
  `EmulatorSurfaceView.doFrame`) but the UI thread mutates `SurfaceView`'s
  internal `SurfaceControl` without taking it. Pre-existing; the generation
  check narrows the window rather than widening it, and the `catch (Exception)`
  degrades it to N1 rather than a crash.

- **N3 — `SurfaceFrontRenderer.doFrame`'s surface-destroyed early return calls
  GL delete functions before making the context current.**
  `SurfaceFrontRenderer.kt:56-59` runs `releaseGlResources` *above* the
  `glContext.useWithoutSurface()` at `:63`. This is exactly the leak class
  `abd83067`'s commit message describes ("every `glDeleteFramebuffers` … made
  without a current context is a silent no-op"), and it is the one instance the
  commit did not fix. Pre-existing upstream, but it is now the odd one out.
  Worse than a plain leak: `glDeleteFramebuffers` is not a shared-object call —
  FBO names are per-context — so if the emulator's own context happens to be
  current, the name being deleted belongs to a different container namespace.

- **N4 — `buffer.presentFence` is closed but never nulled.**
  `SurfaceFrontRenderer.kt:106-111`. Harmless because `SyncFence.isValid`
  returns false after `close()`, but the field holds a dead object until the
  next present.

- **N5 — `handler` is now built eagerly from `looper`, which blocks.**
  `FrameRenderCoordinator.kt:95-107`. `HandlerThread.getLooper()` blocks until
  the looper is prepared, and the first accessor is the UI thread
  (`addSurface` from `onStart`, or `renderFrame` from the Choreographer). The
  thread is started in the coordinator's `init` during `onCreate`, so in
  practice this is sub-millisecond — but the fix trades "a dropped message" for
  "the main thread can block", and `getLooper()` has no timeout. Also:
  `getLooper()` returns `null` after the thread has quit, so a first access
  post-`stop()` would NPE; nothing does that today.

- **N6 — `removeSurface` on the UI thread can block behind a whole frame.**
  `FrameRenderCoordinator.kt:55-62` takes `surfacesLock`, which
  `renderFrame` (`:162`) holds for the entire duration of `presentFrame`,
  including `SyncFence.await(100 ms)` per surface in front-buffer mode. So
  `onStop` can stall the main thread for hundreds of milliseconds. Pre-existing.

- **N7 — `ExternalPresentation` re-adds its surface only in the constructor.**
  `ExternalPresentation.kt:71` (`addSurface`) is in `init`, but
  `removeSurface` is in `onStop` (`:139-142`). A Presentation that is stopped
  and restarted never gets its surface back, and `showExternalDisplay`
  (`EmulatorActivity.kt:745-748`) early-returns when the display id is
  unchanged, so it will not be rebuilt either. Pre-existing upstream; relevant
  if you dock the Thor.

- **N8 — `DisplayManager.onDisplayChanged` calls `updateDisplays()` off the UI
  thread.** `EmulatorActivity.kt:196-198`, unlike its `onDisplayAdded`/
  `onDisplayRemoved` siblings which post to the UI thread. `updateDisplays()`
  touches the view model and creates/dismisses a `Presentation`. Pre-existing,
  and live regression surface on any device that fires display-changed events.

- **N9 — `POST_NOTIFICATIONS` is fed through the shared
  `permissionRequestLauncher`.** `EmulatorActivity.kt:269-280`. The result is
  passed to `permissionHandler.notifyPermissionStatusUpdated`, which is
  null-safe for permissions it does not track (`PermissionHandler.kt:36-38`), so
  no harm. But the permission dialog now appears the first time a game is
  launched, on top of the game, and it is not registered in `activeOverlays` —
  so `onResume` will treat the app as un-overlaid and resume emulation while the
  dialog is up.

- **N10 — the notification's `PendingIntent` has no action or data.**
  `EmulatorForegroundService.kt:47-58`. That is deliberate and correct:
  `LaunchArgs.fromIntent` returns `null`, so `onNewIntent` early-returns at
  `EmulatorActivity.kt:790-791` and nothing touches the running session. Worth
  keeping the comment, because it is load-bearing.

- **N11 — release builds fall back to the debug signing key.** From
  `203168df` (out of scope, but it affects installing): a release APK built
  without a keystore in `local.properties` is signed with the debug key. Fine
  for personal use; it just means a properly-signed build later cannot upgrade
  it in place.

---

## What is *not* wrong — things checked and cleared

Worth recording, because several of these were the specific worries.

**The PR #1664 / #1666 conflict resolution is complete and correct.** This was
flagged as the most likely place for a real defect. It is clean. Diffing the
resolved cherry-picks against the original PR branch (`pr-1664` = `2a838662`,
`502459e6`) and against `pr-1666` = `549240fa`:

- `9e23ee1b` is byte-identical to `502459e6` modulo hunk offsets.
- `git diff pr-1666 abd83067` over all six conflicted files shows **only
  additions**. No #1666 logic was deleted or altered anywhere.
- Every hunk of the original `2a838662` is present. Nothing was dropped during
  conflict resolution.
- Three deliberate divergences, all benign:
  1. `AndroidEmulatorManager.syncRtcToSystem` is wrapped in
     `withContext(Dispatchers.IO)` to match #1666's house style. No behavioural
     difference (it was already off the emulator thread).
  2. `syncRtcToSystem` / `getEmulatorStatus()` ordering in the interfaces is
     cosmetic.
  3. `viewModel.syncRtcOnAppResume()` sits *after* #1666's recovery-prompt early
     return in `onResume` (`EmulatorActivity.kt:832-838`) rather than at the top
     as in #1664. **The RTC sync is still reached.** When `recoveryPrompt` is
     non-null emulation is not running, and `syncRtcOnAppResume`'s own
     `!_emulatorState.value.isRunning()` guard would have returned immediately
     anyway. No behavioural difference.

**The emulator-thread exit path is coherent, with nothing done twice or not at
all.** #1666's version won, and #1664's only change to that region was a
trailing-newline fixup, so nothing was lost. `emulate()`
(`MelonDSAndroidJNI.cpp:852-978`) breaks out of the loop under `emuThreadMutex`,
broadcasts, tears down the performance-hint session, calls
`MelonDSAndroid::stop()`, then sets `emuThreadState = Stopped` only
`if (emuThreadState != EmulatorThreadState::Stopping)` — which is exactly right,
because `stopEmulation` (`:643-676`) is the one that set `Stopping` and it sets
`Stopped` itself after `pthread_join`. Cleanup happens exactly once on both
paths. The `pthread_exit(NULL)` #1664 would have kept is gone, which is the
correct call: `pthread_exit` from the emulator thread would have skipped the
state broadcast #1666's recovery depends on.

**No lock inversion in the new render-thread code, and no deadlock.** Lock order
is consistently `surfacesLock` → the view's `surfaceLock`. The render thread
takes `surfacesLock` in `attachRenderer`/`destroySurfaces`/`handleModeSwitch`/
`stopThread`/`renderFrame` and then `surfaceLock` via
`setSurfaceRenderer`/`stop`/`doFrame`. The UI thread takes `surfaceLock` alone
(`surfaceCreated`/`surfaceChanged`/`surfaceDestroyed`) and `surfacesLock` alone
(`addSurface`/`removeSurface`). No path acquires them in the opposite order.
`stopThread` correctly does `glContext.release()`/`destroy()` *outside*
`surfacesLock`.

**The surface generation does not need to be `volatile` or atomic.** It is
written under `surfaceLock` in `EmulatorSurfaceView.surfaceCreated`
(`EmulatorSurfaceView.kt:43-52`) and read under the same monitor in
`EmulatorSurfaceView.doFrame` (`:64-77`), together with `surface` itself, so the
pair is always consistent. The monitor supplies the happens-before edge.

**The generation logic has no missed or double rebuild, and the first frame is
correct.** In `SurfaceBackRenderer` (`:33-53`) I walked all four orderings of
destroy/create against a frame boundary and each ends with exactly one
`destroyWindowSurface` and one `createWindowSurface`. `lastSurfaceGeneration`
starts at `-1` against a counter that starts at `0` and is pre-incremented, so
the first `surfaceCreated` yields `1` and the `windowSurface == null` branch
handles it without a spurious destroy. `stop()` resets it to `-1` (`:78-87`).
`SurfaceFrontRenderer` (`:69-79`) covers the first frame via
`renderBuffers[0].hardwareBuffer == null`, and always calls
`releaseGlResources` before `initializeResources` — old resources are destroyed
before new ones are created in every case.

**Message ordering cannot attach a renderer to a destroyed surface.**
`addSurface` removes the view from `surfacesPendingRemoval` and adds it to
`managedSurfaces` *synchronously* under `surfacesLock` before posting
`MSG_ATTACH_RENDERER`, and `attachRenderer` re-checks
`managedSurfaces.contains(surface)` on the render thread. A
`removeSurface` racing in between takes the view back out, so the attach becomes
a no-op. Conversely a `MSG_DESTROY_SURFACES` still queued ahead of the attach
finds an empty pending list and does nothing. `requestSurfaceDestruction`'s
`removeMessages` dedup does not drop an intervening attach because the two use
different `what` values.

**`abd83067` incidentally fixes a first-frame bug nobody mentioned.**
`MelonDSAndroidJNI.cpp:479` does `EGLDisplay currentDisplay = eglGetCurrentDisplay()`
before `eglWaitSyncKHR`/`eglCreateSyncKHR`. Before this commit the render thread
had no current context until the *first* `SurfaceBackRenderer.doFrame` called
`glContext.use(...)` — which happens inside the callback, after `eglWaitSyncKHR`
had already run against `EGL_NO_DISPLAY`. Now `attachRenderer` makes the context
current on the render thread before any frame, and EGL current-context is sticky
per thread, so the fence sync works from frame one.

**Making the context current on the render thread is genuinely correct, not just
different.** `useWithoutSurface`/`use` are now called only from
`SurfaceFrontRenderer.doFrame`, `SurfaceBackRenderer.doFrame` and
`FrameRenderCoordinator.makeContextCurrent`, all on `FrameRenderThread`. An
EGLContext can only be current on one thread at a time; there is no remaining
UI-thread caller, so the old silent-no-op leak really is closed.

**The foreground service does not thrash on rotation or fold.**
`android:configChanges` covers `orientation|screenSize|screenLayout|smallestScreenSize|uiMode`
(`AndroidManifest.xml:66`), so `EmulatorActivity` is not recreated for any of
them and the `onDestroy`-stop/`onCreate`-start cycle does not run. (`density`
is the gap — see S6.)

**No notification id or channel collision.** The service uses id `1000` on
`channel_emulation`; the only other notifications in the app are id `100`
(`CheatImportWorker`) and `200` (`RetroAchievementsSubmissionWorker`), both on
`channel_cheat_importing`. Two FGS notifications can be visible at once if a
cheat import runs during emulation, which is cosmetic.

**POST_NOTIFICATIONS denial does not disable the protection.** The service is
started unconditionally and `ServiceCompat.startForeground` is called regardless;
the permission only governs whether the notification is visible. That part of
`af7ad06a`'s commit message is accurate.

**No non-Thor regressions found in the four commits.** Grepping every added line
across `af7ad06a`, `f5f448ae`, `0b3cf4d5` and `abd83067` for
`gamepad|controller|inputdevice|display|presentation|secondary|external|joystick|motionevent`
returns zero matches — nothing added assumes a gamepad or a second display. All
API-33+ surfaces are guarded: `SurfaceFrontRenderer` is `@RequiresApi(TIRAMISU)`
and additionally gated by `FrontBufferCapability.isFrontBufferSupported()`
(SDK check, `EGL_ANDROID_get_native_client_buffer` extension query, and a
`HardwareBuffer.isSupported` probe with `USAGE_FRONT_BUFFER`), plus a second
`SDK_INT >= TIRAMISU` check in `createRendererForStrategy`
(`FrameRenderCoordinator.kt:83`). On `minSdk 24`–32 devices, and on any device
whose driver lacks the extension, everything falls back to `SurfaceBackRenderer`
— which is also exercised by the generation fix, so the fix is not
front-buffer-only. `POST_NOTIFICATIONS` is `TIRAMISU`-guarded.

**The Play flavour builds and merges the service.** `playStore` is a real
flavour on the `version` dimension and has no manifest of its own, so the
`specialUse` service and its permissions merge into `playStore*` variants too.
Not a correctness problem — and irrelevant to a sideloaded personal build — but
if this ever went to Play, a `specialUse` FGS declared as "keeps the process
alive while backgrounded" is the case Google's guidance names as *not* a valid
special use. That is a submission problem for upstream, not for you.

---

## Appendix — the autosave/restore path in full

The dedicated data-loss review. **B1 above is its headline finding**; this
section is the supporting trace and everything else the path turned up.

Traced end to end: `EmulatorRecoveryRepository.kt` (571 lines, all the
persistence), `RecoveryPolicy.kt`, the `EmulatorViewModel` orchestration
(`initializeSession` 193‑217, `launchRom` 306‑373, `prepareForDeviceSleep`
545‑575, `resumeAfterDeviceSleep` 577‑607, `restoreCheckpoint` 714‑731),
`EmulatorActivity` (`onPause` 1269‑1281, `onResume` 834‑868,
`showRecoveryDialog` 1138‑1189), and the native side —
`MelonDSAndroidJNI.cpp` (`beginSynchronizedOperation` 214‑253,
`saveStateInternal` 547‑564, `loadStateInternal` 566‑583), `MelonDS.cpp`
(`saveState` 242‑271, `loadState` 273‑323), `SaveManager.cpp`,
`melonDS-android-lib/src/Savestate.cpp`, `melonDS-android-lib/src/NDSCart.cpp`.

State lives in `filesDir/emulator-recovery/`: `session.json` (metadata),
`journal.jsonl` (audit log), `checkpoint-<epochMs>.mln` (the savestate).

### Is the write atomic? Yes. This is the strongest part of #1666.

The four things that could have made this blocking are all done right.

- **`session.json` uses `android.util.AtomicFile`**
  (`EmulatorRecoveryRepository.kt:39, 363-379`) — write-new + rename, `.bak`
  fallback on read, `finishWrite` fsyncs.
- **The checkpoint blob is temp + fsync + rename, never truncate-in-place**
  (`:186-239`):
  ```kotlin
  val tempFile = File(recoveryDirectory, "$CHECKPOINT_FILE_PREFIX${UUID.randomUUID()}.tmp")
  ...
  RandomAccessFile(tempFile, "rw").use { it.fd.sync() }
  val committedFile = File(recoveryDirectory, "$CHECKPOINT_FILE_PREFIX${System.currentTimeMillis()}.mln")
  if (!tempFile.renameTo(committedFile)) { ... }
  ```
  A kill mid-write leaves an orphan `.tmp` the session never references; the
  previously committed `.mln` survives. A kill between rename and `writeSession`
  leaves an unreferenced `.mln`. Both are reaped by `deleteCheckpoints()` at the
  next session start. Every failure direction is fail-safe.
- **A partial write is detected, three times over, and never loaded blindly.**
  SHA-256 recorded in the session and re-verified before use (`isCheckpointValid`,
  `:399-408`); a non-zero length check; and the core's own header validation —
  magic `"MELN"`, exact major-version match, minor not from the future, and
  `read_length != buffer_length` ⇒ `Error` (`Savestate.cpp:55-116`).
  `MelonDS.cpp:311` tests `state->Error` and rolls back to a pre-load backup
  state it takes at line 290.
- **The checkpoint is taken from a quiesced emulator, never mid-frame.**
  `saveStateInternal` goes through `beginSynchronizedOperation()`
  (`MelonDSAndroidJNI.cpp:214-253`), which applies the core pause and blocks in
  `waitForSafePointLocked` until the emu thread sets `emulatorAtSafePoint` at
  the top of its loop, between `MelonDSAndroid::loop()` calls (`:874-896`) —
  i.e. a frame boundary. `prepareForDeviceSleep` additionally requires a
  successful user pause first and aborts otherwise, toasting
  `recovery_pause_timeout` after 2 s.

One gap: no directory fsync after the rename, so a power cut could lose the
rename. Failure mode is a missing checkpoint — fail-safe.

### Can a restore overwrite a newer in-game save? Yes — see **B1**.

That is the blocking finding above, and it is the answer to this question.

### Is the checkpoint bound to a ROM and a build? Partly, and a mismatch is silent.

**Build:** yes. `isCheckpointValid` (`:399-408`) rejects a checkpoint whose
`session.appVersionCode` differs from the current one, which transitively covers
the savestate format version (same build ⇒ same `SAVESTATE_MAJOR`).

**ROM:** only by URI. `session.romUri` (`:80`) is the only binding — there is no
ROM hash. If the file behind that URI changed, the app relaunches "the ROM" and
proceeds. The core does have a check, but it fails open:
`melonDS-android-lib/src/NDSCart.cpp:1489-1518` —

```cpp
// savestate should be loaded after the right game is loaded
// (TODO: system to verify that indeed the right ROM is loaded)
...
    u32 savetype;  file->Var32(&savetype);  if (savetype != carttype) return;
    u32 savechk;   file->Var32(&savechk);   if (savechk != cartchk)  return;
```

A ROM mismatch is a **bare `return`** — no error flag, no failure signalled. RAM,
CPUs, GPU and SPU have already been loaded by then, so the emulator ends up
running one game's console state with another's cart inserted, and
`MelonDS.cpp:311` reports success. Garbage, not a clean refusal.

Small mercy: because `Cart->DoSavestate` is skipped, the `WriteNDSSave` from B1
never runs, so a cross-ROM restore does not directly clobber the `.sav` — though
the resulting garbage state can make the game write nonsense to SRAM on its own.

Reachability is low (you would have to replace a ROM file in place at the same
URI), which is why this is not itself blocking.

### Other findings in this path

- **Automatic, silent restore now covers crashes and ANRs.** Commit `96d00a8d`
  inverted #1666's allow-list into a deny-list (`RecoveryPolicy.kt:76-81`), so
  `ANR`, `CRASH`, `NATIVE_CRASH`, `SELF_EXIT`, `INITIALIZATION_FAILURE` and
  `PERMISSION_CHANGE` are all auto-restore eligible — no dialog, and the `.sav`
  silently rewritten. The guards are real (`session.sleeping`,
  `checkpointCreatedAt >= sleepStartedAt`, one-shot via
  `automaticRecoveryAttempted`), so in the happy path the restored checkpoint is
  fresh and loss is bounded to the sleep interval. The residual risk is that
  `sleeping` is only cleared by `resumeAfterDeviceSleep`, which
  `EmulatorActivity.kt:849-851` skips on an early return — see the next item.

- **An `onResume` early return can leave the game frozen and the session
  mislabelled as asleep.** `EmulatorActivity.kt:843-857`:
  ```kotlin
  viewModel.finishDeviceSleepPreparation()
  yield()
  if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isScreenOff()) {
      return@launch
  }
  if (viewModel.resumeAfterDeviceSleep(shouldResume)) {
      choreographerFrameRenderer.startRendering()
  ```
  If that early return is taken, `resumeAfterDeviceSleep` never runs, so
  `sleeping` stays `true` while the user is awake and playing, *and*
  `startRendering()` never runs — a frozen game until the user backgrounds and
  foregrounds the app. It self-heals on the next `onResume`, but a kill inside
  that window produces a prompt-less automatic restore of a now-stale
  checkpoint. How wide the window is depends on device timing —
  `finishDeviceSleepPreparation` can span the 2 s pause timeout plus a
  multi-megabyte savestate write — and cannot be settled without a device.

- **Multi-megabyte fsync and SHA-256 run on the main thread.**
  `sessionCoroutineScope` and `viewModelScope` are `Dispatchers.Main.immediate`
  (`EmulatorViewModel.kt:1399-1412`) and only three repository calls are wrapped
  in `withContext(Dispatchers.IO)` (`:194, 198, 207`). `commitCheckpoint` —
  `RandomAccessFile(...).fd.sync()` then a full-file `sha256()`, over a
  savestate whose `Savestate::DEFAULT_SIZE` is 32 MB — runs on Main at every
  screen-off, and `restoreCheckpoint` (`:714-731`) re-runs `isCheckpointValid`
  on Main for a *second* full SHA-256 of the same file. `markDeviceSleepStarted`
  is called synchronously from `onPause`. Self-defeating: ANR is both one of the
  exit reasons this feature exists to survive and, since `96d00a8d`, an
  auto-restore trigger.

- **An unhandled exception in the recovery probe crashes the app on every
  launch.** `getPendingRecovery` (`:43-70`) has no try/catch. `readSession()`
  and `readAtomicFile()` are guarded, but `isCheckpointValid` → `File.sha256()`
  (`:445-458`) and `currentVersionCode()` (`getPackageInfo`) are not. An
  `IOException` propagates out of `initializeSession`, which is an unguarded
  `viewModelScope.launch` (`:188-191`) with no `CoroutineExceptionHandler` →
  uncaught → process death. Nothing has cleaned the session, so the next launch
  takes the identical path. Low probability, persistent failure mode.

- **The message pipe's write end is blocking.** `EmulatorMessageQueueJNI.cpp:90-91`
  sets `O_NONBLOCK` on the read end only, and `fireEmulatorEvent` (`:129-149`)
  writes to the blocking write end under `messagePipeMutex`. A stalled reader
  filling the 64 KB pipe blocks the emulator thread in `write()`, so it never
  reaches the safe point and `beginSynchronizedOperation` times out after 2 s —
  the checkpoint silently fails (`checkpoint_failed`). The code also checks
  `errno != EAGAIN` on an fd that can never return it.

- **SRAM written in the last frame before a pause is not flushed.**
  `SaveManager::CheckFlush()` — the step that promotes `Buffer` →
  `SecondaryBuffer`, the only thing the writer thread ever persists — is called
  only from the per-frame path (`MelonInstance.cpp:390-397`). Pausing stops
  frames, so a pause loses at most one frame's worth of SRAM writes.
  Pre-existing melonDS behaviour, but #1666's "pause on every screen-off" makes
  it hit far more often, and `resumeAfterDeviceSleep(resumeEmulation = false)`
  (taken when an overlay is up) extends the paused period indefinitely.

### Tests

Two new JVM test files, plus a PR workflow running
`:app:testGitHubProdDebugUnitTest`.

- `app/src/test/.../recovery/RecoveryPolicyTest.kt` (210 lines, 7 tests)
  exercises **only the pure functions in `RecoveryPolicy.kt`** —
  `canAutomaticallyRestore`, `shouldDiscardRecovery`, `classifyPreviousExit`, the
  sleep/auto-recovery transitions, `shouldDisableHardcoreForRecovery` — against
  hand-built `RecoverySession` objects.
- `app/src/test/.../EmulatorEventFrameDecoderTest.kt` (112 lines) tests the
  event-frame decoder. Good, and unrelated to checkpoint integrity.

**`EmulatorRecoveryRepository` has zero tests.** Nothing covers
`commitCheckpoint`, the temp/rename sequence, `isCheckpointValid`, a SHA
mismatch, truncation, `deleteCheckpoints`, `AtomicFile` recovery, or
`RecoverySession` JSON round-tripping. (Note `schemaVersion` is written at
`:526` and never read by `fromJson`.) Nothing covers the savestate ⇄ `.sav`
interaction — i.e. nothing would have caught B1. Nothing covers checkpoint
staleness, the `EmulatorViewModel` orchestration, or anything native.

`docs/sleep-recovery-testing.md` is honest that native state serialisation
"still require[s] a final device acceptance pass", but its acceptance checklist
(lines 60‑69) never asks anyone to verify that an in-game save survives a
restore.

---

## Suggested order of work

1. **B1.** One line in `RecoveryPolicy.finishDeviceSleep()` plus a
   `deleteCheckpoints()` call. Do this before the build goes on the Thor, or
   commit to the behavioural rule at the end of B1.
2. Run the three unverified tests in THOR-NOTES — especially the kill/restore
   cycle (which is now also a B1 regression test) and the re-sent launch intent
   (S8). Two of the four agent-written commits currently rest on code review
   alone.
3. S1 (two lines) and S7 (one line). Both cheap; S1 also removes a chain that
   can silently discard a recovery checkpoint.
4. S5 (one line) if you intend to turn on "Sync RTC on lid open". Otherwise
   leave the pref off and the whole issue is inert.
5. S2, S3, S4 — the renderer robustness set. None can lose a save; all three
   turn "black screen forever" into something recoverable.
6. S6, S8 and the nits at leisure.

Two cheap upstream-worthy additions while you are in there: put
`session.romName` in the recovery dialog, and add a line saying the in-game save
is rolled back too. Both are strings, and between them they would have made B1
survivable by inspection.
