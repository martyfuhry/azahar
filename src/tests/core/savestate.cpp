// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <array>
#include <cstring>
#include <filesystem>
#include <limits>
#include <string>
#include <vector>
#include <catch2/catch_test_macros.hpp>
#include <fmt/format.h>
#include "common/common_types.h"
#include "common/file_util.h"
#include "common/scm_rev.h"
#include "core/savestate.h"

namespace {

constexpr u64 program_id = 0x0004000000055D00;

/// Points the states directory at a scratch directory for the duration of a test
struct ScratchStatesDir {
    std::string previous;
    std::string dir;

    ScratchStatesDir() {
        previous = FileUtil::GetUserPath(FileUtil::UserPath::StatesDir);
        dir = (std::filesystem::temp_directory_path() / "azahar-autosave-tests").string();
        std::filesystem::remove_all(dir);
        std::filesystem::create_directories(dir);
        FileUtil::UpdateUserPath(FileUtil::UserPath::StatesDir, dir);
    }

    ~ScratchStatesDir() {
        FileUtil::CreateFullPath(previous);
        FileUtil::UpdateUserPath(FileUtil::UserPath::StatesDir, previous);
        std::filesystem::remove_all(dir);
    }

    std::string AutoSavePath() const {
        return fmt::format("{}{:016X}.autosave.cst",
                           FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id);
    }
};

/**
 * A 256 byte CST header (see CSTHeader in savestate.cpp) whose revision never matches the
 * running build, so the header's build version alone decides between RevisionMismatch, which
 * LoadState accepts, and BuildMismatch, which it refuses.
 */
std::vector<u8> MakeHeader(u64 time, std::string_view build_version) {
    std::vector<u8> header(256, 0);
    constexpr std::array<u8, 4> magic{{'C', 'S', 'T', 0x1B}};
    std::memcpy(header.data(), magic.data(), magic.size());
    std::memcpy(header.data() + 4, &program_id, sizeof(program_id));
    // bytes 12..32 hold the revision: all zero
    std::memcpy(header.data() + 32, &time, sizeof(time));
    constexpr std::string_view build_name = "test build";
    std::memcpy(header.data() + 40, build_name.data(), build_name.size());
    // bytes 60..64 must be zero for the build name to count, 84..88 for the version
    std::memcpy(header.data() + 64, build_version.data(),
                std::min<std::size_t>(build_version.size(), 19));
    return header;
}

void WriteAutoSave(const ScratchStatesDir& states, u64 time, std::string_view build_version) {
    const auto header = MakeHeader(time, build_version);
    FileUtil::IOFile file(states.AutoSavePath(), "wb");
    REQUIRE(file.IsOpen());
    REQUIRE(file.WriteBytes(header.data(), header.size()) == header.size());
}

Core::SaveStateInfo AutoSave(u64 time, Core::SaveStateInfo::ValidationStatus status) {
    Core::SaveStateInfo info{};
    info.slot = Core::AutoSaveStateSlot;
    info.time = time;
    info.status = status;
    return info;
}

} // namespace

