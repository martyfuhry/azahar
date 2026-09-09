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

    /// Generation 0 keeps the file name a build that knew only one autosave would have written
    std::string AutoSavePath(u32 generation = 0) const {
        const std::string suffix =
            generation == 0 ? "autosave" : fmt::format("autosave{}", generation);
        return fmt::format("{}{:016X}.{}.cst", FileUtil::GetUserPath(FileUtil::UserPath::StatesDir),
                           program_id, suffix);
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

void WriteAutoSave(const ScratchStatesDir& states, u64 time, std::string_view build_version,
                   u32 generation = 0) {
    const auto header = MakeHeader(time, build_version);
    FileUtil::IOFile file(states.AutoSavePath(generation), "wb");
    REQUIRE(file.IsOpen());
    REQUIRE(file.WriteBytes(header.data(), header.size()) == header.size());
}

Core::SaveStateInfo AutoSave(u64 time, Core::SaveStateInfo::ValidationStatus status,
                             u32 generation = 0) {
    Core::SaveStateInfo info{};
    info.slot = Core::AutoSaveStateSlot + generation;
    info.time = time;
    info.status = status;
    return info;
}

/// The generations as ListAutoSaveStates hands them over: newest first
std::vector<Core::SaveStateInfo> Newest(std::vector<Core::SaveStateInfo> generations) {
    std::stable_sort(generations.begin(), generations.end(),
                     [](const Core::SaveStateInfo& lhs, const Core::SaveStateInfo& rhs) {
                         return lhs.time > rhs.time;
                     });
    return generations;
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

TEST_CASE("Core::SelectAutoSaveState", "[core][savestate]") {
    using Core::AutoSaveResumeStatus;
    using Status = Core::SaveStateInfo::ValidationStatus;
    constexpr u64 last_boot = 1'800'000'000;

    SECTION("reports None and an empty header when the ring is empty") {
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState({}, last_boot, &selected) == AutoSaveResumeStatus::None);
        REQUIRE(selected.slot == std::numeric_limits<u32>::max());
    }

    SECTION("resumes from the newest generation, whichever slot holds it") {
        const auto generations = Newest({AutoSave(last_boot + 10, Status::OK, 0),
                                         AutoSave(last_boot + 90, Status::OK, 1),
                                         AutoSave(last_boot + 50, Status::OK, 2)});
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState(generations, last_boot, &selected) ==
                AutoSaveResumeStatus::Resumable);
        REQUIRE(selected.slot == Core::AutoSaveStateSlot + 1);
        REQUIRE(selected.time == last_boot + 90);
    }

    SECTION("passes over a generation this build cannot load in favour of an older one it can") {
        const auto generations = Newest({AutoSave(last_boot + 90, Status::BuildMismatch, 0),
                                         AutoSave(last_boot + 50, Status::OK, 1)});
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState(generations, last_boot, &selected) ==
                AutoSaveResumeStatus::Resumable);
        REQUIRE(selected.slot == Core::AutoSaveStateSlot + 1);
    }

    SECTION("only reports a build mismatch when no generation is loadable, and names the newest") {
        const auto generations = Newest({AutoSave(last_boot + 50, Status::BuildMismatch, 0),
                                         AutoSave(last_boot + 90, Status::BuildMismatch, 1)});
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState(generations, last_boot, &selected) ==
                AutoSaveResumeStatus::BuildMismatch);
        REQUIRE(selected.time == last_boot + 90);
    }

    SECTION("reports the newest generation stale rather than digging for an older one, because "
            "an older one can only be staler") {
        const auto generations = Newest(
            {AutoSave(last_boot - 10, Status::OK, 0), AutoSave(last_boot - 400, Status::OK, 1)});
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState(generations, last_boot, &selected) ==
                AutoSaveResumeStatus::Stale);
        REQUIRE(selected.time == last_boot - 10);
    }
}

