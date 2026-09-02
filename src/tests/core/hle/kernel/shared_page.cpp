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

namespace SharedPage {

static constexpr s64 ONE_SECOND_CYCLES = msToCycles(1000);
static constexpr s64 ONE_MINUTE_CYCLES = msToCycles(60 * 1000);
static constexpr s64 ONE_HOUR_CYCLES = msToCycles(60 * 60 * 1000);

static constexpr s64 ONE_MINUTE = 60;
static constexpr s64 ONE_HOUR = 60 * 60;
static constexpr s64 ONE_DAY = 24 * 60 * 60;
static constexpr u64 MS_BETWEEN_1900_AND_2000 = 3155673600000ULL;
// A fixed clock in 2023; the default fixed time is before the year 2000 epoch in some time zones
static constexpr u64 FIXED_INIT_TIME = 1'700'000'000;

// Only whole seconds are resynced, so pause a little over a second to make one second due
static constexpr std::chrono::milliseconds ONE_SECOND_PAUSE{1100};

/// Restores the clock settings the tests change
struct ScopedClockSettings {
    Settings::InitClock init_clock = Settings::values.init_clock.GetValue();
    u64 init_time = Settings::values.init_time.GetValue();
    s64 init_time_offset = Settings::values.init_time_offset.GetValue();
    ~ScopedClockSettings() {
        Settings::values.init_clock = init_clock;
        Settings::values.init_time = init_time;
        Settings::values.init_time_offset = init_time_offset;
    }
};

/**
 * Moves the host clock as the handler sees it, without sleeping. The handler reads the host clock
 * with init_time_offset added, so changing the offset after boot looks like host time passing.
 * Only positive offsets are used because negative ones are not mirrored (the sub-day part is added
 * as its absolute value); the test case boots with a day of headroom so the clock can also go back.
 */
static void AdvanceHostClock(s64 seconds) {
    Settings::values.init_time_offset = Settings::values.init_time_offset.GetValue() + seconds;
}

/// Advances emulated time on core 0 slice by slice so that scheduled events fire on time
static void AdvanceTicks(Core::Timing& timing, s64 ticks) {
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

/// Enters slice 0, which runs the initial UpdateTimeCallback scheduled by the Handler constructor
static void EnterFirstSlice(Core::Timing& timing) {
    timing.GetTimer(0)->Advance();
    timing.GetTimer(0)->SetNextSlice();
}

/// The DateTime slot UpdateTimeCallback writes when the counter has the given value
static const DateTime& SlotFor(const SharedPageDef& page, u32 counter) {
    return counter % 2 ? page.date_time_0 : page.date_time_1;
}

/// Asserts that a resync neither advanced the clock nor published a DateTime entry
static void RequireUnchanged(Handler& handler, u32 counter_before, u64 guest_before) {
    REQUIRE(handler.GetSharedPage().date_time_counter == counter_before);
    REQUIRE(handler.GetSystemTimeSince2000() == guest_before);
}

/// Signed difference in milliseconds between two system times
static s64 MsBetween(u64 later, u64 earlier) {
    return static_cast<s64>(later) - static_cast<s64>(earlier);
}

/// Gives a handler constructed on the restored timing the shared page of the saved session
static void RestoreSavedPage(Handler& loaded, Handler& saved) {
    loaded.GetSharedPage() = saved.GetSharedPage();
}

TEST_CASE("SharedPage::Handler::ResyncWithHostClock", "[core][kernel][shared_page]") {
    ScopedClockSettings restore_settings;
    Settings::values.init_clock = Settings::InitClock::SystemTime;
    Settings::values.init_time = FIXED_INIT_TIME;
    Settings::values.init_time_offset = ONE_DAY;
    // Fixed base ticks keep the emulated uptime deterministic
    Core::Timing timing(1, 100, /*override_base_ticks=*/0);

    SECTION("advances the clock by the host time that passed while paused") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();

        EnterFirstSlice(timing);
        REQUIRE(page.date_time_counter == 1);
        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        // The host kept pace with the half hour of play, then emulation was paused for three hours
        AdvanceHostClock(30 * ONE_MINUTE + 3 * ONE_HOUR);
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + 3 * ONE_HOUR * 1000);
        REQUIRE(handler.GetSystemTimeSince1900() ==
                handler.GetSystemTimeSince2000() + MS_BETWEEN_1900_AND_2000);

        // Exactly one DateTime entry was published, in the slot the guest reads next
        REQUIRE(page.date_time_counter == counter_before + 1);
        const auto& published = SlotFor(page, counter_before);
        REQUIRE(published.update_tick == static_cast<u64>(timing.GetTicks()));
        REQUIRE(published.tick_to_second_coefficient == BASE_CLOCK_RATE_ARM11);
        REQUIRE(published.tick_offset == 0);
        REQUIRE(published.date_time == handler.GetSystemTimeSince1900());

        // The hourly update was moved from 30 minutes after boot to an hour after the resync
        AdvanceTicks(timing, 31 * ONE_MINUTE_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 1);
        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 2);
    }

    SECTION("carries the sub-second remainder over to the next resync") {
        Handler handler(timing, 0);
        EnterFirstSlice(timing);
        const u64 guest_before = handler.GetSystemTimeSince2000();

        // Host 2000 ms ahead of emulation: 1 s applied, 500 ms carried over
        AdvanceHostClock(2);
        AdvanceTicks(timing, ONE_SECOND_CYCLES / 2);
        const u64 emulated_ms = handler.GetSystemTimeSince2000() - guest_before;
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + emulated_ms + 1000);

        // Another 600 ms makes the carried remainder worth a second; nothing is applied twice
        AdvanceHostClock(1);
        AdvanceTicks(timing, ONE_SECOND_CYCLES * 2 / 5);
        const u64 emulated_ms_2 = handler.GetSystemTimeSince2000() - guest_before - 1000;
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + emulated_ms_2 + 2000);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
    }

    SECTION("second resync right after the first is a no-op") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);

        AdvanceHostClock(1);
        REQUIRE(handler.ResyncWithHostClock());
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        // Frontends may request more than one resync per resume
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);
    }

    SECTION("time gained by fast-forwarding absorbs later pauses") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);

        // Run emulated time three hours ahead of the host in (almost) no host time
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);

        AdvanceHostClock(ONE_HOUR);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);

        // The host has caught up exactly; the next second of pause is applied again
        AdvanceHostClock(2 * ONE_HOUR);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);

        AdvanceHostClock(1);
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before + 1);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + 1000);
    }

    SECTION("never moves the clock backwards when the host clock is set back") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        AdvanceHostClock(-2 * ONE_HOUR);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);

        AdvanceHostClock(2 * ONE_HOUR);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);

        AdvanceHostClock(5);
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before + 1);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + 5000);
    }

    SECTION("makes up for emulation running slower than the host") {
        Handler handler(timing, 0);
        EnterFirstSlice(timing);
        const u64 guest_before = handler.GetSystemTimeSince2000();

        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);
        AdvanceHostClock(ONE_HOUR);
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + ONE_HOUR * 1000);
    }

    SECTION("many resyncs accumulate exactly") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        // Each round emulates one second while the host gains eight, so seven are applied
        for (int i = 0; i < 100; ++i) {
            AdvanceTicks(timing, ONE_SECOND_CYCLES);
            AdvanceHostClock(8);
            REQUIRE(handler.ResyncWithHostClock());
        }
        REQUIRE(page.date_time_counter == counter_before + 100);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + 100 * 8 * 1000);
    }

    SECTION("alternates DateTime slots across publishes") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);

        for (int i = 0; i < 4; ++i) {
            const u32 counter_before = page.date_time_counter;
            const u64 previous_tick = SlotFor(page, counter_before + 1).update_tick;
            AdvanceTicks(timing, ONE_SECOND_CYCLES);
            AdvanceHostClock(2);
            REQUIRE(handler.ResyncWithHostClock());

            REQUIRE(page.date_time_counter == counter_before + 1);
            const auto& published = SlotFor(page, counter_before);
            REQUIRE(published.update_tick == static_cast<u64>(timing.GetTicks()));
            REQUIRE(published.date_time == handler.GetSystemTimeSince1900());
            // The slot the guest may still be reading is left alone
            REQUIRE(SlotFor(page, counter_before + 1).update_tick == previous_tick);
        }
    }

    SECTION("a no-op resync leaves the hourly update in place") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;

        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);
        REQUIRE_FALSE(handler.ResyncWithHostClock());

        AdvanceTicks(timing, 31 * ONE_MINUTE_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 1);
        AdvanceTicks(timing, ONE_HOUR_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 2);
    }

    SECTION("is a no-op with a fixed clock") {
        Settings::values.init_clock = Settings::InitClock::FixedTime;
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        AdvanceHostClock(ONE_HOUR);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);
    }

    SECTION("is a no-op while a movie overrides the clock") {
        Handler handler(timing, /*override_init_time=*/FIXED_INIT_TIME);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        AdvanceHostClock(ONE_HOUR);
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);
    }

    SECTION("switching from a fixed clock to the system clock resyncs from the fixed time") {
        Settings::values.init_clock = Settings::InitClock::FixedTime;
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        // The host time that passed since boot is applied on top of the fixed time; the clock
        // does not jump to the host time itself
        Settings::values.init_clock = Settings::InitClock::SystemTime;
        REQUIRE_FALSE(handler.ResyncWithHostClock());
        RequireUnchanged(handler, counter_before, guest_before);

        AdvanceHostClock(2 * ONE_HOUR);
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before + 1);
        REQUIRE(handler.GetSystemTimeSince2000() == guest_before + 2 * ONE_HOUR * 1000);
    }

    SECTION("end to end: a real pause is made up on resume") {
        Handler handler(timing, 0);
        auto& page = handler.GetSharedPage();
        EnterFirstSlice(timing);
        const u32 counter_before = page.date_time_counter;
        const u64 guest_before = handler.GetSystemTimeSince2000();

        std::this_thread::sleep_for(ONE_SECOND_PAUSE);
        REQUIRE(handler.ResyncWithHostClock());
        REQUIRE(page.date_time_counter == counter_before + 1);
        // The upper bound allows for a slow host
        const u64 advanced_ms = handler.GetSystemTimeSince2000() - guest_before;
        REQUIRE(advanced_ms >= 1000);
        REQUIRE(advanced_ms <= 10000);
    }
}

