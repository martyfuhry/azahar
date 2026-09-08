// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <cstring>
#include <istream>
#include <ostream>
#include <span>
#include <vector>
#include <catch2/benchmark/catch_benchmark.hpp>
#include <catch2/catch_test_macros.hpp>
#include "common/common_types.h"
#include "common/zstd_stream.h"

namespace {

/// Mixed runs and noise, like emulated RAM: compressible, but not trivially so
std::vector<u8> MakePayload(std::size_t size) {
    std::vector<u8> data(size);
    u32 lcg = 0x12345678;
    for (std::size_t i = 0; i < size; ++i) {
        if ((i / 64) % 3 == 0) {
            data[i] = static_cast<u8>(i / 64);
        } else {
            lcg = lcg * 1664525 + 1013904223;
            data[i] = static_cast<u8>(lcg >> 24);
        }
    }
    return data;
}

std::vector<u8> Compress(std::span<const u8> payload) {
    std::vector<u8> compressed;
    Common::Compression::ZSTDOutputStreamBuf out{[&](std::span<const u8> chunk) {
        compressed.insert(compressed.end(), chunk.begin(), chunk.end());
        return true;
    }};
    {
        std::ostream stream{&out};
        stream.write(reinterpret_cast<const char*>(payload.data()), payload.size());
    }
    if (!out.Finish()) {
        compressed.clear();
    }
    return compressed;
}

std::size_t Decompress(std::span<const u8> compressed, std::span<u8> scratch) {
    Common::Compression::ZSTDInputStreamBuf in{
        [compressed, offset = std::size_t{0}](std::span<u8> dest) mutable {
            const std::size_t piece = std::min(dest.size(), compressed.size() - offset);
            std::memcpy(dest.data(), compressed.data() + offset, piece);
            offset += piece;
            return piece;
        }};
    std::istream stream{&in};
    std::size_t total = 0;
    while (stream.read(reinterpret_cast<char*>(scratch.data()), scratch.size()) ||
           stream.gcount() > 0) {
        total += static_cast<std::size_t>(stream.gcount());
    }
    return in.Failed() ? 0 : total;
}

} // namespace

// Baseline for the savestate path (M-3/M-4 change how much memory is serialized): the same
// streambufs that SaveState/LoadState wrap around boost::serialization, on a fixed 16 MiB payload.
TEST_CASE("Common::Compression::ZSTDStreamBuf round trip", "[bench][common][zstd]") {
    const auto payload = MakePayload(16 * 1024 * 1024);
    const auto compressed = Compress(payload);
    std::vector<u8> scratch(64 * 1024);
    // Validate the round trip once, outside the measured bodies
    REQUIRE_FALSE(compressed.empty());
    REQUIRE(compressed.size() < payload.size());
    REQUIRE(Decompress(compressed, scratch) == payload.size());

    BENCHMARK("compress 16 MiB through ZSTDOutputStreamBuf") {
        return Compress(payload).size();
    };

    BENCHMARK("decompress 16 MiB through ZSTDInputStreamBuf") {
        return Decompress(compressed, scratch);
    };
}
