// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <cstring>
#include "common/bounded_byte_pipe.h"

namespace Common {

BoundedBytePipe::BoundedBytePipe(std::size_t capacity)
    : buffer(std::max<std::size_t>(capacity, 1)) {}

BoundedBytePipe::~BoundedBytePipe() {
    Abort();
}

bool BoundedBytePipe::Write(std::span<const u8> data) {
    std::unique_lock lock{mutex};
    while (!data.empty()) {
        writable.wait(lock, [this] { return aborted || Free() > 0; });
        if (aborted) {
            return false;
        }
        // The ring wraps, so a write may need two copies; take the shorter of what fits, what is
        // left before the end of the buffer, and what the caller still has
        const std::size_t tail = (head + size) % buffer.size();
        const std::size_t piece = std::min({data.size(), Free(), buffer.size() - tail});
        std::memcpy(buffer.data() + tail, data.data(), piece);
        size += piece;
        written += piece;
        data = data.subspan(piece);
        readable.notify_one();
    }
    return true;
}

void BoundedBytePipe::Close() {
    {
        std::scoped_lock lock{mutex};
        closed = true;
    }
    readable.notify_all();
}

std::size_t BoundedBytePipe::Read(std::span<u8> out) {
    std::unique_lock lock{mutex};
    readable.wait(lock, [this] { return aborted || closed || Available() > 0; });
    if (aborted) {
        return 0;
    }
    // Only report end of stream once everything written before the close has been handed over
    if (Available() == 0) {
        return 0;
    }
    const std::size_t piece = std::min({out.size(), Available(), buffer.size() - head});
    std::memcpy(out.data(), buffer.data() + head, piece);
    head = (head + piece) % buffer.size();
    size -= piece;
    writable.notify_one();
    return piece;
}

void BoundedBytePipe::Abort() {
    {
        std::scoped_lock lock{mutex};
        aborted = true;
    }
    // Both sides, because either may be waiting when the other gives up
    writable.notify_all();
    readable.notify_all();
}

bool BoundedBytePipe::Aborted() const {
    std::scoped_lock lock{mutex};
    return aborted;
}

std::size_t BoundedBytePipe::BytesWritten() const {
    std::scoped_lock lock{mutex};
    return written;
}

} // namespace Common
