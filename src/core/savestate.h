// Copyright 2020-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <limits>
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
 * First of the slots reserved for the frontend's automatic "save on exit" state. They lie well
 * outside [0, SaveStateSlotCount) so no slot picker ever lists them, and they are stored under
 * their own "autosave" file names rather than numbered ones, so a later increase of
 * SaveStateSlotCount cannot silently turn one into a user slot. The numbers themselves are only
 * routing keys for SaveState/LoadState and Signal::Save/Signal::Load.
 */
constexpr u32 AutoSaveStateSlot = 1000;
static_assert(AutoSaveStateSlot >= SaveStateSlotCount);

/**
 * How many autosave generations are kept per title, i.e. how many past sessions' final states
 * survive on disk. Slots AutoSaveStateSlot .. AutoSaveStateSlot + AutoSaveGenerationCount - 1
 * address them; generation 0 keeps the historical ".autosave.cst" name so that a state written
 * by an older build is simply the ring's first entry rather than something to migrate.
 *
 * A session writes to exactly one generation for its whole lifetime (PickAutoSaveWriteSlot) and
 * never touches the others, so the generations are not shifted along and there is no window in
 * which a kill leaves the ring short. Three is the smallest depth that survives the failure this
 * exists for: a good state, then two consecutive sessions that start cold and autosave over the
 * ring before the player notices. States run 7-24 MB, so the ceiling is ~72 MB for a title that
 * has actually been played three times, and one file for a title played once.
 */
constexpr u32 AutoSaveGenerationCount = 3;
// PickAutoSaveWriteSlot must always be able to avoid the generation a boot selected, even with
// the ring full. That needs at least two generations: with one, the only slot left to write to
// would be the very state the user was just offered.
static_assert(AutoSaveGenerationCount >= 2);

/// Whether `slot` addresses one of the autosave generations rather than a user slot
constexpr bool IsAutoSaveSlot(u32 slot) {
    return slot >= AutoSaveStateSlot && slot < AutoSaveStateSlot + AutoSaveGenerationCount;
}

/// Whether the autosave of a title should be offered when the title boots
enum class AutoSaveResumeStatus {
    None,          ///< No autosave exists for this title (or it is unreadable)
    Resumable,     ///< Written since the previous boot and loadable by this build
    Stale,         ///< Predates the previous boot: it was already offered or superseded
    BuildMismatch, ///< Written by an incompatible build; LoadState would refuse it
};

std::vector<SaveStateInfo> ListSaveStates(u64 program_id, u64 movie_id);

SaveStateInfo GetSaveStateInfo(u64 program_id, u64 movie_id, u32 slot);

/// Headers of every autosave generation that exists and parses, newest first
std::vector<SaveStateInfo> ListAutoSaveStates(u64 program_id, u64 movie_id);

/// The newest autosave generation this build can load, else the newest one at all
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
 *
 * Stale does not mean worthless. It means the state must not be loaded without asking, because
 * the player may have made in-game saves since; the frontend still owes them the choice.
 */
AutoSaveResumeStatus ClassifyAutoSaveState(const SaveStateInfo& autosave, u64 last_boot_time);

/**
 * Picks which of `generations` (as returned by ListAutoSaveStates) a boot should resume from and
 * classifies it, writing the chosen header to `selected` when given.
 *
 * A generation this build cannot load is passed over in favour of an older one it can, so
 * changing builds costs at most the newest generation instead of the whole ring; BuildMismatch
 * is only reported when no generation is loadable at all. Resumable and Stale need no such
 * tie-breaking: they are decided by the header time, so if the newest generation is stale every
 * older one is too.
 */
AutoSaveResumeStatus SelectAutoSaveState(const std::vector<SaveStateInfo>& generations,
                                         u64 last_boot_time, SaveStateInfo* selected = nullptr);

/// SelectAutoSaveState on the files on disk; `info` receives the chosen header if wanted
AutoSaveResumeStatus CheckAutoSaveState(u64 program_id, u64 movie_id,
                                        SaveStateInfo* info = nullptr);

/**
 * The autosave slot a session should write every one of its autosaves to, chosen from
 * `generations` (as returned by ListAutoSaveStates): a generation that does not exist yet, else
 * the one whose loss costs the least, i.e. the oldest.
 *
 * `avoid_slot` is the slot SelectAutoSaveState picked for this boot, and is never chosen while
 * any other generation could be. Passing it is not an optimisation, it is the correctness
 * condition: the oldest generation is *not* reliably a different one from the selected
 * generation, because SelectAutoSaveState returns the newest generation this build can *load*,
 * and that is the oldest one whenever everything newer is a BuildMismatch. Without this the
 * session would overwrite the state the user was offered -- with periodic saving on, possibly
 * while its "resume?" dialog is still on screen.
 *
 * Because the choice is made once per session rather than once per save, the ring holds the last
 * AutoSaveGenerationCount *sessions* rather than the last few minutes of one of them.
 */
u32 PickAutoSaveWriteSlot(const std::vector<SaveStateInfo>& generations,
                          u32 avoid_slot = std::numeric_limits<u32>::max());

/// PickAutoSaveWriteSlot on the files on disk
u32 PickAutoSaveWriteSlot(u64 program_id, u64 movie_id,
                          u32 avoid_slot = std::numeric_limits<u32>::max());

} // namespace Core
