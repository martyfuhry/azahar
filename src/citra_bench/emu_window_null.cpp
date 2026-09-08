// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include "citra_bench/emu_window_null.h"
#include "core/3ds.h"

namespace Bench {

EmuWindow_Null::EmuWindow_Null() {
    window_info.type = Frontend::WindowSystemType::Headless;
    // A native-resolution single-screen stack, purely so any layout query is well defined
    UpdateCurrentFramebufferLayout(Core::kScreenTopWidth,
                                   Core::kScreenTopHeight + Core::kScreenBottomHeight);
}

EmuWindow_Null::~EmuWindow_Null() = default;

void EmuWindow_Null::PollEvents() {
    if (frames++ == 0) {
        first_frame_time = Clock::now();
    }
}

} // namespace Bench
