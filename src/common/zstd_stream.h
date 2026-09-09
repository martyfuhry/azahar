// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <cstddef>
#include <functional>
#include <span>
#include <streambuf>
#include <vector>
#include "common/common_types.h"

struct ZSTD_CCtx_s;
struct ZSTD_DCtx_s;

namespace Common::Compression {

/**
 * std::streambuf that Zstandard-compresses whatever is written to it and hands the compressed
 * bytes to a sink in chunks. Lets boost::serialization stream straight into a file, so neither
 * the serialized data nor its compressed form ever has to exist in memory in one piece. Call
 * Finish() once everything has been written; it compresses what is still buffered and closes
 * the frame. The frame carries no content size, so read it back with ZSTDInputStreamBuf rather
 * than DecompressDataZSTD.
 */
/**
 * Zstandard's own default level (ZSTD_CLEVEL_DEFAULT), restated here so that <zstd.h> does not
 * have to be included by everything that names it. Callers with a speed/ratio preference of their
 * own should pass a level rather than relying on this.
 */
constexpr int DefaultCompressionLevel = 3;

class ZSTDOutputStreamBuf final : public std::streambuf {
public:
    /// Receives each compressed chunk; returning false aborts the stream
    using Sink = std::function<bool(std::span<const u8>)>;

    /**
     * `level` is a Zstandard compression level. It affects only how this frame is produced --
     * the level is recorded in the frame's own parameters, so ZSTDInputStreamBuf reads any level
     * back without being told which one was used, and a stream written by an older build at a
     * different level stays readable.
     */
    explicit ZSTDOutputStreamBuf(Sink sink, int level = DefaultCompressionLevel);
    ~ZSTDOutputStreamBuf() override;

    ZSTDOutputStreamBuf(const ZSTDOutputStreamBuf&) = delete;
    ZSTDOutputStreamBuf& operator=(const ZSTDOutputStreamBuf&) = delete;

    /// Compresses the buffered input and ends the frame. False if compression or the sink failed.
    [[nodiscard]] bool Finish();

    /// Whether compression or the sink failed at any point
    [[nodiscard]] bool Failed() const {
        return failed;
    }

protected:
    int_type overflow(int_type ch) override;
    int sync() override;

private:
    /// Feeds the put area to the compressor; `directive` is a ZSTD_EndDirective
    bool Compress(int directive);

    Sink sink;
    ZSTD_CCtx_s* cctx{};
    std::vector<char> in_buffer;
    std::vector<u8> out_buffer;
    bool failed = false;
    bool finished = false;
};

/**
 * std::streambuf that pulls a Zstandard frame from a source in chunks and hands out the
 * decompressed bytes as they are produced, without buffering the whole result. Reads frames
 * written by ZSTDOutputStreamBuf and by the one-shot CompressDataZSTD alike. The stream ends
 * early if the frame is corrupt or the source runs out before the frame is complete; check
 * Failed() to tell that apart from a clean end.
 */
class ZSTDInputStreamBuf final : public std::streambuf {
public:
    /// Fills the span with the next input bytes and returns how many, 0 at end of input
    using Source = std::function<std::size_t(std::span<u8>)>;

    explicit ZSTDInputStreamBuf(Source source);
    ~ZSTDInputStreamBuf() override;

    ZSTDInputStreamBuf(const ZSTDInputStreamBuf&) = delete;
    ZSTDInputStreamBuf& operator=(const ZSTDInputStreamBuf&) = delete;

    /// Whether the data was corrupt or ended before the frame was complete
    [[nodiscard]] bool Failed() const {
        return failed;
    }

protected:
    int_type underflow() override;

private:
    Source source;
    ZSTD_DCtx_s* dctx{};
    std::vector<u8> in_buffer;
    std::size_t in_pos = 0;
    std::size_t in_size = 0;
    std::vector<char> out_buffer;
    bool frame_complete = false;
    bool failed = false;
};

} // namespace Common::Compression
