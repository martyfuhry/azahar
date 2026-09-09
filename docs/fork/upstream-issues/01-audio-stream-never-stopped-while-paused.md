# Android: the audio output stream is never stopped while emulation is paused, and the resulting background CPU can get the process killed

**Shape:** new issue.
**Searched first:** `gh issue list -R azahar-emu/azahar --search "audio background cpu paused"`, `"battery drain sleeping"`, `"CPU background killed"`, `"excessive cpu"`. Nothing filed for this mechanism. The closest is #519 ("Huge battery consumption during Android Sleep"), which is the *symptom* of this bug reported without a cause, and #2433, which is a different problem in the same callback. Once this is filed, drop a one-line pointer to it on #519.

---

## Environment

- Azahar: upstream `master` at `073110cb4` (2026-09-07), between the `2126.1-rc2` tag and 2126.1. Behaviour is unchanged back through 2125.x, which is what the #519 reporters are on.
- Test device: Samsung Galaxy Z Fold5 (SM-F946U1), Android 16 (API 36), Snapdragon 8 Gen 2, Adreno 740. This is my lab phone. Same SoC as the AYN Thor, different device, different panel, different thermals.
- Intended target: AYN Thor (`ro.product.manufacturer=AYN`, `ro.product.model=AYN Thor`), Android 13 (API 33), `ro.soc.model=QCS8550` (SM8550 / Snapdragon 8 Gen 2), Adreno 740 on driver **512.676.53** — the Fold5 is on 512.676.1. CPU has not been measured there — see the checklist at the bottom.
- Game: Animal Crossing: New Leaf, Vulkan, internal resolution 3x, package `org.azahar_emu.azahar` (vanilla flavour).

## Reproduction

1. Build the Android app from `master` and install it.
2. Launch any title and play until you are in gameplay with sound.
3. Note the pid: `PID=$(adb shell pidof org.azahar_emu.azahar)`.
4. Press Home. `EmulationFragment.onPause()` pauses emulation; the emulation thread parks.
5. Sample the process CPU over a fixed window:

```
adb shell cat /proc/$PID/stat            # fields 14 and 15, utime + stime, in clock ticks
sleep 30
adb shell cat /proc/$PID/stat
```

6. Do the same per thread over the same window to see where it goes:

```
adb shell 'for t in /proc/'$PID'/task/*; do echo "$(cat $t/comm) $(cat $t/stat)"; done'
```

7. Leave the app in the background for more than fifteen minutes without a foreground service holding it up, then come back and ask the OS why it died:

```
adb shell dumpsys activity exit-info org.azahar_emu.azahar
```

## Expected

A paused emulator produces no audio. The output device should be stopped, and the process should be effectively idle while it sits in the background — well under 1% of one core.

## Actual

