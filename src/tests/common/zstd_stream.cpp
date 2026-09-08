// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <array>
#include <cstring>
#include <istream>
#include <ostream>
#include <span>
#include <vector>
#include <catch2/catch_test_macros.hpp>
#include "common/common_types.h"
#include "common/zstd_compression.h"
#include "common/zstd_stream.h"

namespace {

/// Several MiB of data that compresses but is not trivial, spanning many zstd blocks
std::vector<u8> MakePayload(std::size_t size) {
    std::vector<u8> data(size);
    u32 lcg = 0x12345678;
    for (std::size_t i = 0; i < size; ++i) {
        // Runs of repeated bytes interleaved with noise
        if ((i / 64) % 3 == 0) {
            data[i] = static_cast<u8>(i / 64);
        } else {
            lcg = lcg * 1664525 + 1013904223;
            data[i] = static_cast<u8>(lcg >> 24);
        }
    }
    return data;
}

/// Hands out `data` in uneven pieces, so chunk boundaries never line up with zstd's buffers
Common::Compression::ZSTDInputStreamBuf::Source ChunkedSource(std::span<const u8> data) {
    return [data, offset = std::size_t{0}](std::span<u8> out) mutable {
        const std::size_t piece = std::min({out.size(), data.size() - offset, std::size_t{7777}});
        std::memcpy(out.data(), data.data() + offset, piece);
        offset += piece;
        return piece;
    };
}

std::vector<u8> ReadAll(std::istream& stream) {
    std::vector<u8> out;
    std::array<char, 5000> piece{};
    while (stream.read(piece.data(), piece.size()) || stream.gcount() > 0) {
        out.insert(out.end(), piece.begin(), piece.begin() + stream.gcount());
    }
    return out;
}

} // namespace

TEST_CASE("Common::Compression::ZSTDOutputStreamBuf", "[common][zstd]") {
    using namespace Common::Compression;
    const auto payload = MakePayload(3 * 1024 * 1024);

    SECTION("streams a frame chunk by chunk that ZSTDInputStreamBuf reads back") {
        std::vector<u8> compressed;
        std::size_t chunks = 0;
        ZSTDOutputStreamBuf out{[&](std::span<const u8> chunk) {
            compressed.insert(compressed.end(), chunk.begin(), chunk.end());
            ++chunks;
            return true;
        }};
        {
            std::ostream stream{&out};
            // Uneven writes, so put-area refills happen mid-write
            for (std::size_t offset = 0; offset < payload.size(); offset += 12345) {
                const auto count = std::min<std::size_t>(12345, payload.size() - offset);
                stream.write(reinterpret_cast<const char*>(payload.data() + offset), count);
            }
            REQUIRE(stream.good());
        }
        REQUIRE(out.Finish());
        REQUIRE_FALSE(out.Failed());
        REQUIRE(chunks > 1);
        REQUIRE(compressed.size() < payload.size());

        ZSTDInputStreamBuf in{ChunkedSource(compressed)};
        std::istream stream{&in};
        REQUIRE(ReadAll(stream) == payload);
        REQUIRE_FALSE(in.Failed());
    }

    SECTION("reports a sink that refuses data") {
        ZSTDOutputStreamBuf out{[](std::span<const u8>) { return false; }};
        std::ostream stream{&out};
        stream.write(reinterpret_cast<const char*>(payload.data()), payload.size());
        stream.flush();
        REQUIRE_FALSE(stream.good());
        REQUIRE(out.Failed());
        REQUIRE_FALSE(out.Finish());
    }
}

TEST_CASE("Common::Compression::ZSTDInputStreamBuf", "[common][zstd]") {
    using namespace Common::Compression;
    const auto payload = MakePayload(2 * 1024 * 1024);
    const auto compressed = CompressDataZSTDDefault(payload);

    SECTION("reads a frame written by the one-shot compressor, as older save states are") {
        ZSTDInputStreamBuf in{ChunkedSource(compressed)};
        std::istream stream{&in};
        REQUIRE(ReadAll(stream) == payload);
        REQUIRE_FALSE(in.Failed());
    }

    SECTION("fails on a truncated frame instead of ending cleanly") {
        const std::span<const u8> truncated{compressed.data(), compressed.size() - 100};
        ZSTDInputStreamBuf in{ChunkedSource(truncated)};
        std::istream stream{&in};
        const auto read = ReadAll(stream);
        REQUIRE(read.size() < payload.size());
        REQUIRE(in.Failed());
    }

    SECTION("fails on a corrupt streamed frame, thanks to its checksum") {
        std::vector<u8> streamed;
        ZSTDOutputStreamBuf out{[&](std::span<const u8> chunk) {
            streamed.insert(streamed.end(), chunk.begin(), chunk.end());
            return true;
        }};
        {
            std::ostream stream{&out};
            stream.write(reinterpret_cast<const char*>(payload.data()), payload.size());
        }
        REQUIRE(out.Finish());
        streamed[streamed.size() / 2] ^= 0xFF;
        streamed[streamed.size() / 2 + 1] ^= 0xFF;

        ZSTDInputStreamBuf in{ChunkedSource(streamed)};
        std::istream stream{&in};
        ReadAll(stream);
        REQUIRE(in.Failed());
    }
}
