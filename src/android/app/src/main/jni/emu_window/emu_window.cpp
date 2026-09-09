// Copyright 2019-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <array>
#include <cstdlib>
#include <string>
#include <android/native_window_jni.h>
#include "common/android_utils.h"
#include "common/logging/log.h"
#include "common/settings.h"
#include "input_common/main.h"
#include "jni/emu_window/emu_window.h"
#include "jni/id_cache.h"
#include "jni/input_manager.h"
#include "network/network.h"
#include "video_core/renderer_base.h"

bool EmuWindow_Android::OnSurfaceChanged(ANativeWindow* surface) {
    int temp_width = (surface == nullptr) ? 0 : ANativeWindow_getWidth(surface);
    int temp_height = (surface == nullptr) ? 0 : ANativeWindow_getHeight(surface);
    if (render_window == surface && temp_width == window_width && temp_height == window_height) {
        return false;
    }
    window_width = temp_width;
    window_height = temp_height;
    render_window = surface;
    window_info.type = Frontend::WindowSystemType::Android;
    window_info.render_surface = surface;
    StopPresenting();
    OnFramebufferSizeChanged();
    return true;
}

bool EmuWindow_Android::OnTouchEvent(int x, int y, bool pressed) {
    if (pressed) {
        return TouchPressed((unsigned)std::max(x, 0), (unsigned)std::max(y, 0));
    }

    TouchReleased();
    return true;
}

void EmuWindow_Android::OnTouchMoved(int x, int y) {
    TouchMoved((unsigned)std::max(x, 0), (unsigned)std::max(y, 0));
}

void EmuWindow_Android::OnFramebufferSizeChanged() {
    const bool is_portrait_mode = (AndroidUtils::IsPortraitMode() && !is_secondary);
    UpdateCurrentFramebufferLayout(window_width, window_height, is_portrait_mode);
}

EmuWindow_Android::EmuWindow_Android(ANativeWindow* surface, bool is_secondary)
    : EmuWindow{is_secondary}, host_window(surface) {
    LOG_DEBUG(Frontend, "Initializing EmuWindow_Android");
    // window_info.render_surface is what the renderers use to decide whether this window has
    // anything to present to (see RendererVulkan::SwapBuffers), so it has to be accurate from
    // construction on, not only after the first OnSurfaceChanged(). The Vulkan window sets the
    // same pair in CreateWindowSurface(); the GL one never did.
    window_info.type = Frontend::WindowSystemType::Android;
    window_info.render_surface = surface;
    if (!surface) {
        // Expected for the secondary window when no secondary display is in use: the frontend
        // creates no Presentation, so there is no surface until one is plugged in at runtime.
        if (is_secondary) {
            LOG_INFO(Frontend, "No secondary surface; secondary window starts inactive");
        } else {
            LOG_CRITICAL(Frontend, "surface is nullptr");
        }
        return;
    }

    window_width = ANativeWindow_getWidth(surface);
    window_height = ANativeWindow_getHeight(surface);
}

EmuWindow_Android::~EmuWindow_Android() {
    DestroyWindowSurface();
    DestroyContext();
}

void EmuWindow_Android::MakeCurrent() {
    // Absent when the graphics context could not be created, including the legitimate case of a
    // secondary window that has never had a surface (no secondary display in use).
    if (!core_context) {
        return;
    }
    core_context->MakeCurrent();
}

void EmuWindow_Android::DoneCurrent() {
    if (!core_context) {
        return;
    }
    core_context->DoneCurrent();
}