The audio device callback thread keeps running at roughly 100–200 calls a second for as long as the game is backgrounded, doing the full mixing path to produce silence. Over my 30-second window after Home, the process burned **8.19% of one core**, of which **7.82 points were the audio callback thread alone** (it shows as `AAudio_1` on this phone; in an earlier pass on the same device it showed as `AudioTrack` — it is cubeb's AAudio callback thread either way). Everything else was rounding error: `NativeEmulation` 0.30%, main 0.07%, the rest 0.00%.

That is above the line Android kills you for, and on one device I have a kill record to go with it. From `dumpsys activity exit-info` on **the Fold5**, 2026-09-02, on a build with none of my audio changes in it:

```
reason=9 EXCESSIVE RESOURCE USAGE  subreason=7 EXCESSIVE CPU USAGE
description="excessive cpu 27810 during 300120 dur=1129762 limit=2"  state=empty  rss=1.1GB
```

Read that out: 27.810 seconds of CPU in a 300.120-second window, while the process was in the `empty` state, 1,129,762 ms (18.8 minutes) after it became unimportant, against a limit of 2%. 27.81 / 300.12 is 9.27%, so 4.6x over. That is `ActivityManagerConstants`' background CPU check: `POWER_CHECK_INTERVAL` is 5 minutes, the thresholds are 25/25/10/**2**% by how long the process has been unimportant, anything past 15 minutes lands in the 2% tier, and only processes at `procState >= PROCESS_STATE_HOME` are checked at all. `dur=1129762` and `limit=2` match that last tier exactly.

So the user-visible outcome on that device is both of the things people report on #519: it is warm and the battery is draining while it sleeps, *and* the game silently disappears while backgrounded, because Android killed the process for burning CPU it should not have been burning.

### How far the kill claim actually generalises

Not as far as the sentence above reads on its own, and this is worth getting right before filing.

The `EXCESSIVE RESOURCE USAGE` kill has been observed **on the Fold5 only**. A read-only snapshot of the AYN Thor on 2026-09-09 (`docs/fork/baselines/2026-09-09-thor-device-snapshot.md`) read `dumpsys activity exit-info` for both emulators installed on it, and there is **no excessive-CPU record on that device at all** — not for Azahar, not for melonDS, not at any date. There is not a `LOW_MEMORY` record either. What the Thor has is native crashes.

That does not weaken the bug. The mechanism is source-evident, the CPU burn is measured, and `ActivityManagerConstants`' 2% tier is stock AOSP, so any Android device that leaves the app unimportant for more than fifteen minutes is exposed. But the *kill* is the part that depends on the device, the OEM's policy and how the user actually backgrounds the app, and one device that shows the burn has so far not produced a kill record.

So the claim to file is: **the paused emulator burns background CPU well above Android's 2% tier, which is enough for `ActivityManagerConstants` to kill it, and here is a kill record from a device where it did.** Not: "this is why your game disappears". The battery/warmth half of #519 needs no kill at all and is the safer half of the report.

One honesty note on the numbers: the size of the burn varies with the scene and with whether time stretching is engaged. An earlier 60-second pass on the same phone, same build family, read 1.78% at Home with the screen on and 2.02% with the screen off. The 8.19% figure is the one from the controlled before/after pass. It is never zero, and at the 2% tier there is no headroom for it to be anything but a problem.

After stopping the stream, the same 30-second window on the same phone, same scene, same autosave: **0.33%**, with the audio thread gone from the per-thread table entirely.

| | paused CPU after Home, 30 s (% of one core) |
|---|---|
| `master` behaviour | 8.19 (audio thread 7.82) |
| stream stopped | 0.33 (audio thread absent) |

## Root cause

Pausing only zeroes the volume. It never touches the output device.

`src/android/app/src/main/jni/native.cpp:376-385` — the paused branch of the run loop:

```cpp
} else {
    // Ensure no audio bleeds out while game is paused
    const float volume = Settings::values.volume.GetValue();
    SCOPE_EXIT({ Settings::values.volume = volume; });
    Settings::values.volume = 0;

    std::unique_lock pause_lock{paused_mutex};
    running_cv.wait(pause_lock, [] { return !pause_emulation || stop_run; });
    window->PollEvents();
}
```

The emulation thread parks correctly. The audio device does not, because nothing can stop it:

- `src/audio_core/sink.h:19-50` — `Sink` has `GetNativeSampleRate`, `SetCallback`, `ImmediateSubmission` and `PushSamples`. There is no pause, stop or start in the interface at all.
- `src/audio_core/cubeb_sink.cpp:91-94` — `CubebSink`'s constructor calls `cubeb_stream_start`. `src/audio_core/cubeb_sink.cpp:97-108` — the destructor calls `cubeb_stream_stop`. Those are the only two places the stream state ever changes, so the stream runs from construction to destruction.
- `src/audio_core/dsp_interface.h` — `DspInterface` has no pause API either, so even if a sink could be stopped, no frontend has a way to ask.

So every callback still runs the whole path in `DspInterface::OutputCallback`, `src/audio_core/dsp_interface.cpp:73-120`:

- the stretcher, `dsp_interface.cpp:84-87`, which pops the FIFO **by value** — that is a `std::vector<s16>` allocation the size of the whole FIFO on every callback — and runs `TimeStretcher::Process`, which itself allocates two `std::vector<float>` scratch buffers and runs two conversion loops (`src/audio_core/time_stretch.cpp`);
- the hold-last-frame fill, `dsp_interface.cpp:105-108`, which memcpys the last sample across the rest of the buffer;
- and the volume multiply, `dsp_interface.cpp:110-119`, which is a per-sample cubic-scaled multiply over the whole buffer and which is *guaranteed* to run in this case, because the branch is `if (linear_volume != 1.0)` and the frontend has just set the volume to exactly 0.

Every one of those is work done to write zeros into a buffer nobody will hear, 94–188 times a second, for as long as the user has the game in their pocket.

The desktop frontend has the same shape: `GMainWindow::OnPauseGame` (`src/citra_qt/citra_qt.cpp:2640`) stops the emulation thread and leaves the sink running. It matters much less there because nothing on a desktop kills you for it, but a paused Azahar still holds an active audio device.

## How I fixed it in my fork

Three commits, in prose:

**Give `Sink` a pause API.** I added `SetPaused(bool)` to `Sink` with a no-op default, and implemented it per backend: `cubeb_stream_stop`/`cubeb_stream_start` for cubeb, `SDL_PauseAudioDevice` for SDL2, `alSourcePause`/`alSourcePlay` for OpenAL. The null and libretro sinks stay no-ops — the libretro frontend owns the device and simply stops calling `retro_run`. Above that, `DspInterface::PauseOutput(bool)` forwards to the sink under a mutex, remembers the state, and re-applies it to a sink created later by `SetSink`, because Android re-applies settings (and therefore rebuilds the DSP) while paused. One wrinkle worth knowing if you take this route: an AAudio route change while the stream is stopped (headphones unplugged with the game paused) can leave cubeb's stream in its `ERROR` state, from which `cubeb_stream_start` always fails, so the state callback records that and the next resume reopens the stream rather than trying to start a dead one.
https://github.com/martyfuhry/azahar/commit/7c67b5bbd

**Make the callback cheap even when it does run.** When `Settings::Volume()` is 0 the callback now drains the FIFO (so audio does not pile up and play back late when the volume returns), clears the stretcher and memsets the buffer, instead of stretching and multiplying its way to zeros. Same commit also stops running the stretcher when emulation is at real time — that is the TODO already in the code at `dsp_interface.cpp:75-76` referencing #2487 — and stops the per-callback FIFO-by-value allocation.
https://github.com/martyfuhry/azahar/commit/8c03bacbe

**Call it from the frontends.** `pauseEmulation` stops the output stream immediately through `DspInterface::PauseOutput`, and `unPauseEmulation` restarts it *before* waking the emulation thread so the first frames after a resume are not dropped waiting for the device to come back. The paused branch repeats the stop when it parks and the start when it genuinely leaves the pause, which covers a DSP rebuilt by a savestate load in between. I left the existing volume mute in place as belt and braces for the stop latency. The Qt side gets the same treatment in `OnPauseGame`/`OnResumeGame`.
https://github.com/martyfuhry/azahar/commit/a6400e568 (Android) and https://github.com/martyfuhry/azahar/commit/aca0264d0 (Qt)

I am not attaching a patch. The `Sink` API change alone is well past what your AI policy allows me to submit as code, and the sensible upstream shape may not be the one I picked. The measurement and the mechanism are the useful part.

## Marty must verify before filing

- [ ] Reproduce the paused-CPU burn myself on the **Thor**, on an unmodified upstream build, with my own hands: play, Home, sample `/proc/<pid>/stat` over 30 s, and confirm the process total is well above 2% and that the audio device thread is where it goes.
- [ ] Confirm per-thread that it is the audio callback thread and not something else on the Thor's Android 13 (thread names differ across AAudio paths — do not assume `AAudio_1`).
- [ ] **Do not require a Thor kill record for this to be filable — there is not one, and there may never be.** The 2026-09-09 snapshot found no excessive-CPU record on the Thor for any package. What the report needs is the CPU burn reproduced on the Thor (previous two boxes); the kill record stays a Fold5 record, labelled as one. If a Thor kill does turn up while testing, quote it as a second data point; if it does not, file anyway with the burn plus the Fold5 kill, and say in the body that the kill was seen on one device and the burn on both. Never present the Fold5 record as a Thor record.
- [ ] Sanity-check the 8.19% number once more on the Thor before quoting it, or quote the Thor's own number instead. The figure moves with the scene.
- [ ] Confirm the same behaviour on an official Azahar build from the release page, not just a build from my tree, so the report is about upstream and not about my checkout.

---

*Disclosure: this investigation was AI-assisted — I used an AI agent to read the audio path and correlate it with the CPU samples and the kill record. I reproduced the behaviour and took the measurements myself, and I am writing this report in my own words.*
