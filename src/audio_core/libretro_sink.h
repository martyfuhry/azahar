// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <cstddef>
#include <string>
#include <vector>
#include "audio_core/sink.h"

namespace AudioCore {

class LibRetroSink final : public Sink {
public:
    explicit LibRetroSink(std::string target_device_name);
    ~LibRetroSink() override;

    unsigned int GetNativeSampleRate() const override;

    // Not used for immediate submission sinks
    void SetCallback(std::function<void(s16*, std::size_t)> cb) override {};

    bool ImmediateSubmission() override {
        return true;
    }

    void PushSamples(const void* data, std::size_t num_samples) override;

    // The frontend owns the output device and stops calling retro_run while paused
    void SetPaused(bool) override {}
};

std::vector<std::string> ListLibretroSinkDevices();

} // namespace AudioCore
