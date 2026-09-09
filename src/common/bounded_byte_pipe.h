// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <condition_variable>
#include <cstddef>
#include <mutex>
#include <span>
#include <vector>
#include "common/common_types.h"

namespace Common {

/**
 * A bounded byte pipe between exactly one producer thread and one consumer thread.
 *
 * The bound is the point. A savestate serializes to a few hundred megabytes before compression,
 * so handing that between threads as a buffer would spike memory by more than the emulated system
 * itself -- on a handheld that is already being killed for its footprint, that trades a smoother
 * frame for the very failure the autosave exists to insure against. Here the producer blocks once
 * the pipe is full, so peak memory is the capacity and nothing else, whatever the volume that
 * flows through.
 *
 * Blocking the producer is not a fallback, it is the normal case: the consumer (compression) is
 * slower than the producer (serialization), so the pipe runs full and the producer is paced by
 * the consumer. What it buys is that the producer finishes one pipe-full before the consumer
 * does, rather than after the whole compression -- which for the emulation thread is the
 * difference between resuming the guest at the end of compression and resuming it at the end of
 * serialization.
 */
class BoundedBytePipe {
public:
    explicit BoundedBytePipe(std::size_t capacity);

    /**
     * Aborts, as a backstop for an error path that forgot to. It does *not* make destruction safe
     * while another thread is still inside Write() or Read() -- nothing could -- so the owner must
     * still abort and join both sides before destroying the pipe.
     */
    ~BoundedBytePipe();

    BoundedBytePipe(const BoundedBytePipe&) = delete;
    BoundedBytePipe& operator=(const BoundedBytePipe&) = delete;

    /**
     * Producer: copies `data` in, blocking while the pipe is full. Returns false once the
     * consumer has abandoned the pipe, at which point the producer should stop and unwind.
     */
    bool Write(std::span<const u8> data);

    /// Producer: no more data is coming. Read() drains what is left and then reports 0.
    void Close();

    /**
     * Consumer: copies out at most `out.size()` bytes, blocking until some are available.
     * Returns 0 only once the producer has closed and the pipe is empty.
     */
    std::size_t Read(std::span<u8> out);

    /**
     * Consumer: abandons the pipe. A producer blocked in Write() wakes and is told to stop.
     * Safe to call more than once, and safe to call after Close().
     */
    void Abort();

    /// Whether the consumer abandoned the pipe
    [[nodiscard]] bool Aborted() const;

    /// Bytes the producer has handed over in total, for accounting
    [[nodiscard]] std::size_t BytesWritten() const;

private:
    std::size_t Available() const {
        return size;
    }
    std::size_t Free() const {
        return buffer.size() - size;
    }

    mutable std::mutex mutex;
    std::condition_variable readable;
    std::condition_variable writable;
    std::vector<u8> buffer;
    std::size_t head = 0; ///< next byte to read
    std::size_t size = 0; ///< bytes currently held
    std::size_t written = 0;
    bool closed = false;
    bool aborted = false;
};

} // namespace Common
