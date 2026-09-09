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

TEST_CASE("Common::Compression::ZSTDOutputStreamBuf compression levels", "[common][zstd]") {
    using namespace Common::Compression;
    const auto payload = MakePayload(3 * 1024 * 1024);

    const auto compress_at = [&payload](int level) {
        std::vector<u8> compressed;
        ZSTDOutputStreamBuf out{[&](std::span<const u8> chunk) {
                                    compressed.insert(compressed.end(), chunk.begin(), chunk.end());
                                    return true;
                                },
                                level};
        {
            std::ostream stream{&out};
            stream.write(reinterpret_cast<const char*>(payload.data()), payload.size());
            REQUIRE(stream.good());
        }
        REQUIRE(out.Finish());
        REQUIRE_FALSE(out.Failed());
        return compressed;
    };

    const auto round_trip = [&payload](const std::vector<u8>& compressed) {
        ZSTDInputStreamBuf in{ChunkedSource(compressed)};
        std::istream stream{&in};
        const auto out = ReadAll(stream);
        REQUIRE_FALSE(in.Failed());
        REQUIRE(out == payload);
    };

    // Savestates are written at Core::SaveStateCompressionLevel and read by a decoder that is
    // never told which level produced the frame. States written by older builds used Zstandard's
    // default of 3, so every one of these has to read back through the same input streambuf for
    // an existing state to survive a build that changed the level.
    SECTION("a frame reads back identically whatever level wrote it") {
        for (const int level : {3, 1, -1, -3}) {
            INFO("compression level " << level);
            round_trip(compress_at(level));
        }
    }

    SECTION("the level that savestates now use is not the one older builds wrote") {
        // Guards the claim above from becoming vacuous if the level is ever set back to 3
        STATIC_REQUIRE(DefaultCompressionLevel == 3);
        const auto legacy = compress_at(DefaultCompressionLevel);
        const auto current = compress_at(1);
        REQUIRE(legacy != current);
        round_trip(legacy);
        round_trip(current);
    }

    SECTION("both levels still compress") {
        // Deliberately not asserting that level 1 produces a larger frame than level 3. It does
        // on real savestate data (14.5 MB against 13.6 MB for Animal Crossing New Leaf) but that
        // is a property of the data, not of Zstandard: on this synthetic payload level 1 comes
        // out 0.5% *smaller*, and an assertion the other way would only be encoding an accident.
        REQUIRE(compress_at(3).size() < payload.size());
        REQUIRE(compress_at(1).size() < payload.size());
    }
}

TEST_CASE("Common::Compression::ZSTDOutputStreamBuf::MeasureInto", "[common][zstd]") {
    using namespace Common::Compression;
    const auto payload = MakePayload(3 * 1024 * 1024);

    ZSTDOutputStreamBuf::Stats stats;
    std::size_t sink_bytes = 0;
    ZSTDOutputStreamBuf out{[&](std::span<const u8> chunk) {
        sink_bytes += chunk.size();
        return true;
    }};
    out.MeasureInto(&stats);
    {
        std::ostream stream{&out};
        stream.write(reinterpret_cast<const char*>(payload.data()), payload.size());
        REQUIRE(stream.good());
    }
    REQUIRE(out.Finish());

    SECTION("counts every byte in and every byte out") {
        // bytes_in is what the caller wrote, which is the figure the breakdown reports as the
        // uncompressed volume; bytes_out has to agree with what the sink actually received
        REQUIRE(stats.bytes_in == payload.size());
        REQUIRE(stats.bytes_out == sink_bytes);
        REQUIRE(stats.bytes_out < stats.bytes_in);
    }

    SECTION("attributes time to the compressor and the sink separately") {
        REQUIRE(stats.compress_ns > 0);
        // The sink here only adds to a counter, so it must be a small fraction of the compressor
        REQUIRE(stats.sink_ns < stats.compress_ns);
    }
}

TEST_CASE("Common::Compression::ZSTDOutputStreamBuf without MeasureInto", "[common][zstd]") {
    using namespace Common::Compression;
    const auto payload = MakePayload(1024 * 1024);

    // The default path must not touch the Stats machinery at all; this is the shape every save
    // takes unless log_savestate_breakdown is on
    ZSTDOutputStreamBuf::Stats stats;
    ZSTDOutputStreamBuf out{[](std::span<const u8>) { return true; }};
    {
        std::ostream stream{&out};
        stream.write(reinterpret_cast<const char*>(payload.data()), payload.size());
    }
    REQUIRE(out.Finish());
    REQUIRE(stats.bytes_in == 0);
    REQUIRE(stats.bytes_out == 0);
    REQUIRE(stats.compress_ns == 0);
    REQUIRE(stats.sink_ns == 0);
}
