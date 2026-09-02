// Copyright 2015-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <chrono>
#include <cstring>
#include <boost/serialization/base_object.hpp>
#include <boost/serialization/binary_object.hpp>
#include "common/archives.h"
#include "common/assert.h"
#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/core_timing.h"
#include "core/hle/kernel/shared_page.h"
#include "core/hle/service/ptm/ptm.h"
#include "core/movie.h"

SERIALIZE_EXPORT_IMPL(SharedPage::Handler)

namespace boost::serialization {

template <class Archive>
void load_construct_data(Archive& ar, SharedPage::Handler* t, const unsigned int) {
    ::new (t) SharedPage::Handler(Core::System::GetInstance().CoreTiming(),
                                  Core::System::GetInstance().Movie().GetOverrideInitTime());
}
template void load_construct_data<iarchive>(iarchive& ar, SharedPage::Handler* t,
                                            const unsigned int);

} // namespace boost::serialization

namespace SharedPage {

// Host time as seen by a console booted with InitClock::SystemTime (DST hour and offset applied)
static std::chrono::milliseconds GetHostInitTime() {
    auto now = std::chrono::system_clock::now();
    // If the system time is in daylight saving, we give an additional hour to console time
    std::time_t now_time_t = std::chrono::system_clock::to_time_t(now);
    std::tm* now_tm = std::localtime(&now_time_t);
    if (now_tm && now_tm->tm_isdst > 0)
        now = now + std::chrono::hours(1);

    // add the offset
    s64 init_time_offset = Settings::values.init_time_offset.GetValue();
    long long days_offset = init_time_offset / 86400;
    long long days_offset_in_seconds = days_offset * 86400; // h/m/s truncated
    unsigned long long seconds_offset =
        std::abs(init_time_offset) - std::abs(days_offset_in_seconds);

    now = now + std::chrono::seconds(seconds_offset);
    now = now + std::chrono::seconds(days_offset_in_seconds);
    return std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch());
}

/// Time the emulated console has been running, base ticks included
static std::chrono::milliseconds GetEmulatedTime(const Core::Timing& timing) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(timing.GetGlobalTimeUs());
}

/// The system clock at emulated time zero; a movie override takes precedence over the setting
static std::chrono::seconds GetInitTime(const Core::Timing& timing, u64 override_init_time) {
    if (override_init_time != 0) {
        // Override the clock init time with the one in the movie
        return std::chrono::seconds(override_init_time);
    }

    switch (Settings::values.init_clock.GetValue()) {
    case Settings::InitClock::SystemTime:
        // Anchor so that init_time + emulated time == host time now. Emulated time is not zero
        // here: at boot it holds the base ticks, after a savestate load the saved session's uptime
        return std::chrono::duration_cast<std::chrono::seconds>(GetHostInitTime() -
                                                                GetEmulatedTime(timing));
    case Settings::InitClock::FixedTime:
        return std::chrono::seconds(Settings::values.init_time.GetValue());
    default:
        UNREACHABLE_MSG("Invalid InitClock value ({})", Settings::values.init_clock.GetValue());
    }
}

Handler::Handler(Core::Timing& timing, u64 override_init_time)
    : timing(timing), override_init_time(override_init_time) {
    std::memset(&shared_page, 0, sizeof(shared_page));

    shared_page.running_hw = 0x1; // product

    // Some games wait until this value becomes 0x1, before asking running_hw
    shared_page.unknown_value = 0x1;

    // Set to a completely full battery
    shared_page.battery_state.charge_level.Assign(
        static_cast<u8>(Service::PTM::ChargeLevels::CompletelyFull));
    shared_page.battery_state.is_adapter_connected.Assign(1);
    shared_page.battery_state.is_charging.Assign(1);

    init_time = GetInitTime(timing, override_init_time);
    boot_init_time = init_time;
    boot_host_time = GetHostInitTime();
    boot_emulated_time = GetEmulatedTime(timing);

    using namespace std::placeholders;
    update_time_event = timing.RegisterEvent("SharedPage::UpdateTimeCallback",
                                             std::bind(&Handler::UpdateTimeCallback, this, _1, _2));
    timing.ScheduleEvent(0, update_time_event, 0, 0);

    float slidestate = Settings::values.factor_3d.GetValue() / 100.0f;
    shared_page.sliderstate_3d = static_cast<float_le>(slidestate);

    // TODO(PabloMK7)
    // Set wifi state to internet, to fake a connection from the NDM service.
    // Remove once it is figured out how NDM uses AC to connect at console boot.
    SetWifiLinkLevel(WifiLinkLevel::Best);
    SetWifiState(WifiState::Internet);
}

