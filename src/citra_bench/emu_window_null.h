// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <chrono>
#include <optional>
#include "common/common_types.h"
#include "core/frontend/emu_window.h"

namespace Bench {

/**
 * Window for a frontend that never presents anything. The software renderer needs no graphics
 * context, so every context method is the GraphicsContext no-op; the only thing this window does
 * is count the system frames the renderer ends (RendererBase::EndFrame polls the window once
 * per LCD vblank) and remember when the first one arrived, so the harness can stop after N
 * frames and report the boot time.
 */
class EmuWindow_Null final : public Frontend::EmuWindow {
public:
    using Clock = std::chrono::steady_clock;

    EmuWindow_Null();
    ~EmuWindow_Null() override;

    void PollEvents() override;

    /// System frames (LCD vblanks) presented since construction
    u64 GetFrameCount() const {
        return frames;
    }

    /// When the first system frame was presented, if one has been
    std::optional<Clock::time_point> GetFirstFrameTime() const {
        return first_frame_time;
    }

private:
    u64 frames = 0;
    std::optional<Clock::time_point> first_frame_time;
};

} // namespace Bench
