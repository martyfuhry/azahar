// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <cstddef>
#include <optional>
#include "common/common_types.h"
#include "video_core/shader/generator/profile.h"

namespace Vulkan {

/**
 * Bookkeeping for which title the Vulkan PipelineCache's disk-backed state belongs to. Kept free
 * of any Vulkan types so the decisions it makes can be unit tested on the host.
 *
 * It answers two questions the PipelineCache used to answer from `current_program_id` alone:
 *
 * - Whose driver pipeline cache is this, if anyone's? A PipelineCache starts out with program id
 *   0 and an empty (or no) driver cache that was never read from disk. Writing that out would
 *   create a `0000000000000000-*.bin` file for a title that does not exist, so nothing is saved
 *   until a title's cache has actually been loaded, and it is always saved under the id it was
 *   loaded for, never under an id that was set ahead of the next load.
 *
 * - Is a load for this title redundant? Loading a savestate rebuilds the renderer and
 *   System::serialize switches the new one to the running title, which loads the driver cache
 *   and the transferable shader caches and starts compiling every pipeline they list. A
 *   frontend that then asks for the title's default disk resources (Android does, after an
 *   autosave resume) would wait for all of that compilation to finish, throw the compiled driver
 *   cache away unsaved, reload the stale one from disk and compile everything a second time.
 */
class DiskCacheState {
public:
    /// The driver pipeline cache now holds what is on disk for `program_id`, serialized to `size`
    /// bytes (the header alone when there was no file).
    void OnDriverCacheLoaded(u64 program_id, std::size_t size) {
        driver_program_id = program_id;
        saved_driver_size = size;
    }

    /// The driver pipeline cache was written to disk at `size` bytes.
    void OnDriverCacheSaved(std::size_t size) {
        saved_driver_size = size;
    }

    /// The title whose driver cache is loaded, or nullopt if none was ever loaded.
    std::optional<u64> DriverCacheProgramId() const {
        return driver_program_id;
    }

    /**
     * Whether a driver cache that currently serializes to `current_size` bytes should be written.
     * Never for a cache that was not loaded for a title; otherwise only when the driver added
     * something since the last load or save, which keeps periodic flushes free.
     */
    bool ShouldSaveDriverCache(std::size_t current_size) const {
        return driver_program_id.has_value() && current_size != 0 &&
               current_size != saved_driver_size;
    }

    /// The transferable shader caches for `program_id` were initialized under `profile`.
    void OnShaderCachesLoaded(u64 program_id, const Pica::Shader::Profile& profile) {
        shader_program_id = program_id;
        shader_profile = profile;
    }

    /**
     * Whether both the driver cache and the shader caches are already loaded for `program_id`
     * under `profile`, so that loading them again would only redo the same work.
     */
    bool IsLoadedFor(u64 program_id, const Pica::Shader::Profile& profile) const {
        return driver_program_id == program_id && shader_program_id == program_id &&
               shader_profile == profile;
    }

private:
    std::optional<u64> driver_program_id;
    std::size_t saved_driver_size{0};
    std::optional<u64> shader_program_id;
    Pica::Shader::Profile shader_profile{};
};

} // namespace Vulkan