u64 Handler::GetSystemTimeSince2000() const {
    std::chrono::milliseconds now =
        init_time + std::chrono::duration_cast<std::chrono::milliseconds>(timing.GetGlobalTimeUs());

    // 3DS system does't allow user to set a time before Jan 1 2000,
    // so we use it as an auxiliary epoch to calculate the console time.
    std::tm epoch_tm;
    epoch_tm.tm_sec = 0;
    epoch_tm.tm_min = 0;
    epoch_tm.tm_hour = 0;
    epoch_tm.tm_mday = 1;
    epoch_tm.tm_mon = 0;
    epoch_tm.tm_year = 100;
    epoch_tm.tm_isdst = 0;
    s64 epoch = std::mktime(&epoch_tm) * 1000;

    // Only when system time is after 2000, we set it as 3DS system time
    if (now.count() > epoch) {
        return now.count() - epoch;
    } else {
        return 0;
    }
}

u64 Handler::GetSystemTimeSince1900() const {
    // 3DS console time uses Jan 1 1900 as internal epoch,
    // so we use the milliseconds between 1900 and 2000 as base console time
    return 3155673600000ULL + GetSystemTimeSince2000();
}

bool Handler::ResyncWithHostClock() {
    if (override_init_time != 0) {
        // Movie playback and recording must stay deterministic
        return false;
    }
    if (Settings::values.init_clock.GetValue() != Settings::InitClock::SystemTime) {
        // The clock was configured to be independent of the host
        return false;
    }

    // Ticks stop while paused, so since boot the host clock has gained exactly the missed time on
    // the emulated clock. Subtract what earlier resyncs already added to init_time.
    const auto host_elapsed = GetHostInitTime() - boot_host_time;
    const auto emulated_elapsed = GetEmulatedTime(timing) - boot_emulated_time;
    const auto applied = init_time - boot_init_time;
    const auto deficit = host_elapsed - emulated_elapsed - applied;
    // Whole seconds only; the remainder is carried over to the next resync
    const auto missed = std::chrono::duration_cast<std::chrono::seconds>(deficit);

    if (missed <= std::chrono::seconds::zero()) {
        // In step with or ahead of the host; the clock never moves backwards, as on hardware
        LOG_DEBUG(Kernel, "Clock resync skipped, deficit is {} ms", deficit.count());
        return false;
    }

    init_time += missed;

    // PTM publishes a new DateTime reference when the console wakes, so publish one now instead of
    // waiting for the hourly update. UpdateTimeCallback re-arms the event removed here.
    timing.RemoveEvent(update_time_event);
    UpdateTimeCallback(0, 0);

    LOG_INFO(Kernel, "Advanced system clock by {} s to resync with host", missed.count());
    return true;
}

void Handler::UpdateTimeCallback(std::uintptr_t user_data, int cycles_late) {
    DateTime& date_time =
        shared_page.date_time_counter % 2 ? shared_page.date_time_0 : shared_page.date_time_1;

    date_time.date_time = GetSystemTimeSince1900();
    date_time.update_tick = timing.GetTicks();
    date_time.tick_to_second_coefficient = BASE_CLOCK_RATE_ARM11;
    date_time.tick_offset = 0;

    ++shared_page.date_time_counter;

    // system time is updated hourly
    timing.ScheduleEvent(msToCycles(60 * 60 * 1000) - cycles_late, update_time_event);
}

void Handler::SetMacAddress(const MacAddress& addr) {
    std::memcpy(shared_page.wifi_macaddr, addr.data(), sizeof(MacAddress));
}

MacAddress Handler::GetMacAddress() {
    MacAddress addr;
    std::memcpy(addr.data(), shared_page.wifi_macaddr, sizeof(MacAddress));
    return addr;
}

void Handler::SetWifiLinkLevel(WifiLinkLevel level) {
    shared_page.wifi_link_level = static_cast<u8>(level);
}

WifiLinkLevel Handler::GetWifiLinkLevel() {
    return static_cast<WifiLinkLevel>(shared_page.wifi_link_level);
}

void Handler::SetWifiState(WifiState state) {
    shared_page.wifi_state = static_cast<u8>(state);
}

void Handler::Set3DLed(u8 state) {
    shared_page.ledstate_3d = state;
}

void Handler::Set3DSlider(float slidestate) {
    shared_page.sliderstate_3d = static_cast<float_le>(slidestate);
}

SharedPageDef& Handler::GetSharedPage() {
    return shared_page;
}

template <class Archive>
void Handler::serialize(Archive& ar, const unsigned int) {
    ar& boost::serialization::base_object<BackingMem>(*this);
    ar& boost::serialization::make_binary_object(&shared_page, sizeof(shared_page));
}
SERIALIZE_IMPL(Handler)

} // namespace SharedPage
