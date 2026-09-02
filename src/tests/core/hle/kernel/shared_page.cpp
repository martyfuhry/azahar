// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <chrono>
#include <thread>
#include <catch2/catch_test_macros.hpp>
#include "common/settings.h"
#include "core/core_timing.h"
#include "core/hle/kernel/shared_page.h"

namespace {

constexpr s64 MS_PER_HOUR = 60 * 60 * 1000;
constexpr s64 ONE_HOUR_CYCLES = msToCycles(static_cast<int>(MS_PER_HOUR));
constexpr s64 ONE_SECOND_CYCLES = msToCycles(1000);

/// Advances emulated time on core 0 one slice at a time so that scheduled events fire on time.
void AdvanceTicks(Core::Timing& timing, s64 ticks) {
    auto timer = timing.GetTimer(0);
    while (ticks > 0) {
        const s64 step = std::min(ticks, timer->GetDowncount());
        timer->AddTicks(static_cast<u64>(step));
        ticks -= step;
        if (timer->GetDowncount() <= 0) {
            timer->Advance();
            timer->SetNextSlice();
        }
    }
}

/// Enters slice 0, which runs the initial UpdateTimeCallback scheduled by the Handler ctor.
void EnterFirstSlice(Core::Timing& timing) {
    timing.GetTimer(0)->Advance();
    timing.GetTimer(0)->SetNextSlice();
}

/// Lets host time pass while emulated time stands still, like a pause does. The Handler applies
/// whole seconds only, so sleeping a little over N seconds guarantees at least N seconds are due.
void PauseFor(std::chrono::milliseconds duration) {
    std::this_thread::sleep_for(duration);
}

/// Restores the clock settings touched by the tests.
struct ScopedClockSettings {
    Settings::InitClock init_clock = Settings::values.init_clock.GetValue();
    s64 init_time_offset = Settings::values.init_time_offset.GetValue();
    ~ScopedClockSettings() {
        Settings::values.init_clock = init_clock;
        Settings::values.init_time_offset = init_time_offset;
    }
};

} // namespace

TEST_CASE("SharedPage::Handler::ResyncWithHostClock", "[core][kernel][shared_page]") {
    ScopedClockSettings restore_settings;
    Settings::values.init_time_offset = 0;
    // Fixed base ticks keep the emulated uptime deterministic. Catch2 re-runs the test body for
    // every SECTION, so each section gets a fresh Timing and Handler.
    Core::Timing timing(1, 100, /*override_base_ticks=*/0);

    SECTION("advances the clock by host time that passed while emulated time stood still") {
        Settings::values.init_clock = Settings::InitClock::SystemTime;
        SharedPage::Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();

        EnterFirstSlice(timing);
        REQUIRE(page.date_time_counter == 1);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        PauseFor(std::chrono::milliseconds(2100));

        REQUIRE(handler.ResyncWithHostClock());

        // Exactly one DateTime entry was published, in the slot the guest reads next.
        REQUIRE(page.date_time_counter == counter_before + 1);
        const auto& published = (counter_before % 2) ? page.date_time_0 : page.date_time_1;
        REQUIRE(published.update_tick == static_cast<u64>(timing.GetTicks()));
        REQUIRE(published.tick_to_second_coefficient == BASE_CLOCK_RATE_ARM11);
        REQUIRE(published.tick_offset == 0);
        REQUIRE(published.date_time == handler.GetSystemTimeSince1900());

        // The guest clock caught up by the length of the pause in whole seconds (2 s, with a
        // generous allowance for a slow or preempted host).
        const u64 guest_after = handler.GetSystemTimeSince2000();
        const s64 advanced_ms = static_cast<s64>(guest_after) - static_cast<s64>(guest_before);
        REQUIRE(advanced_ms >= 2000);
        REQUIRE(advanced_ms <= 10000);

        // The hourly update was re-armed exactly once; the previous pending event was removed.
        AdvanceTicks(timing, ONE_HOUR_CYCLES + ONE_SECOND_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 2);
    }

    SECTION("never moves the clock backwards") {
        Settings::values.init_clock = Settings::InitClock::SystemTime;
        SharedPage::Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();

        EnterFirstSlice(timing);
        // Emulated time runs three hours ahead of the host in (almost) zero host time. Real
        // hardware never runs its clock backwards across a sleep, so the resync must leave the
        // guest clock alone.
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        REQUIRE_FALSE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before);

        // The guest is still far ahead of the host, so a real pause is absorbed by that surplus
        // instead of being added on top of it.
        PauseFor(std::chrono::milliseconds(1100));
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before);
    }

    SECTION("second resync right after the first is a no-op") {
        Settings::values.init_clock = Settings::InitClock::SystemTime;
        SharedPage::Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();

        EnterFirstSlice(timing);
        PauseFor(std::chrono::milliseconds(1100));
        REQUIRE(handler.ResyncWithHostClock());
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        // Frontends may request a resync more than once per resume; the first one already
        // accounted for the whole pause and less than a second is left over.
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before);
    }

    SECTION("is a no-op with a fixed clock") {
        Settings::values.init_clock = Settings::InitClock::FixedTime;
        SharedPage::Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();

        EnterFirstSlice(timing);
        PauseFor(std::chrono::milliseconds(1100));
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        REQUIRE_FALSE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before);
    }

    SECTION("is a no-op while a movie overrides the clock") {
        Settings::values.init_clock = Settings::InitClock::SystemTime;
        SharedPage::Handler handler(timing, /*override_init_time=*/1'700'000'000);
        auto& page = handler.GetSharedPage();

        EnterFirstSlice(timing);
        PauseFor(std::chrono::milliseconds(1100));
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        REQUIRE_FALSE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before);
    }
}
