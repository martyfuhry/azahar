// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <thread>
#include <catch2/catch_test_macros.hpp>
#include "core/core.h"

namespace Core {

// The system under test is never powered on, so TrimMemoryIfRequested() has no GPU to ask and
// only the request bookkeeping runs. That is the part the Android frontend depends on: its
// emulation thread parks on a condition variable whose predicate is IsMemoryTrimRequested(),
// so a request that is not consumed exactly once is either a missed trim or a wake-up loop.
TEST_CASE("Core::System memory trim requests", "[core]") {
    System system;

    SECTION("nothing is requested by default") {
        REQUIRE_FALSE(system.IsMemoryTrimRequested());
    }

    SECTION("a request is visible until it is carried out") {
        system.RequestMemoryTrim();
        REQUIRE(system.IsMemoryTrimRequested());

        system.TrimMemoryIfRequested();
        REQUIRE_FALSE(system.IsMemoryTrimRequested());
    }

    SECTION("carrying out a trim nobody asked for is a no-op") {
        system.TrimMemoryIfRequested();
        REQUIRE_FALSE(system.IsMemoryTrimRequested());

        // Repeated calls (the emulation thread parks and wakes for other reasons) stay quiet
        for (int i = 0; i < 8; ++i) {
            system.TrimMemoryIfRequested();
        }
        REQUIRE_FALSE(system.IsMemoryTrimRequested());
    }

    SECTION("several requests before the emulation thread runs collapse into one") {
        // Android reports UI_HIDDEN, then BACKGROUND, then MODERATE in quick succession
        system.RequestMemoryTrim();
        system.RequestMemoryTrim();
        system.RequestMemoryTrim();
        REQUIRE(system.IsMemoryTrimRequested());

        system.TrimMemoryIfRequested();
        REQUIRE_FALSE(system.IsMemoryTrimRequested());
    }

    SECTION("a request made while one is being carried out is not lost") {
        system.RequestMemoryTrim();
        system.TrimMemoryIfRequested();
        system.RequestMemoryTrim();
        REQUIRE(system.IsMemoryTrimRequested());

        system.TrimMemoryIfRequested();
        REQUIRE_FALSE(system.IsMemoryTrimRequested());
    }

    SECTION("requesting from another thread is seen by this one") {
        std::thread ui_thread{[&system] { system.RequestMemoryTrim(); }};
        ui_thread.join();
        REQUIRE(system.IsMemoryTrimRequested());

        system.TrimMemoryIfRequested();
        REQUIRE_FALSE(system.IsMemoryTrimRequested());
    }
}

} // namespace Core