// Each handler truncates its anchor to a whole second, so two handlers constructed on the same
// host time can read up to a second apart, plus the wall time that passes between them
static constexpr s64 ANCHOR_TOLERANCE_MS = 2000;

TEST_CASE("SharedPage::Handler clock anchoring", "[core][kernel][shared_page]") {
    ScopedClockSettings restore_settings;
    Settings::values.init_clock = Settings::InitClock::SystemTime;
    Settings::values.init_time = FIXED_INIT_TIME;
    Settings::values.init_time_offset = ONE_DAY;
    Core::Timing timing(1, 100, /*override_base_ticks=*/0);

    SECTION("boots on the host time regardless of the base ticks") {
        Core::Timing large_base(1, 100, /*override_base_ticks=*/4'000'000'000);
        REQUIRE(large_base.GetGlobalTimeUs() > std::chrono::seconds(10));

        Handler from_zero(timing, 0);
        Handler from_large(large_base, 0);
        const s64 apart =
            MsBetween(from_large.GetSystemTimeSince2000(), from_zero.GetSystemTimeSince2000());
        REQUIRE(std::abs(apart) < ANCHOR_TOLERANCE_MS);
    }

    SECTION("reads the host time when constructed on a running timing") {
        Handler saved(timing, 0);
        EnterFirstSlice(timing);
        const u64 boot = saved.GetSystemTimeSince2000();

        // Three hours of play, then a savestate load in the same session
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        Handler loaded(timing, 0);
        REQUIRE(std::abs(MsBetween(loaded.GetSystemTimeSince2000(), boot)) < ANCHOR_TOLERANCE_MS);
        REQUIRE(MsBetween(saved.GetSystemTimeSince2000(), loaded.GetSystemTimeSince2000()) >
                3 * ONE_HOUR * 1000 - ANCHOR_TOLERANCE_MS);
    }

    SECTION("reads the host time when constructed after the host clock moved on") {
        Handler saved(timing, 0);
        EnterFirstSlice(timing);
        const u64 boot = saved.GetSystemTimeSince2000();

        // Three hours of play, the application closed for fourteen hours, then a savestate load
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        AdvanceHostClock(14 * ONE_HOUR);
        Handler loaded(timing, 0);
        const s64 since_boot = MsBetween(loaded.GetSystemTimeSince2000(), boot);
        REQUIRE(std::abs(since_boot - 14 * ONE_HOUR * 1000) < ANCHOR_TOLERANCE_MS);
    }

    SECTION("keeps the fixed clock of the saved session") {
        Settings::values.init_clock = Settings::InitClock::FixedTime;
        Handler saved(timing, 0);
        EnterFirstSlice(timing);
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        AdvanceHostClock(14 * ONE_HOUR);

        Handler loaded(timing, 0);
        REQUIRE(loaded.GetSystemTimeSince2000() == saved.GetSystemTimeSince2000());
    }

    SECTION("keeps the movie clock of the saved session") {
        Handler saved(timing, /*override_init_time=*/FIXED_INIT_TIME);
        EnterFirstSlice(timing);
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        AdvanceHostClock(14 * ONE_HOUR);

        Handler loaded(timing, /*override_init_time=*/FIXED_INIT_TIME);
        REQUIRE(loaded.GetSystemTimeSince2000() == saved.GetSystemTimeSince2000());
    }

    SECTION("OnSavestateLoaded publishes once and re-arms the hourly update") {
        Handler saved(timing, 0);
        EnterFirstSlice(timing);
        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);

        // Unlike a real load, the constructor's immediate publish is queued here; it is removed
        // together with the saved hourly update, so exactly one publish follows either way
        Handler loaded(timing, 0);
        RestoreSavedPage(loaded, saved);
        auto& page = loaded.GetSharedPage();
        const u32 counter_before = page.date_time_counter;
        REQUIRE(counter_before == 1);

        loaded.OnSavestateLoaded();
        REQUIRE(page.date_time_counter == counter_before + 1);
        const auto& published = SlotFor(page, counter_before);
        REQUIRE(published.update_tick == static_cast<u64>(timing.GetTicks()));
        REQUIRE(published.tick_to_second_coefficient == BASE_CLOCK_RATE_ARM11);
        REQUIRE(published.date_time == loaded.GetSystemTimeSince1900());

        // The saved hourly update was due in 30 minutes; only the re-armed one fires
        AdvanceTicks(timing, 31 * ONE_MINUTE_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 1);
        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);
        REQUIRE(page.date_time_counter == counter_before + 2);
    }

    SECTION("OnSavestateLoaded leaves a fixed clock alone") {
        Settings::values.init_clock = Settings::InitClock::FixedTime;
        Handler saved(timing, 0);
        EnterFirstSlice(timing);
        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);

        Handler loaded(timing, 0);
        RestoreSavedPage(loaded, saved);
        const u32 counter_before = loaded.GetSharedPage().date_time_counter;
        const u64 guest_before = loaded.GetSystemTimeSince2000();
        loaded.OnSavestateLoaded();
        RequireUnchanged(loaded, counter_before, guest_before);
    }

    SECTION("OnSavestateLoaded leaves a movie clock alone") {
        Handler saved(timing, /*override_init_time=*/FIXED_INIT_TIME);
        EnterFirstSlice(timing);
        AdvanceTicks(timing, 30 * ONE_MINUTE_CYCLES);

        Handler loaded(timing, /*override_init_time=*/FIXED_INIT_TIME);
        RestoreSavedPage(loaded, saved);
        const u32 counter_before = loaded.GetSharedPage().date_time_counter;
        const u64 guest_before = loaded.GetSystemTimeSince2000();
        loaded.OnSavestateLoaded();
        RequireUnchanged(loaded, counter_before, guest_before);
    }

    SECTION("a resync right after construction is a no-op") {
        Core::Timing large_base(1, 100, /*override_base_ticks=*/4'000'000'000);
        Handler booted(large_base, 0);
        REQUIRE_FALSE(booted.ResyncWithHostClock());

        Handler saved(timing, 0);
        EnterFirstSlice(timing);
        AdvanceTicks(timing, 3 * ONE_HOUR_CYCLES);
        AdvanceHostClock(14 * ONE_HOUR);

        Handler loaded(timing, 0);
        RestoreSavedPage(loaded, saved);
        u32 counter_before = loaded.GetSharedPage().date_time_counter;
        u64 guest_before = loaded.GetSystemTimeSince2000();
        REQUIRE_FALSE(loaded.ResyncWithHostClock());
        RequireUnchanged(loaded, counter_before, guest_before);

        loaded.OnSavestateLoaded();
        counter_before = loaded.GetSharedPage().date_time_counter;
        guest_before = loaded.GetSystemTimeSince2000();
        REQUIRE_FALSE(loaded.ResyncWithHostClock());
        RequireUnchanged(loaded, counter_before, guest_before);
    }
}

} // namespace SharedPage
