// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <memory>
#include <catch2/benchmark/catch_benchmark.hpp>
#include <catch2/catch_test_macros.hpp>
#include "core/core.h"
#include "core/memory.h"

// Baseline for M-4 (MemoryRef shrink): a PageTable is ~49 MiB of pointer/ref/attribute arrays,
// zeroed on construction and again by Clear(). VMManager allocates one per process with
// make_shared, on boot and on every savestate load, so that is what is measured (the matching
// deallocation is included; it is an munmap and negligible next to faulting the pages in).
TEST_CASE("Memory::PageTable", "[bench][core][memory]") {
    BENCHMARK("make_shared<PageTable>() and release") {
        return std::make_shared<Memory::PageTable>();
    };

    auto table = std::make_shared<Memory::PageTable>();
    BENCHMARK("Clear") {
        table->Clear();
        return table->attributes[0];
    };
}

// Baseline for L-4 / M-3 (FCRAM allocated on demand instead of as a zeroed 256 MiB block): the
// MemorySystem constructor allocates and zeroes FCRAM, VRAM, N3DS extra RAM and DSP RAM, so its
// cost is dominated by faulting in ~267 MiB. System::Init does exactly this with make_unique.
TEST_CASE("Memory::MemorySystem", "[bench][core][memory]") {
    Core::System system;

    BENCHMARK("make_unique<MemorySystem>() and release") {
        return std::make_unique<Memory::MemorySystem>(system);
    };
}
