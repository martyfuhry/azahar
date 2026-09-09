// Copyright 2020-2026 yuzu Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include "common/common_types.h"

namespace Common {

struct MemoryInfo {
    u64 total_physical_memory{};
    u64 total_swap_memory{};
};

/**
 * Gets the memory info of the host system
 * @return Reference to a MemoryInfo struct with the physical and swap memory sizes in bytes
 */
[[nodiscard]] const MemoryInfo GetMemInfo();

/**
 * Gets the page size of the host system
 * @return Page size in bytes of the host system
 */
u64 GetPageSize();

/**
 * Hands memory the C allocator is holding on to, but is not using, back to the operating
 * system. Frontends call this when the OS reports memory pressure; the allocator reclaims the
 * pages again on demand, so the only cost is the page faults that follow. A no-op on hosts
 * whose allocator has no such call.
 */
void ReleaseFreeHostMemory();

} // namespace Common
