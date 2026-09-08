// Copyright 2017-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include "audio_core/dsp_interface.h"
#include "audio_core/sink.h"
#include "audio_core/sink_details.h"
#include "common/assert.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/dumping/backend.h"

namespace AudioCore {

DspInterface::DspInterface(Core::System& system_) : system(system_) {}

DspInterface::~DspInterface() = default;

void DspInterface::SetSink(AudioCore::SinkType sink_type, std::string_view audio_device) {
    std::scoped_lock lock{sink_mutex};

    // Dispose of the current sink first to avoid contention.
    sink.reset();

    sink = AudioCore::GetSinkDetails(sink_type).create_sink(audio_device);
    sink->SetCallback(
        [this](s16* buffer, std::size_t num_frames) { OutputCallback(buffer, num_frames); });
    time_stretcher.SetOutputSampleRate(sink->GetNativeSampleRate());

    // Settings can be re-applied while the emulator is paused (Android reloads them on the way
    // back from the settings activity); a fresh sink must not start streaming silence.
    if (output_paused.load(std::memory_order_relaxed)) {
        sink->SetPaused(true);
    }
}

Sink& DspInterface::GetSink() {
    ASSERT(sink);
    return *sink.get();
}

void DspInterface::EnableStretching(bool enable) {
    enable_time_stretching = enable;
}

void DspInterface::PauseOutput(bool paused) {
    std::scoped_lock lock{sink_mutex};
    if (output_paused.exchange(paused, std::memory_order_relaxed) == paused) {
        return;
    }
    if (sink) {
        sink->SetPaused(paused);
    }
}

void DspInterface::UpdateEmulationSpeed() {
    // One system frame is 1/60 s of guest audio; sampling the perf stats every guest audio
    // frame (160 samples, ~200 Hz) is plenty and keeps the per-sample LLE path cheap
    constexpr std::size_t samples_per_update = 160;
    samples_since_speed_update += 1;
    if (samples_since_speed_update < samples_per_update) {
        return;
    }
    samples_since_speed_update = 0;

    // Roughly a 50 ms time constant at 200 Hz: fast enough to react to the frame limiter
    // engaging, slow enough that a single long frame (shader compile, GC) does not flip the
    // stretcher on and off.
    constexpr double smoothing = 0.1;
    // A frame that took more than a third of a second is a pause or a hitch, not a speed; cap
    // its weight so the stretcher does not stay engaged for seconds after a resume
    constexpr double max_scale = 20.0;
    const double scale = system.GetStableFrameTimeScale();
    if (!std::isfinite(scale) || scale <= 0.0) {
        return;
    }
    const double previous = frame_time_scale.load(std::memory_order_relaxed);
    frame_time_scale.store(previous + smoothing * (std::min(scale, max_scale) - previous),
                           std::memory_order_relaxed);
}

bool DspInterface::ShouldStretch() const {
    if (!enable_time_stretching.load(std::memory_order_relaxed)) {
        return false;
    }
    // Stretching exists to cover speeds the FIFO cannot absorb. Within a few percent of real
    // time the drift is slower than the FIFO's quarter second of slack for many seconds, and
    // SoundTouch would only add latency and a full resample per callback. A little hysteresis
    // keeps a speed that hovers on the threshold from toggling the stretcher every callback.
    constexpr double engage_threshold = 0.05;
    constexpr double release_threshold = 0.04;
    const double deviation = std::abs(frame_time_scale.load(std::memory_order_relaxed) - 1.0);
    const double threshold = performing_time_stretching.load(std::memory_order_relaxed)
                                 ? release_threshold
                                 : engage_threshold;
    return deviation > threshold;
}

void DspInterface::OutputFrame(StereoFrame16 frame) {
    if (!sink) {
        return;
    }

    UpdateEmulationSpeed();

    if (sink->ImmediateSubmission()) {
        sink->PushSamples(frame.data(), frame.size());
    } else {
        fifo.Push(frame.data(), frame.size());
    }

    auto video_dumper = system.GetVideoDumper();
    if (video_dumper && video_dumper->IsDumping()) {
        video_dumper->AddAudioFrame(std::move(frame));
    }
}

void DspInterface::OutputSample(std::array<s16, 2> sample) {
    if (!sink) {
        return;
    }

    UpdateEmulationSpeed();

    if (sink->ImmediateSubmission()) {
        sink->PushSamples(&sample, 1);
    } else {
        fifo.Push(&sample, 1);
    }

    auto video_dumper = system.GetVideoDumper();
    if (video_dumper && video_dumper->IsDumping()) {
        video_dumper->AddAudioSample(std::move(sample));
    }
}

void DspInterface::OutputCallback(s16* buffer, std::size_t num_frames) {
    const float linear_volume = std::clamp(Settings::Volume(), 0.0f, 1.0f);
    if (linear_volume <= 0.0f) {
        // Muted (the Android frontend also mutes while paused, until the sink has stopped):
        // nothing the guest produced can be heard, so skip the stretcher, the hold-last-frame
        // fill and the volume multiply. Still drain the FIFO so audio does not pile up and
        // play back late when the volume comes back, and drop whatever the stretcher held.
        fifo.Pop(buffer, num_frames);
        std::memset(buffer, 0, num_frames * 2 * sizeof(s16));
        last_frame = {};
        if (performing_time_stretching) {
            time_stretcher.Clear();
            performing_time_stretching = false;
        }
        flushing_time_stretcher = false;
        return;
    }

    // Only stretch when the emulation speed is far enough from real time for it to matter
    const bool should_stretch = ShouldStretch();
    if (performing_time_stretching && !should_stretch) {
        // If we just stopped stretching, flush the stretcher before returning to normal output.
        flushing_time_stretcher = true;
    }
    performing_time_stretching = should_stretch;

    std::size_t frames_written = 0;
    if (performing_time_stretching) {
        // Sized to the whole FIFO once; RingBuffer::Pop() by value would allocate that much on
        // every callback
        stretch_in.resize(fifo.Capacity() * 2);
        const std::size_t num_in = fifo.Pop(stretch_in.data());
        frames_written = time_stretcher.Process(stretch_in.data(), num_in, buffer, num_frames);
    } else {
        if (flushing_time_stretcher) {
            time_stretcher.Flush();
            frames_written = time_stretcher.Process(nullptr, 0, buffer, num_frames);
            flushing_time_stretcher = false;

            // Make sure any frames that did not fit are cleared from the time stretcher,
            // so that they do not bleed into the next time the stretcher is enabled.
            time_stretcher.Clear();
        }
        frames_written += fifo.Pop(buffer, num_frames - frames_written);
    }

    if (frames_written > 0) {
        std::memcpy(&last_frame[0], buffer + 2 * (frames_written - 1), 2 * sizeof(s16));
    }

    // Hold last emitted frame; this prevents popping.
    for (std::size_t i = frames_written; i < num_frames; i++) {
        std::memcpy(buffer + 2 * i, &last_frame[0], 2 * sizeof(s16));
    }

    // Implementation of the hardware volume slider
    // A cubic curve is used to approximate a linear change in human-perceived loudness
    if (linear_volume != 1.0) {
        const float volume_scale_factor = linear_volume * linear_volume * linear_volume;
        for (std::size_t i = 0; i < num_frames; i++) {
            buffer[i * 2 + 0] = static_cast<s16>(buffer[i * 2 + 0] * volume_scale_factor);
            buffer[i * 2 + 1] = static_cast<s16>(buffer[i * 2 + 1] * volume_scale_factor);
        }
    }
}

} // namespace AudioCore
