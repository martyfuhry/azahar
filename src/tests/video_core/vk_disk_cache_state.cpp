// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <catch2/catch_test_macros.hpp>
#include "video_core/renderer_vulkan/vk_disk_cache_state.h"

namespace {
constexpr u64 SuperMario3DLand = 0x0004000000054000;
constexpr u64 AnimalCrossing = 0x0004000000086300;
/// What vkGetPipelineCacheData reports for an empty cache: the 32-byte header alone
constexpr std::size_t EmptyCacheSize = 32;
constexpr std::size_t StaleCacheSize = 7655851;
} // namespace

TEST_CASE("Vulkan::DiskCacheState::ShouldSaveDriverCache", "[video_core][vulkan][disk_cache]") {
    Vulkan::DiskCacheState state;

    SECTION("does not save a cache that was never loaded for a title") {
        // A renderer a savestate load just built: program id 0, an empty driver cache, and
        // System::serialize about to switch it to the running title. Saving here used to write
        // 0000000000000000-*.bin.
        REQUIRE_FALSE(state.DriverCacheProgramId().has_value());
        REQUIRE_FALSE(state.ShouldSaveDriverCache(EmptyCacheSize));
        REQUIRE_FALSE(state.ShouldSaveDriverCache(StaleCacheSize));
    }

    SECTION("saves under the title the cache was loaded for") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        REQUIRE(state.DriverCacheProgramId() == SuperMario3DLand);
    }

    SECTION("skips the write when nothing was compiled since the load") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        REQUIRE_FALSE(state.ShouldSaveDriverCache(StaleCacheSize));
    }

    SECTION("writes once pipelines were compiled, and not again until more are") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        REQUIRE(state.ShouldSaveDriverCache(StaleCacheSize + 4096));
        state.OnDriverCacheSaved(StaleCacheSize + 4096);
        REQUIRE_FALSE(state.ShouldSaveDriverCache(StaleCacheSize + 4096));
        REQUIRE(state.ShouldSaveDriverCache(StaleCacheSize + 8192));
    }

    SECTION("writes a title's first cache even when there was no file to load") {
        state.OnDriverCacheLoaded(AnimalCrossing, EmptyCacheSize);
        REQUIRE_FALSE(state.ShouldSaveDriverCache(EmptyCacheSize));
        REQUIRE(state.ShouldSaveDriverCache(EmptyCacheSize + 1024));
    }

    SECTION("rewrites a title whose stale or invalid file was discarded") {
        // LoadDriverPipelineDiskCache deletes a file that fails IsCacheValid (other driver, other
        // GPU) and starts from an empty cache; the title must still get a fresh file
        state.OnDriverCacheLoaded(SuperMario3DLand, EmptyCacheSize);
        REQUIRE(state.ShouldSaveDriverCache(StaleCacheSize));
        REQUIRE(state.DriverCacheProgramId() == SuperMario3DLand);
    }

    SECTION("keeps saving under the loaded title until another title's cache is loaded") {
        // LoadDefaultDiskResources sets the next program id before it loads; a save in between
        // (the one LoadDriverPipelineDiskCache now does before replacing the cache) belongs to
        // the title that was compiled into it
        state.OnDriverCacheLoaded(AnimalCrossing, StaleCacheSize);
        REQUIRE(state.DriverCacheProgramId() == AnimalCrossing);
        state.OnDriverCacheLoaded(SuperMario3DLand, EmptyCacheSize);
        REQUIRE(state.DriverCacheProgramId() == SuperMario3DLand);
    }

    SECTION("never saves a driver that reports no data") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        REQUIRE_FALSE(state.ShouldSaveDriverCache(0));
    }
}

TEST_CASE("Vulkan::DiskCacheState::IsLoadedFor", "[video_core][vulkan][disk_cache]") {
    Vulkan::DiskCacheState state;
    Pica::Shader::Profile profile{};
    profile.is_vulkan = true;

    SECTION("a fresh renderer has nothing loaded") {
        REQUIRE_FALSE(state.IsLoadedFor(SuperMario3DLand, profile));
        REQUIRE_FALSE(state.IsLoadedFor(0, profile));
    }

    SECTION("a savestate load's switch makes the frontend's follow-up load redundant") {
        // System::serialize -> SwitchDiskResources on the rebuilt renderer...
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        state.OnShaderCachesLoaded(SuperMario3DLand, profile);
        // ...then the Android frontend's LoadDefaultDiskResources for the same title
        REQUIRE(state.IsLoadedFor(SuperMario3DLand, profile));
    }

    SECTION("autosave resume: one load for the title, nothing saved as title 0") {
        // RunCitra boots the title into a renderer that has loaded nothing yet...
        REQUIRE_FALSE(state.ShouldSaveDriverCache(EmptyCacheSize));
        // ...the resume's System::serialize builds a new renderer and switches it to the title.
        // SwitchCache saves the outgoing cache first: nothing, since no title was loaded
        Vulkan::DiskCacheState rebuilt;
        REQUIRE_FALSE(rebuilt.ShouldSaveDriverCache(EmptyCacheSize));
        rebuilt.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        rebuilt.OnShaderCachesLoaded(SuperMario3DLand, profile);
        // ...then RunCitra's LoadDiskResources asks for the same title again: skipped
        REQUIRE(rebuilt.IsLoadedFor(SuperMario3DLand, profile));
        // ...and what the workers compile reaches the title's own file
        REQUIRE(rebuilt.ShouldSaveDriverCache(StaleCacheSize + 65536));
        REQUIRE(rebuilt.DriverCacheProgramId() == SuperMario3DLand);
    }

    SECTION("a different title still loads") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        state.OnShaderCachesLoaded(SuperMario3DLand, profile);
        REQUIRE_FALSE(state.IsLoadedFor(AnimalCrossing, profile));
    }

    SECTION("a changed shader profile still loads") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        state.OnShaderCachesLoaded(SuperMario3DLand, profile);
        Pica::Shader::Profile accurate = profile;
        accurate.enable_accurate_mul = true;
        REQUIRE_FALSE(state.IsLoadedFor(SuperMario3DLand, accurate));
    }

    SECTION("a driver cache alone is not a full load") {
        state.OnDriverCacheLoaded(SuperMario3DLand, StaleCacheSize);
        REQUIRE_FALSE(state.IsLoadedFor(SuperMario3DLand, profile));
    }

    SECTION("a title without a program id is tracked like any other once loaded") {
        state.OnDriverCacheLoaded(0, EmptyCacheSize);
        state.OnShaderCachesLoaded(0, profile);
        REQUIRE(state.IsLoadedFor(0, profile));
        REQUIRE(state.DriverCacheProgramId() == u64{0});
    }
}