TEST_CASE("Core::PickAutoSaveWriteSlot", "[core][savestate]") {
    using Status = Core::SaveStateInfo::ValidationStatus;
    constexpr u64 base = 1'800'000'000;

    SECTION("claims generation 0 first, so a title played once keeps a single file") {
        REQUIRE(Core::PickAutoSaveWriteSlot(std::vector<Core::SaveStateInfo>{}) ==
                Core::AutoSaveStateSlot);
    }

    SECTION("claims a generation that does not exist yet before reusing any that does") {
        const auto generations = Newest({AutoSave(base, Status::OK, 0)});
        REQUIRE(Core::PickAutoSaveWriteSlot(generations) == Core::AutoSaveStateSlot + 1);
    }

    SECTION("fills the gap a deleted generation leaves rather than overwriting a live one") {
        const auto generations =
            Newest({AutoSave(base, Status::OK, 0), AutoSave(base + 10, Status::OK, 2)});
        REQUIRE(Core::PickAutoSaveWriteSlot(generations) == Core::AutoSaveStateSlot + 1);
    }

    SECTION("overwrites the oldest generation once the ring is full") {
        const auto generations =
            Newest({AutoSave(base + 30, Status::OK, 0), AutoSave(base + 10, Status::OK, 1),
                    AutoSave(base + 20, Status::OK, 2)});
        REQUIRE(Core::PickAutoSaveWriteSlot(generations) == Core::AutoSaveStateSlot + 1);
    }

    SECTION("does not write over the selected generation when it is also the oldest, which "
            "happens as soon as everything newer is from a build that cannot be loaded") {
        // The oldest generation is only reliably a different one from the selected generation
        // while every generation is loadable. SelectAutoSaveState returns the newest *loadable*
        // one, so a full ring whose two newest entries are BuildMismatch selects the oldest --
        // and picking "the oldest" to write to would then destroy the only state on the machine
        // this build can read, the very one the user is looking at in the resume dialog.
        const auto generations = Newest({AutoSave(base + 30, Status::BuildMismatch, 0),
                                         AutoSave(base + 10, Status::OK, 1),
                                         AutoSave(base + 20, Status::BuildMismatch, 2)});
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState(generations, base - 1, &selected) ==
                Core::AutoSaveResumeStatus::Resumable);
        REQUIRE(selected.slot == Core::AutoSaveStateSlot + 1);
        // the oldest entry really is the selected one, which is what used to make this unsafe
        REQUIRE(generations.back().slot == selected.slot);
        REQUIRE(Core::PickAutoSaveWriteSlot(generations, selected.slot) != selected.slot);
        // and it falls back to the next-oldest rather than to a free slot that does not exist
        REQUIRE(Core::PickAutoSaveWriteSlot(generations, selected.slot) ==
                Core::AutoSaveStateSlot + 2);
    }

    SECTION("same when the one loadable generation is a revision mismatch rather than OK") {
        const auto generations = Newest({AutoSave(base + 30, Status::BuildMismatch, 0),
                                         AutoSave(base + 10, Status::RevisionMismatch, 1),
                                         AutoSave(base + 20, Status::BuildMismatch, 2)});
        Core::SaveStateInfo selected{};
        REQUIRE(Core::SelectAutoSaveState(generations, base - 1, &selected) ==
                Core::AutoSaveResumeStatus::Resumable);
        REQUIRE(Core::PickAutoSaveWriteSlot(generations, selected.slot) != selected.slot);
    }

    SECTION("never picks the generation a boot would resume from, over every shape of ring and "
            "every mix of loadable and unloadable generations") {
        // The field failure this whole ring exists for: a good state, a session that boots, is
        // killed before it autosaves, and boots again. Whatever the session writes, the state it
        // was offered is still on disk afterwards. Iterating the statuses is the point -- the
        // first version of this test used Status::OK throughout, so it never reached the branch
        // where the selected generation is also the oldest, and passed while that was broken.
        constexpr std::array statuses = {Status::OK, Status::RevisionMismatch,
                                         Status::BuildMismatch};
        std::vector<std::vector<Core::SaveStateInfo>> rings;
        rings.emplace_back();
        rings.push_back(Newest({AutoSave(base, Status::OK, 0)}));
        for (const auto s0 : statuses) {
            for (const auto s1 : statuses) {
                for (const auto s2 : statuses) {
                    rings.push_back(Newest({AutoSave(base + 30, s0, 0), AutoSave(base + 10, s1, 1),
                                            AutoSave(base + 20, s2, 2)}));
                    // and a ring with a hole, where a free slot is available instead
                    rings.push_back(
                        Newest({AutoSave(base + 30, s0, 0), AutoSave(base + 10, s1, 2)}));
                }
            }
        }
        for (const auto& generations : rings) {
            Core::SaveStateInfo selected{};
            const auto status = Core::SelectAutoSaveState(generations, base - 1, &selected);
            if (status == Core::AutoSaveResumeStatus::None) {
                continue;
            }
            const u32 write = Core::PickAutoSaveWriteSlot(generations, selected.slot);
            INFO("ring of " << generations.size() << ", selected slot " << selected.slot
                            << ", write slot " << write);
            REQUIRE(write != selected.slot);
            REQUIRE(Core::IsAutoSaveSlot(write));
        }
    }
}

