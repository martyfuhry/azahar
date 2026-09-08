// Copyright 2018-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdarg>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>
#include <cubeb/cubeb.h>
#include "audio_core/audio_types.h"
#include "audio_core/cubeb_sink.h"
#include "common/logging/log.h"
#include "common/thread.h"

namespace AudioCore {

struct CubebSink::Impl {
    cubeb* ctx = nullptr;
    cubeb_stream* stream = nullptr;
    cubeb_devid output_device = nullptr;
    u32 latency_frames = 0;

    /// Serializes stream stop/start/reopen against each other. cubeb serializes its own
    /// stream_start/stream_stop internally, but reopening replaces `stream` and must not race a
    /// concurrent SetPaused from another thread.
    std::mutex control_mutex;
    /// Whether the last SetPaused() request was for a stopped stream.
    bool paused = false;
    /// Set by the state callback when cubeb gives up on the stream (CUBEB_STATE_ERROR); the next
    /// start reopens the stream instead of trying to start a dead one.
    std::atomic<bool> errored = false;

    std::function<void(s16*, std::size_t)> cb;

    bool OpenStream();
    void CloseStream();

    static long DataCallback(cubeb_stream* stream, void* user_data, const void* input_buffer,
                             void* output_buffer, long num_frames);
    static void StateCallback(cubeb_stream* stream, void* user_data, cubeb_state state);
    static void LogCallback(char const* fmt, ...);
};

/**
 * cubeb_init spawns backend helper threads (AAudio: a state-polling thread and its notifier) from
 * the calling thread, and Linux threads inherit the creator's name. Calling it from a short-lived
 * thread with a name of our own keeps those helpers from showing up as "NativeEmulation" (or
 * whatever the emulation thread is called) in thread inventories.
 */
static int InitCubebNamed(cubeb** ctx, const char* context_name) {
    int result = CUBEB_ERROR;
    std::thread init_thread([&] {
        Common::SetCurrentThreadName("cubeb");
        result = cubeb_init(ctx, context_name, nullptr);
    });
    init_thread.join();
    return result;
}

CubebSink::CubebSink(std::string_view target_device_name) : impl(std::make_unique<Impl>()) {
    if (InitCubebNamed(&impl->ctx, "Azahar Output") != CUBEB_OK) {
        LOG_CRITICAL(Audio_Sink, "cubeb_init failed");
        return;
    }

    if (cubeb_set_log_callback(CUBEB_LOG_NORMAL, &Impl::LogCallback) != CUBEB_OK) {
        LOG_CRITICAL(Audio_Sink, "cubeb_set_log_callback failed");
        return;
    }

    cubeb_stream_params params = {
        .format = CUBEB_SAMPLE_S16LE,
        .rate = native_sample_rate,
        .channels = 2,
        .layout = CUBEB_LAYOUT_STEREO,
    };

    u32 minimum_latency = 100 * native_sample_rate / 1000; // Firefox default
    if (cubeb_get_min_latency(impl->ctx, &params, &minimum_latency) != CUBEB_OK) {
        LOG_WARNING(Audio_Sink,
                    "Error getting minimum output latency, falling back to default latency.");
    }
    impl->latency_frames = std::max(512u, minimum_latency);

    if (target_device_name != auto_device_name && !target_device_name.empty()) {
        cubeb_device_collection collection;
        if (cubeb_enumerate_devices(impl->ctx, CUBEB_DEVICE_TYPE_OUTPUT, &collection) == CUBEB_OK) {
            const auto collection_end{collection.device + collection.count};
            const auto device{
                std::find_if(collection.device, collection_end, [&](const cubeb_device_info& info) {
                    return info.friendly_name != nullptr &&
                           target_device_name == info.friendly_name;
                })};
            if (device != collection_end) {
                impl->output_device = device->devid;
            }
            cubeb_device_collection_destroy(impl->ctx, &collection);
        } else {
            LOG_WARNING(Audio_Sink,
                        "Audio output device enumeration not supported, using default device.");
        }
    }

    std::scoped_lock lock{impl->control_mutex};
    if (!impl->OpenStream()) {
        return;
    }

    if (cubeb_stream_start(impl->stream) != CUBEB_OK) {
        LOG_CRITICAL(Audio_Sink, "Error starting cubeb stream");
        return;
    }
}

CubebSink::~CubebSink() {
    {
        std::scoped_lock lock{impl->control_mutex};
        impl->CloseStream();
    }

    if (impl->ctx) {
        cubeb_destroy(impl->ctx);
    }
}

bool CubebSink::Impl::OpenStream() {
    cubeb_stream_params params = {
        .format = CUBEB_SAMPLE_S16LE,
        .rate = native_sample_rate,
        .channels = 2,
        .layout = CUBEB_LAYOUT_STEREO,
    };

    errored = false;
    auto stream_err =
        cubeb_stream_init(ctx, &stream, "AzaharAudio", nullptr, nullptr, output_device, &params,
                          latency_frames, &DataCallback, &StateCallback, this);
    if (stream_err != CUBEB_OK) {
        stream = nullptr;
        switch (stream_err) {
        case CUBEB_ERROR:
        default:
            LOG_CRITICAL(Audio_Sink, "Error initializing cubeb stream ({})", stream_err);
            break;
        case CUBEB_ERROR_INVALID_FORMAT:
            LOG_CRITICAL(Audio_Sink, "Invalid format when initializing cubeb stream");
            break;
        case CUBEB_ERROR_DEVICE_UNAVAILABLE:
            LOG_CRITICAL(Audio_Sink, "Device unavailable when initializing cubeb stream");
            break;
        }
        return false;
    }
    return true;
}

void CubebSink::Impl::CloseStream() {
    if (!stream) {
        return;
    }
    if (cubeb_stream_stop(stream) != CUBEB_OK) {
        LOG_ERROR(Audio_Sink, "Error stopping cubeb stream.");
    }
    cubeb_stream_destroy(stream);
    stream = nullptr;
}

unsigned int CubebSink::GetNativeSampleRate() const {
    return native_sample_rate;
}

void CubebSink::SetCallback(std::function<void(s16*, std::size_t)> cb) {
    impl->cb = cb;
}

void CubebSink::SetPaused(bool paused) {
    std::scoped_lock lock{impl->control_mutex};
    if (!impl->ctx || paused == impl->paused) {
        return;
    }
    impl->paused = paused;

    if (paused) {
        // cubeb_stream_stop is synchronous with respect to the data callback (AAudio: requestPause
        // returns only once no more callbacks will fire; PulseAudio corks and waits), so a stop
        // issued right after a start cannot leave a callback running on a stopped stream.
        if (impl->stream && cubeb_stream_stop(impl->stream) != CUBEB_OK) {
            LOG_ERROR(Audio_Sink, "Error stopping cubeb stream, reopening on resume.");
            impl->errored = true;
        }
        return;
    }

    // A stream that lost its device while stopped (AAudio route change, headphones unplugged)
    // reports CUBEB_STATE_ERROR and can never be started again; reopen it instead.
    if (impl->stream && !impl->errored && cubeb_stream_start(impl->stream) == CUBEB_OK) {
        return;
    }
    LOG_WARNING(Audio_Sink, "cubeb stream unusable on resume, reopening.");
    impl->CloseStream();
    if (!impl->OpenStream()) {
        return;
    }
    if (cubeb_stream_start(impl->stream) != CUBEB_OK) {
        LOG_CRITICAL(Audio_Sink, "Error starting reopened cubeb stream");
    }
}

long CubebSink::Impl::DataCallback(cubeb_stream* stream, void* user_data, const void* input_buffer,
                                   void* output_buffer, long num_frames) {
    auto* impl = static_cast<Impl*>(user_data);
    auto* buffer = static_cast<s16*>(output_buffer);

    if (!impl || !impl->cb) {
        LOG_DEBUG(Audio_Sink, "Missing internal data and/or audio callback, emitting zeroes.");
        std::memset(output_buffer, 0, num_frames * 2 * sizeof(s16));
    } else {
        impl->cb(buffer, num_frames);
    }

    return num_frames;
}

void CubebSink::Impl::StateCallback(cubeb_stream* stream, void* user_data, cubeb_state state) {
    switch (state) {
    case CUBEB_STATE_STARTED:
        LOG_INFO(Audio_Sink, "Cubeb Audio Stream Started");
        break;
    case CUBEB_STATE_STOPPED:
        LOG_INFO(Audio_Sink, "Cubeb Audio Stream Stopped");
        break;
    case CUBEB_STATE_DRAINED:
        LOG_INFO(Audio_Sink, "Cubeb Audio Stream Drained");
        break;
    case CUBEB_STATE_ERROR:
        LOG_CRITICAL(Audio_Sink, "Cubeb Audio Stream Errored");
        if (auto* impl = static_cast<Impl*>(user_data)) {
            impl->errored = true;
        }
        break;
    }
}

void CubebSink::Impl::LogCallback(char const* format, ...) {
    std::array<char, 512> buffer{};
    std::va_list args;
    va_start(args, format);
#ifdef _MSC_VER
    vsprintf_s(buffer.data(), buffer.size(), format, args);
#else
    vsnprintf(buffer.data(), buffer.size(), format, args);
#endif
    va_end(args);
    buffer.back() = '\0';
    LOG_DEBUG(Audio_Sink, "{}", buffer.data());
}

std::vector<std::string> ListCubebSinkDevices() {
    std::vector<std::string> device_list;
    cubeb* ctx;

    if (InitCubebNamed(&ctx, "Azahar Output Device Enumerator") != CUBEB_OK) {
        LOG_CRITICAL(Audio_Sink, "cubeb_init failed");
        return {};
    }

    cubeb_device_collection collection;
    if (cubeb_enumerate_devices(ctx, CUBEB_DEVICE_TYPE_OUTPUT, &collection) == CUBEB_OK) {
        for (std::size_t i = 0; i < collection.count; i++) {
            const cubeb_device_info& device = collection.device[i];
            if (device.state == CUBEB_DEVICE_STATE_ENABLED && device.friendly_name) {
                device_list.emplace_back(device.friendly_name);
            }
        }
        cubeb_device_collection_destroy(ctx, &collection);
    } else {
        LOG_WARNING(Audio_Sink, "Audio output device enumeration not supported.");
    }

    cubeb_destroy(ctx);
    return device_list;
}

} // namespace AudioCore
