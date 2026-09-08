// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <catch2/catch_test_macros.hpp>
#include "common/settings.h"
#include "core/3ds.h"
#include "core/frontend/emu_window.h"
#include "core/frontend/framebuffer_layout.h"
#include "core/frontend/input.h"

namespace {

/// Restores every setting the layout/touch code reads, so the cases can be run in any order.
struct SettingsGuard {
    SettingsGuard()
        : layout_option{Settings::values.layout_option.GetValue()},
          swap_screen{Settings::values.swap_screen.GetValue()},
          upright_screen{Settings::values.upright_screen.GetValue()},
          render_3d{Settings::values.render_3d.GetValue()} {}
    ~SettingsGuard() {
        Settings::values.layout_option = layout_option;
        Settings::values.swap_screen = swap_screen;
        Settings::values.upright_screen = upright_screen;
        Settings::values.render_3d = render_3d;
    }

    Settings::LayoutOption layout_option;
    bool swap_screen;
    bool upright_screen;
    Settings::StereoRenderOption render_3d;
};

/// The smallest possible EmuWindow: the touch entry points are non-virtual, so all a test
/// needs is a way to install a layout and the pure-virtual PollEvents filled in.
class TestWindow final : public Frontend::EmuWindow {
public:
    explicit TestWindow(bool is_secondary = false) : EmuWindow{is_secondary} {}

    void PollEvents() override {}

    void SetLayout(const Layout::FramebufferLayout& layout) {
        NotifyFramebufferLayoutChanged(layout);
    }
};

constexpr u32 kWidth = Core::kScreenTopWidth;
constexpr u32 kHeight = Core::kScreenTopHeight;

} // namespace

TEST_CASE("Layout::SingleFrameLayout", "[core][frontend][layout]") {
    SettingsGuard guard;
    Settings::values.swap_screen = false;
    Settings::values.upright_screen = false;

    SECTION("showing the top screen reports no bottom screen region") {
        const auto layout = Layout::SingleFrameLayout(kWidth, kHeight, false, false);

        REQUIRE(layout.top_screen_enabled);
        REQUIRE_FALSE(layout.bottom_screen_enabled);
        REQUIRE(layout.top_screen.GetWidth() > 0);
        REQUIRE(layout.top_screen.GetHeight() > 0);
        // The whole point of #2020: a hidden bottom screen must not claim window pixels,
        // or every touch on the top panel lands on the emulated touchscreen.
        REQUIRE(layout.bottom_screen.GetWidth() == 0);
        REQUIRE(layout.bottom_screen.GetHeight() == 0);
    }

    SECTION("showing the bottom screen reports no top screen region") {
        const auto layout = Layout::SingleFrameLayout(kWidth, kHeight, true, false);

        REQUIRE_FALSE(layout.top_screen_enabled);
        REQUIRE(layout.bottom_screen_enabled);
        REQUIRE(layout.bottom_screen.GetWidth() > 0);
        REQUIRE(layout.bottom_screen.GetHeight() > 0);
        REQUIRE(layout.top_screen.GetWidth() == 0);
        REQUIRE(layout.top_screen.GetHeight() == 0);
    }

    SECTION("the hidden screen stays empty when the layout is rotated upright") {
        const auto layout = Layout::SingleFrameLayout(kWidth, kHeight, false, true);

        REQUIRE_FALSE(layout.bottom_screen_enabled);
        REQUIRE(layout.bottom_screen.GetWidth() == 0);
        REQUIRE(layout.bottom_screen.GetHeight() == 0);
    }
}

TEST_CASE("Frontend::EmuWindow::TouchPressed", "[core][frontend][emu_window]") {
    SettingsGuard guard;
    Settings::values.layout_option = Settings::LayoutOption::SingleScreen;
    Settings::values.swap_screen = false;
    Settings::values.upright_screen = false;
    Settings::values.render_3d = Settings::StereoRenderOption::Off;

    SECTION("a window showing only the top screen never takes touch input") {
        TestWindow window;
        window.SetLayout(Layout::SingleFrameLayout(kWidth, kHeight, false, false));

        REQUIRE_FALSE(window.TouchPressed(kWidth / 2, kHeight / 2));
        REQUIRE_FALSE(window.TouchPressed(0, 0));
        REQUIRE_FALSE(window.TouchPressed(kWidth - 1, kHeight - 1));
        window.TouchReleased();
    }

    SECTION("a window showing the bottom screen still takes touch input") {
        TestWindow window{true};
        const auto layout = Layout::SingleFrameLayout(kWidth, kHeight, true, false);
        window.SetLayout(layout);

        const u32 x = (layout.bottom_screen.left + layout.bottom_screen.right) / 2;
        const u32 y = (layout.bottom_screen.top + layout.bottom_screen.bottom) / 2;
        REQUIRE(window.TouchPressed(x, y));
        window.TouchReleased();
    }

    SECTION("a drag over a top-only window does not move the touch point") {
        // The touch state is shared between the primary and secondary windows, so the top
        // window can be asked to move a touch that the bottom window started.
        TestWindow bottom{true};
        const auto bottom_layout = Layout::SingleFrameLayout(kWidth, kHeight, true, false);
        bottom.SetLayout(bottom_layout);
        const u32 x = (bottom_layout.bottom_screen.left + bottom_layout.bottom_screen.right) / 2;
        const u32 y = (bottom_layout.bottom_screen.top + bottom_layout.bottom_screen.bottom) / 2;
        REQUIRE(bottom.TouchPressed(x, y));

        const auto touch = Input::CreateDevice<Input::TouchDevice>("engine:emu_window");
        const auto [pressed_x, pressed_y, pressed] = touch->GetStatus();
        REQUIRE(pressed);

        TestWindow top;
        top.SetLayout(Layout::SingleFrameLayout(kWidth, kHeight, false, false));
        top.TouchMoved(0, 0);
        top.TouchMoved(kWidth - 1, kHeight - 1);

        const auto [moved_x, moved_y, still_pressed] = touch->GetStatus();
        REQUIRE(still_pressed);
        REQUIRE(moved_x == pressed_x);
        REQUIRE(moved_y == pressed_y);

        bottom.TouchReleased();
    }
}
