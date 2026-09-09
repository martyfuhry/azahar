// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <atomic>
#include <span>
#include <thread>
#include <vector>
#include <catch2/catch_test_macros.hpp>
#include "common/bounded_byte_pipe.h"
#include "common/common_types.h"

namespace {

std::vector<u8> Sequence(std::size_t size) {
    std::vector<u8> data(size);
    for (std::size_t i = 0; i < size; ++i) {
        data[i] = static_cast<u8>(i * 31 + (i >> 8));
    }
    return data;
}

/// Drains the pipe on this thread until it reports end of stream
std::vector<u8> DrainAll(Common::BoundedBytePipe& pipe, std::size_t chunk) {
    std::vector<u8> out;
    std::vector<u8> piece(chunk);
    while (true) {
        const std::size_t count = pipe.Read(piece);
        if (count == 0) {
            return out;
        }
        out.insert(out.end(), piece.begin(), piece.begin() + count);
    }
}

} // namespace

TEST_CASE("Common::BoundedBytePipe", "[common][pipe]") {
    using Common::BoundedBytePipe;

    SECTION("carries far more than it can hold, unchanged and in order") {
        // The point of the thing: 4 MiB through a 64 KiB pipe, so it wraps and fills many times
        constexpr std::size_t capacity = 64 * 1024;
        const auto payload = Sequence(4 * 1024 * 1024);
        BoundedBytePipe pipe{capacity};

        std::thread producer{[&] {
            // Uneven writes, so no piece lines up with the capacity or the wrap point
            for (std::size_t offset = 0; offset < payload.size(); offset += 7919) {
                const auto count = std::min<std::size_t>(7919, payload.size() - offset);
                REQUIRE(pipe.Write(std::span<const u8>{payload.data() + offset, count}));
            }
            pipe.Close();
        }};
        const auto received = DrainAll(pipe, 5003);
        producer.join();

        REQUIRE(received.size() == payload.size());
        REQUIRE(received == payload);
        REQUIRE(pipe.BytesWritten() == payload.size());
    }

    SECTION("stays correct when the consumer is much slower than the producer") {
        // The normal case for a savestate: compression cannot keep up with serialization, so the
        // pipe runs full and paces the producer. Peak memory is the capacity by construction --
        // the buffer is allocated once and never grows -- so what is worth testing is that
        // throttling does not lose or reorder anything.
        constexpr std::size_t capacity = 4096;
        const auto payload = Sequence(512 * 1024);
        BoundedBytePipe pipe{capacity};
        std::atomic<bool> done{false};

        std::thread producer{[&] {
            pipe.Write(payload);
            pipe.Close();
            done = true;
        }};
        const auto received = DrainAll(pipe, 64);
        producer.join();

        REQUIRE(done.load());
        REQUIRE(received == payload);
    }

    SECTION("reports end of stream only after the last byte written before the close") {
        BoundedBytePipe pipe{1024};
        const auto payload = Sequence(700);
        REQUIRE(pipe.Write(payload));
        pipe.Close();
        // Everything written before Close() is still owed to the consumer
        REQUIRE(DrainAll(pipe, 64) == payload);
    }

    SECTION("a close with nothing in it reads as an immediate end of stream") {
        BoundedBytePipe pipe{1024};
        pipe.Close();
        std::vector<u8> piece(16);
        REQUIRE(pipe.Read(piece) == 0);
    }

    SECTION("an abort releases a producer that is blocked on a full pipe") {
        // The failure path: the consumer died (a disk error, a shutdown) while the producer was
        // mid-serialize. Without this the emulation thread would block forever.
        constexpr std::size_t capacity = 1024;
        BoundedBytePipe pipe{capacity};
        const auto payload = Sequence(64 * 1024);
        std::atomic<bool> returned{false};
        std::atomic<bool> result{true};

        std::thread producer{[&] {
            result = pipe.Write(payload);
            returned = true;
        }};
        // Let the producer fill the pipe and block, then abandon it
        while (pipe.BytesWritten() < capacity) {
            std::this_thread::yield();
        }
        pipe.Abort();
        producer.join();

        REQUIRE(returned.load());
        REQUIRE_FALSE(result.load());
        REQUIRE(pipe.Aborted());
    }

    SECTION("an abort makes the consumer stop too") {
        BoundedBytePipe pipe{1024};
        REQUIRE(pipe.Write(Sequence(100)));
        pipe.Abort();
        std::vector<u8> piece(16);
        REQUIRE(pipe.Read(piece) == 0);
    }
}
