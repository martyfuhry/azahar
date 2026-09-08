// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <cmath>
#include <numbers>
#include <vector>
#include <catch2/benchmark/catch_benchmark.hpp>
#include <catch2/catch_test_macros.hpp>
#include "audio_core/audio_types.h"
#include "audio_core/time_stretch.h"
#include "common/common_types.h"

namespace {

/// Interleaved stereo tone, so SoundTouch has real transients to align instead of silence
std::vector<s16> MakeTone(std::size_t frames) {
    std::vector<s16> samples(frames * 2);
    for (std::size_t i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / AudioCore::native_sample_rate;
        samples[i * 2] = static_cast<s16>(12000.0 * std::sin(2.0 * std::numbers::pi * 440.0 * t));
        samples[i * 2 + 1] =
            static_cast<s16>(12000.0 * std::sin(2.0 * std::numbers::pi * 660.0 * t));
    }
    return samples;
}

} // namespace

// Baseline for B2 (scratch-buffer reuse / SoundTouch bypass in the audio callback). One call
// corresponds to one sink callback: the DSP has queued `in` frames since the last callback and
// the sink wants `out` frames now.
TEST_CASE("AudioCore::TimeStretcher::Process", "[bench][audio_core]") {
    constexpr std::size_t callback_frames = 512;
    const auto tone = MakeTone(callback_frames);
    std::vector<s16> out(callback_frames * 2);

    AudioCore::TimeStretcher stretcher;
    stretcher.SetOutputSampleRate(48000);
    // Fill the backlog so every measured call goes through the stretcher at a steady ratio
    for (int i = 0; i < 32; ++i) {
        stretcher.Process(tone.data(), callback_frames, out.data(), callback_frames);
    }

    BENCHMARK("512 frames in, 512 frames out (full speed)") {
        return stretcher.Process(tone.data(), callback_frames, out.data(), callback_frames);
    };

    BENCHMARK("256 frames in, 512 frames out (emulation at half speed)") {
        return stretcher.Process(tone.data(), callback_frames / 2, out.data(), callback_frames);
    };

    BENCHMARK("0 frames in, 512 frames out (starved)") {
        return stretcher.Process(nullptr, 0, out.data(), callback_frames);
    };
}