TEST_CASE("Core::ListAutoSaveStates", "[core][savestate]") {
    ScratchStatesDir states;
    constexpr u64 movie_id = 0;
    constexpr u64 base = 1'800'000'000;

    SECTION("is empty when the title has never been autosaved") {
        REQUIRE(Core::ListAutoSaveStates(program_id, movie_id).empty());
    }

    SECTION("returns every generation on disk, newest first") {
        WriteAutoSave(states, base + 10, Common::g_build_version, 0);
        WriteAutoSave(states, base + 90, Common::g_build_version, 1);
        WriteAutoSave(states, base + 50, Common::g_build_version, 2);
        const auto generations = Core::ListAutoSaveStates(program_id, movie_id);
        REQUIRE(generations.size() == 3);
        REQUIRE(generations[0].slot == Core::AutoSaveStateSlot + 1);
        REQUIRE(generations[1].slot == Core::AutoSaveStateSlot + 2);
        REQUIRE(generations[2].slot == Core::AutoSaveStateSlot + 0);
    }

    SECTION("skips a generation whose file is truncated instead of failing the whole listing") {
        WriteAutoSave(states, base + 10, Common::g_build_version, 0);
        REQUIRE(FileUtil::WriteStringToFile(true, states.AutoSavePath(1), "not a state") == 11);
        const auto generations = Core::ListAutoSaveStates(program_id, movie_id);
        REQUIRE(generations.size() == 1);
        REQUIRE(generations[0].slot == Core::AutoSaveStateSlot);
        // ...and that generation is free for the next session to claim
        REQUIRE(Core::PickAutoSaveWriteSlot(program_id, movie_id) == Core::AutoSaveStateSlot + 1);
    }

    SECTION("keeps every generation out of the user-facing slot list") {
        for (u32 generation = 0; generation < Core::AutoSaveGenerationCount; ++generation) {
            WriteAutoSave(states, base + generation, Common::g_build_version, generation);
        }
        REQUIRE(Core::ListSaveStates(program_id, movie_id).empty());
    }
}

TEST_CASE("Core::CheckAutoSaveState rotation", "[core][savestate]") {
    using Core::AutoSaveResumeStatus;
    ScratchStatesDir states;
    constexpr u64 movie_id = 0;
    constexpr u64 last_boot = 1'800'000'000;
    Core::RecordNormalBoot(program_id, last_boot);

    SECTION("reads a state left in the single-file layout of an older build") {
        // Exactly what an upgrade finds on disk: one <title>.autosave.cst and nothing else
        WriteAutoSave(states, last_boot + 5, Common::g_build_version);
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::Resumable);
        REQUIRE(info.slot == Core::AutoSaveStateSlot);
        REQUIRE(info.time == last_boot + 5);
        // and the session that reads it writes somewhere else, so it survives
        REQUIRE(Core::PickAutoSaveWriteSlot(program_id, movie_id) != info.slot);
    }

    SECTION("resumes from a newer generation than the legacy file") {
        WriteAutoSave(states, last_boot + 5, Common::g_build_version, 0);
        WriteAutoSave(states, last_boot + 40, Common::g_build_version, 1);
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::Resumable);
        REQUIRE(info.slot == Core::AutoSaveStateSlot + 1);
    }

    SECTION("falls back to a loadable generation when the newest is from another build") {
        WriteAutoSave(states, last_boot + 40, "not-this-build", 1);
        WriteAutoSave(states, last_boot + 5, Common::g_build_version, 0);
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::Resumable);
        REQUIRE(info.slot == Core::AutoSaveStateSlot);
    }

    SECTION("offers a stale state rather than reporting nothing to offer") {
        WriteAutoSave(states, last_boot - 5, Common::g_build_version, 0);
        Core::SaveStateInfo info{};
        REQUIRE(Core::CheckAutoSaveState(program_id, movie_id, &info) ==
                AutoSaveResumeStatus::Stale);
        REQUIRE(info.slot == Core::AutoSaveStateSlot);
        REQUIRE(info.time == last_boot - 5);
    }
}