TEST_CASE("Core::ClassifyAutoSaveState", "[core][savestate]") {
    using Core::AutoSaveResumeStatus;
    using Status = Core::SaveStateInfo::ValidationStatus;
    constexpr u64 last_boot = 1'800'000'000;

    SECTION("the reserved slot never overlaps a user slot") {
        STATIC_REQUIRE(Core::AutoSaveStateSlot >= Core::SaveStateSlotCount);
    }

    SECTION("reports None when there is no autosave") {
        Core::SaveStateInfo missing{};
        missing.slot = std::numeric_limits<u32>::max();
        REQUIRE(Core::ClassifyAutoSaveState(missing, last_boot) == AutoSaveResumeStatus::None);
    }

    SECTION("offers an autosave written after the previous boot") {
        REQUIRE(Core::ClassifyAutoSaveState(AutoSave(last_boot + 60, Status::OK), last_boot) ==
                AutoSaveResumeStatus::Resumable);
    }

    SECTION("offers an autosave written in the same second as the previous boot") {
        REQUIRE(Core::ClassifyAutoSaveState(AutoSave(last_boot, Status::OK), last_boot) ==
                AutoSaveResumeStatus::Resumable);
    }

    SECTION("offers any autosave when no boot was ever recorded") {
        REQUIRE(Core::ClassifyAutoSaveState(AutoSave(1, Status::OK), 0) ==
                AutoSaveResumeStatus::Resumable);
    }

    SECTION("treats an autosave older than the previous boot as stale") {
        REQUIRE(Core::ClassifyAutoSaveState(AutoSave(last_boot - 1, Status::OK), last_boot) ==
                AutoSaveResumeStatus::Stale);
    }

    SECTION("still offers a state from another revision of the same build, as LoadState "
            "accepts those") {
        REQUIRE(Core::ClassifyAutoSaveState(AutoSave(last_boot + 1, Status::RevisionMismatch),
                                            last_boot) == AutoSaveResumeStatus::Resumable);
    }

    SECTION("reports a build mismatch even for a fresh autosave, so it is skipped instead of "
            "failing the load") {
        REQUIRE(Core::ClassifyAutoSaveState(AutoSave(last_boot + 1, Status::BuildMismatch),
                                            last_boot) == AutoSaveResumeStatus::BuildMismatch);
    }
}

TEST_CASE("Core::RecordNormalBoot", "[core][savestate]") {
    ScratchStatesDir states;

    SECTION("reports no boot before one is recorded") {
        REQUIRE(Core::GetLastNormalBootTime(program_id) == 0);
    }

    SECTION("round-trips the recorded time and keeps only the latest") {
        Core::RecordNormalBoot(program_id, 1'700'000'000);
        REQUIRE(Core::GetLastNormalBootTime(program_id) == 1'700'000'000);
        Core::RecordNormalBoot(program_id, 1'700'000'500);
        REQUIRE(Core::GetLastNormalBootTime(program_id) == 1'700'000'500);
    }

    SECTION("keeps titles apart") {
        Core::RecordNormalBoot(program_id, 1'700'000'000);
        REQUIRE(Core::GetLastNormalBootTime(program_id + 1) == 0);
    }

    SECTION("treats a corrupt marker as no boot") {
        Core::RecordNormalBoot(program_id, 1'700'000'000);
        const auto path = fmt::format(
            "{}{:016X}.lastboot", FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id);
        REQUIRE(FileUtil::WriteStringToFile(true, path, "yesterday") == 9);
        REQUIRE(Core::GetLastNormalBootTime(program_id) == 0);
    }
}

TEST_CASE("Core::CheckAutoSaveState", "[core][savestate]") {
    using Core::AutoSaveResumeStatus;
    ScratchStatesDir states;
    constexpr u64 movie_id = 0;
    constexpr u64 last_boot = 1'800'000'000;
    Core::RecordNormalBoot(program_id, last_boot);

    SECTION("reports None when the title has no autosave file") {
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::None);
        REQUIRE(info.slot == std::numeric_limits<u32>::max());
    }

    SECTION("reads the autosave from its own file name and offers it when fresh") {
        WriteAutoSave(states, last_boot + 5, Common::g_build_version);
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::Resumable);
        REQUIRE(info.slot == Core::AutoSaveStateSlot);
        REQUIRE(info.time == last_boot + 5);
    }

    SECTION("does not offer an autosave that predates the previous boot") {
        WriteAutoSave(states, last_boot - 5, Common::g_build_version);
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id) == AutoSaveResumeStatus::Stale);
    }

    SECTION("flags an autosave from an incompatible build and names that build") {
        WriteAutoSave(states, last_boot + 5, "not-this-build");
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::BuildMismatch);
        REQUIRE(info.build_name == "test build");
        REQUIRE(info.build_version == "not-this-build");
    }

    SECTION("keeps the autosave out of the user-facing slot list") {
        WriteAutoSave(states, last_boot + 5, Common::g_build_version);
        REQUIRE(Core::ListSaveStates(program_id, movie_id).empty());
    }
}
