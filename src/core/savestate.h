// Copyright 2020-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <string>
#include <vector>
#include "common/common_types.h"

namespace Core {

struct SaveStateInfo {
    u32 slot;
    u64 time;
    enum class ValidationStatus {
        OK,
        RevisionMismatch,
        BuildMismatch,
    } status;
    std::string build_name;
    std::string build_version;
};

constexpr u32 SaveStateSlotCount = 11; // Maximum count of savestate slots

/**
 * Slot reserved for the frontend's automatic "save on exit" state. It lies well outside
 * [0, SaveStateSlotCount) so no slot picker ever lists it, and it is stored under its own
 * ".autosave.cst" file name rather than a numbered one, so a later increase of
 * SaveStateSlotCount cannot silently turn it into a user slot. The number itself is only a
 * routing key for SaveState/LoadState and Signal::Save/Signal::Load.
 */
constexpr u32 AutoSaveStateSlot = 1000;
static_assert(AutoSaveStateSlot >= SaveStateSlotCount);

/// Whether the autosave of a title should be offered when the title boots
enum class AutoSaveResumeStatus {
    None,          ///< No autosave exists for this title (or it is unreadable)
    Resumable,     ///< Written since the previous boot and loadable by this build
    Stale,         ///< Predates the previous boot: it was already offered or superseded
    BuildMismatch, ///< Written by an incompatible build; LoadState would refuse it
};

std::vector<SaveStateInfo> ListSaveStates(u64 program_id, u64 movie_id);

SaveStateInfo GetSaveStateInfo(u64 program_id, u64 movie_id, u32 slot);

/// GetSaveStateInfo for AutoSaveStateSlot
SaveStateInfo GetAutoSaveStateInfo(u64 program_id, u64 movie_id);

/// Unix time (seconds) recorded by the last RecordNormalBoot for the title, 0 if none
u64 GetLastNormalBootTime(u64 program_id);

/// Remembers that the frontend booted the title at `time` (Unix seconds)
void RecordNormalBoot(u64 program_id, u64 time);

/**
 * Decides what to do with an autosave. `autosave` comes from GetAutoSaveStateInfo and
 * `last_boot_time` from GetLastNormalBootTime, read before RecordNormalBoot for this boot.
 * An autosave is only Resumable when it was written at or after the previous boot: once a
 * boot has had the chance to load it, the in-game save may have moved past it.
 */
AutoSaveResumeStatus ClassifyAutoSaveState(const SaveStateInfo& autosave, u64 last_boot_time);

/// ClassifyAutoSaveState on the files on disk; `info` receives the autosave header if wanted
AutoSaveResumeStatus CheckAutoSaveState(u64 program_id, u64 movie_id,
                                        SaveStateInfo* info = nullptr);

} // namespace Core
