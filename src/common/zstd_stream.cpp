// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <zstd.h>
#include "common/logging/log.h"
#include "common/zstd_stream.h"

namespace Common::Compression {

ZSTDOutputStreamBuf::ZSTDOutputStreamBuf(Sink sink_, int level)
    : sink{std::move(sink_)}, cctx{ZSTD_createCCtx()}, in_buffer(ZSTD_CStreamInSize()),
      out_buffer(ZSTD_CStreamOutSize()) {
    static_assert(DefaultCompressionLevel == ZSTD_CLEVEL_DEFAULT);
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, level);
    // Four bytes per frame that let the reader tell a corrupt state from a valid one
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_checksumFlag, 1);
    setp(in_buffer.data(), in_buffer.data() + in_buffer.size());
}

ZSTDOutputStreamBuf::~ZSTDOutputStreamBuf() {
    ZSTD_freeCCtx(cctx);
}

bool ZSTDOutputStreamBuf::Compress(int directive) {
    if (failed) {
        return false;
    }
    ZSTD_inBuffer input{in_buffer.data(), static_cast<std::size_t>(pptr() - pbase()), 0};
    const auto mode = static_cast<ZSTD_EndDirective>(directive);
    bool done = false;
    while (!done) {
        ZSTD_outBuffer output{out_buffer.data(), out_buffer.size(), 0};
        const std::size_t remaining = ZSTD_compressStream2(cctx, &output, &input, mode);
        if (ZSTD_isError(remaining)) {
            LOG_ERROR(Common, "Error compressing ZSTD stream: {}", ZSTD_getErrorName(remaining));
            failed = true;
            return false;
        }
        if (output.pos > 0 && !sink(std::span<const u8>{out_buffer.data(), output.pos})) {
            failed = true;
            return false;
        }
        // Plain input is done once consumed; a flush or end also has to drain the compressor
        done = mode == ZSTD_e_continue ? input.pos == input.size : remaining == 0;
    }
    setp(in_buffer.data(), in_buffer.data() + in_buffer.size());
    return true;
}

ZSTDOutputStreamBuf::int_type ZSTDOutputStreamBuf::overflow(int_type ch) {
    if (finished || !Compress(ZSTD_e_continue)) {
        return traits_type::eof();
    }
    if (!traits_type::eq_int_type(ch, traits_type::eof())) {
        *pptr() = traits_type::to_char_type(ch);
        pbump(1);
    }
    return traits_type::not_eof(ch);
}

int ZSTDOutputStreamBuf::sync() {
    return !finished && Compress(ZSTD_e_flush) ? 0 : -1;
}

bool ZSTDOutputStreamBuf::Finish() {
    if (finished) {
        return !failed;
    }
    finished = true;
    return Compress(ZSTD_e_end);
}

ZSTDInputStreamBuf::ZSTDInputStreamBuf(Source source_)
    : source{std::move(source_)}, dctx{ZSTD_createDCtx()}, in_buffer(ZSTD_DStreamInSize()),
      out_buffer(ZSTD_DStreamOutSize()) {
    setg(out_buffer.data(), out_buffer.data(), out_buffer.data());
}

ZSTDInputStreamBuf::~ZSTDInputStreamBuf() {
    ZSTD_freeDCtx(dctx);
}

ZSTDInputStreamBuf::int_type ZSTDInputStreamBuf::underflow() {
    if (gptr() < egptr()) {
        return traits_type::to_int_type(*gptr());
    }
    while (!failed) {
        if (in_pos == in_size) {
            in_size = source(std::span<u8>{in_buffer});
            in_pos = 0;
            if (in_size == 0) {
                if (!frame_complete) {
                    LOG_ERROR(Common, "ZSTD stream ended before the frame was complete");
                    failed = true;
                }
                return traits_type::eof();
            }
        }
        ZSTD_inBuffer input{in_buffer.data(), in_size, in_pos};
        ZSTD_outBuffer output{out_buffer.data(), out_buffer.size(), 0};
        const std::size_t remaining = ZSTD_decompressStream(dctx, &output, &input);
        in_pos = input.pos;
        if (ZSTD_isError(remaining)) {
            LOG_ERROR(Common, "Error decompressing ZSTD stream: {}", ZSTD_getErrorName(remaining));
            failed = true;
            return traits_type::eof();
        }
        frame_complete = remaining == 0;
        if (output.pos > 0) {
            setg(out_buffer.data(), out_buffer.data(), out_buffer.data() + output.pos);
            return traits_type::to_int_type(*gptr());
        }
    }
    return traits_type::eof();
}

} // namespace Common::Compression
