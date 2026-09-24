// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <sstream>
#include <boost/serialization/array.hpp>
#include <catch2/catch_test_macros.hpp>

#include "audio_core/hle/mixers.h"
#include "common/archives.h"

namespace {

using AudioCore::QuadFrame32;
using AudioCore::StereoFrame16;
using OutputFormat = AudioCore::HLE::Mixers::OutputFormat;

/// Mixers as it was serialized before the delay and reverb effects existed. The archive layout
/// is structural (binary archives carry class versions, not names), so this writes the same
/// bytes a pre-effects build wrote into its savestates.
struct PreEffectsMixerState {
    std::array<float, 3> intermediate_mixer_volume = {};
    std::array<bool, 2> aux_bus_enable = {};
    std::array<QuadFrame32, 3> intermediate_mix_buffer = {};
    OutputFormat output_format = OutputFormat::Stereo;

    template <class Archive>
    void serialize(Archive& ar, const unsigned int) {
        ar & intermediate_mixer_volume;
        ar & aux_bus_enable;
        ar & intermediate_mix_buffer;
        ar & output_format;
    }
};

struct PreEffectsMixers {
    StereoFrame16 current_frame = {};
    StereoFrame16 backup_frame = {};
    PreEffectsMixerState state;
    PreEffectsMixerState backup_state;

    template <class Archive>
    void serialize(Archive& ar, const unsigned int) {
        ar & current_frame;
        ar & backup_frame;
        ar & state;
        ar & backup_state;
    }
};

// Whatever follows the mixers in a savestate (the rest of the DSP, then emulated memory and
// the kernel) must be read from where it was written.
constexpr u32 NextField = 0xC0FFEE42;

} // Anonymous namespace

TEST_CASE("AudioCore::HLE::Mixers::serialize", "[audio_core][hle][savestate]") {
    SECTION("loads a state written before the effects existed without shifting what follows") {
        PreEffectsMixers old_mixers;
        old_mixers.current_frame[0] = {123, -456};
        old_mixers.state.intermediate_mixer_volume = {0.25f, 0.5f, 0.75f};
        old_mixers.state.output_format = OutputFormat::Surround;
        old_mixers.backup_state.aux_bus_enable = {true, false};

        std::stringstream stream;
        {
            oarchive oa{stream};
            u32 next = NextField;
            oa & old_mixers;
            oa & next;
        }

        AudioCore::HLE::Mixers mixers;
        u32 next = 0;
        {
            iarchive ia{stream};
            ia & mixers;
            ia & next;
        }

        REQUIRE(next == NextField);
        REQUIRE(mixers.GetOutput()[0] == std::array<s16, 2>{123, -456});
    }

    SECTION("round-trips a state written by this build") {
        std::stringstream stream;
        {
            oarchive oa{stream};
            AudioCore::HLE::Mixers mixers;
            u32 next = NextField;
            oa & mixers;
            oa & next;
        }

        AudioCore::HLE::Mixers mixers;
        u32 next = 0;
        {
            iarchive ia{stream};
            ia & mixers;
            ia & next;
        }

        REQUIRE(next == NextField);
    }
}
