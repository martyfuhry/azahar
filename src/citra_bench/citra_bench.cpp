// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// Headless benchmark harness: boots a title on the software renderer with no window, no audio
// device and no frame limit, runs it for a fixed number of system frames and prints host-side
// cost figures (wall time, CPU time, resident memory, boot time, PerfStats) that can be diffed
// between builds. See docs/fork/improvement-plan.md, metric H-B.

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <optional>
#include <random>
#include <string>
#include <string_view>
#include <fmt/format.h>
#include "audio_core/sink_details.h"
#include "citra_bench/emu_window_null.h"
#include "common/common_types.h"
#include "common/detached_tasks.h"
#include "common/file_util.h"
#include "common/logging/backend.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/hle/service/service.h"
#include "core/loader/loader.h"
#include "core/movie.h"
#include "video_core/gpu.h"
#include "video_core/renderer_base.h"

#if defined(__unix__) || defined(__APPLE__)
#include <sys/resource.h>
#endif

namespace {

using Clock = std::chrono::steady_clock;

double ToMs(Clock::duration d) {
    return std::chrono::duration<double, std::milli>(d).count();
}

struct Options {
    std::string rom;
    u64 frames = 600;
    u64 save_after = 0;
    std::string user_dir;
    bool keep_user_dir = false;
    std::string movie;
    bool verbose = false;
};

void PrintUsage(const char* argv0) {
    fmt::print(stderr,
               "Usage: {} [options] [rom]\n"
               "  rom                 Title to boot; defaults to $AZAHAR_BENCH_ROM\n"
               "  --frames N          Stop after N system frames (default 600)\n"
               "  --save-after N      After frame N, save a state, load it back and report both\n"
               "  --movie PATH        Play back a .ctm movie for deterministic input\n"
               "  --user-dir PATH     User directory (default: a fresh temporary one)\n"
               "  --keep-user-dir     Do not delete the temporary user directory on exit\n"
               "  --verbose           Echo the emulator log to stderr\n",
               argv0);
}

bool ParseArgs(int argc, char** argv, Options& options) {
    for (int i = 1; i < argc; ++i) {
        const std::string_view arg = argv[i];
        auto value = [&](u64& out) {
            if (i + 1 >= argc) {
                return false;
            }
            out = std::strtoull(argv[++i], nullptr, 10);
            return true;
        };
        auto text = [&](std::string& out) {
            if (i + 1 >= argc) {
                return false;
            }
            out = argv[++i];
            return true;
        };
        if (arg == "--frames") {
            if (!value(options.frames)) {
                return false;
            }
        } else if (arg == "--save-after") {
            if (!value(options.save_after)) {
                return false;
            }
        } else if (arg == "--movie") {
            if (!text(options.movie)) {
                return false;
            }
        } else if (arg == "--user-dir") {
            if (!text(options.user_dir)) {
                return false;
            }
        } else if (arg == "--keep-user-dir") {
            options.keep_user_dir = true;
        } else if (arg == "--verbose") {
            options.verbose = true;
        } else if (arg == "--help" || arg == "-h") {
            return false;
        } else if (!arg.empty() && arg[0] == '-') {
            fmt::print(stderr, "Unknown option {}\n", arg);
            return false;
        } else {
            options.rom = arg;
        }
    }
    if (options.rom.empty()) {
        if (const char* env = std::getenv("AZAHAR_BENCH_ROM")) {
            options.rom = env;
        }
    }
    if (options.rom.empty()) {
        fmt::print(stderr, "No ROM given and AZAHAR_BENCH_ROM is not set\n");
        return false;
    }
    if (options.frames == 0) {
        fmt::print(stderr, "--frames must be at least 1\n");
        return false;
    }
    return true;
}

/// Host process cost counters; -1 where the platform does not provide one
struct HostUsage {
    double cpu_ms = -1;      ///< User + system CPU time of the whole process
    double peak_rss_mb = -1; ///< High-water mark of resident memory
    double vm_rss_mb = -1;   ///< Resident memory right now
    double rss_anon_mb = -1; ///< Anonymous (heap, not file-backed) part of vm_rss_mb
};

HostUsage ReadHostUsage() {
    HostUsage usage;
#if defined(__unix__) || defined(__APPLE__)
    rusage ru{};
    if (getrusage(RUSAGE_SELF, &ru) == 0) {
        const auto tv_ms = [](const timeval& tv) {
            return tv.tv_sec * 1000.0 + tv.tv_usec / 1000.0;
        };
        usage.cpu_ms = tv_ms(ru.ru_utime) + tv_ms(ru.ru_stime);
#if defined(__APPLE__)
        usage.peak_rss_mb = static_cast<double>(ru.ru_maxrss) / (1024.0 * 1024.0);
#else
        usage.peak_rss_mb = static_cast<double>(ru.ru_maxrss) / 1024.0;
#endif
    }
#endif
#if defined(__linux__)
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line)) {
        const auto read_kb = [&](std::string_view key) -> std::optional<double> {
            if (!line.starts_with(key)) {
                return std::nullopt;
            }
            return std::strtod(line.c_str() + key.size(), nullptr) / 1024.0;
        };
        if (const auto kb = read_kb("VmRSS:")) {
            usage.vm_rss_mb = *kb;
        } else if (const auto kb = read_kb("RssAnon:")) {
            usage.rss_anon_mb = *kb;
        }
    }
#endif
    return usage;
}

std::string MakeTempUserDir() {
    std::random_device rd;
    const char* base = std::getenv("TMPDIR");
    const std::string dir =
        fmt::format("{}/azahar-bench-{:08x}/", base ? base : "/tmp", rd() & 0xFFFFFFFFu);
    return FileUtil::CreateFullPath(dir) ? dir : std::string{};
}

/// Sends a Save/Load signal the way the frontends do and pumps RunLoop until the core has
/// carried it out (it waits for pending async kernel operations first). Returns how long the
/// RunLoop call that performed the operation took, which is the save/load itself plus a few
/// bookkeeping checks.
std::optional<double> RunSaveStateSignal(Core::System& system, Core::System::Signal signal,
                                         u32 slot, std::string& error) {
    if (!system.SendSignal(signal, slot)) {
        error = "another save state operation is pending";
        return std::nullopt;
    }
    while (true) {
        const auto begin = Clock::now();
        const auto result = system.RunLoop();
        const auto end = Clock::now();
        if (result != Core::System::ResultStatus::Success) {
            error = fmt::format("RunLoop returned {} ({})", static_cast<u32>(result),
                                system.GetStatusDetails());
            return std::nullopt;
        }
        if (!system.HasPendingSaveStateRequest()) {
            return ToMs(end - begin);
        }
    }
}

struct SaveStateResult {
    double save_ms = -1;
    double load_ms = -1;
    u64 bytes = 0;
};

std::optional<SaveStateResult> SaveAndReloadState(Core::System& system, u64 title_id) {
    constexpr u32 slot = 1;
    SaveStateResult result;
    std::string error;
    if (const auto ms = RunSaveStateSignal(system, Core::System::Signal::Save, slot, error)) {
        result.save_ms = *ms;
    } else {
        fmt::print(stderr, "Save state failed: {}\n", error);
        return std::nullopt;
    }
    // Same naming as core/savestate.cpp GetSaveStatePath, for a numbered slot without a movie
    const auto path =
        fmt::format("{}{:016X}.{:02d}.cst", FileUtil::GetUserPath(FileUtil::UserPath::StatesDir),
                    title_id, slot);
    result.bytes = FileUtil::GetSize(path);
    if (const auto ms = RunSaveStateSignal(system, Core::System::Signal::Load, slot, error)) {
        result.load_ms = *ms;
    } else {
        fmt::print(stderr, "Load state failed: {}\n", error);
        return std::nullopt;
    }
    return result;
}

} // namespace

int main(int argc, char** argv) {
    Options options;
    if (!ParseArgs(argc, argv, options)) {
        PrintUsage(argv[0]);
        return 1;
    }

    // The user directory has to be chosen before anything asks FileUtil for a path (the log
    // file is the first), and a fresh one keeps runs independent of each other's saves
    const bool temp_user_dir = options.user_dir.empty();
    if (temp_user_dir) {
        options.user_dir = MakeTempUserDir();
        if (options.user_dir.empty()) {
            fmt::print(stderr, "Could not create a temporary user directory\n");
            return 1;
        }
    }
    FileUtil::SetUserPath(options.user_dir);

    Common::Log::Initialize();
    Common::Log::Start();
    Common::Log::SetColorConsoleBackendEnabled(options.verbose);
    Common::DetachedTasks detached_tasks;

    // Headless: nothing to present to, nothing to play through, nothing to pace against
    Settings::values.graphics_api = Settings::GraphicsAPI::Software;
    Settings::values.frame_limit = 0;
    Settings::values.use_disk_shader_cache = false;
    Settings::values.output_type = AudioCore::SinkType::Null;
    Settings::values.enable_audio_stretching = false;
    // Service::Init looks every module up in this map, so it has to be filled in: all HLE
    for (const auto& service_module : Service::service_module_map) {
        Settings::values.lle_modules.emplace(service_module.name, false);
    }

    auto& system = Core::System::GetInstance();
    Bench::EmuWindow_Null window;

    const auto usage_start = ReadHostUsage();
    const auto load_begin = Clock::now();
    const auto load_result = system.Load(window, options.rom);
    if (load_result != Core::System::ResultStatus::Success) {
        fmt::print(stderr, "Failed to load {}: ResultStatus {} ({})\n", options.rom,
                   static_cast<u32>(load_result), system.GetStatusDetails());
        Common::Log::Stop();
        return 2;
    }

    u64 title_id{};
    system.GetAppLoader().ReadProgramId(title_id);
    // What EmuThread::run does before its first RunLoop
    system.GPU().ApplyPerProgramSettings(title_id);
    const std::atomic_bool stop_loading{false};
    system.GPU().Renderer().Rasterizer()->LoadDefaultDiskResources(stop_loading, nullptr);
    system.RegisterCoreLoopThreadId();

    if (!options.movie.empty()) {
        system.Movie().StartPlayback(options.movie);
    }

    std::optional<Clock::time_point> first_frame;
    HostUsage usage_first_frame;
    std::optional<SaveStateResult> save_state;
    bool save_state_done = false;
    int exit_code = 0;

    while (window.GetFrameCount() < options.frames) {
        const auto result = system.RunLoop();
        if (result == Core::System::ResultStatus::ShutdownRequested) {
            fmt::print(stderr, "Title requested shutdown after {} frames\n",
                       window.GetFrameCount());
            break;
        }
        if (result != Core::System::ResultStatus::Success) {
            fmt::print(stderr, "RunLoop failed after {} frames: ResultStatus {} ({})\n",
                       window.GetFrameCount(), static_cast<u32>(result), system.GetStatusDetails());
            exit_code = 3;
            break;
        }
        if (!first_frame && window.GetFrameCount() > 0) {
            first_frame = window.GetFirstFrameTime();
            usage_first_frame = ReadHostUsage();
        }
        if (options.save_after != 0 && !save_state_done &&
            window.GetFrameCount() >= options.save_after) {
            save_state_done = true;
            save_state = SaveAndReloadState(system, title_id);
            if (!save_state) {
                exit_code = 4;
                break;
            }
        }
    }

    const auto end = Clock::now();
    const auto usage_end = ReadHostUsage();
    const auto perf = system.GetAndResetPerfStats();
    const u64 frames = window.GetFrameCount();
    const double wall_ms = ToMs(end - load_begin);
    // Steady-state figures exclude boot: measured from the first system frame
    const double run_ms = first_frame ? ToMs(end - *first_frame) : 0.0;
    const u64 run_frames = frames > 0 ? frames - 1 : 0;

    fmt::print("rom                 {}\n", options.rom);
    fmt::print("title_id            {:016X}\n", title_id);
    fmt::print("frames              {}\n", frames);
    fmt::print("wall_ms             {:.1f}\n", wall_ms);
    fmt::print("boot_ms             {:.1f}\n",
               first_frame ? ToMs(*first_frame - load_begin) : -1.0);
    fmt::print("run_ms              {:.1f}\n", run_ms);
    fmt::print("frames_per_s        {:.2f}\n", run_ms > 0 ? run_frames / (run_ms / 1000.0) : 0.0);
    fmt::print("cpu_ms              {:.1f}\n", usage_end.cpu_ms - usage_start.cpu_ms);
    fmt::print("cpu_ms_per_frame    {:.3f}\n",
               run_frames > 0 ? (usage_end.cpu_ms - usage_first_frame.cpu_ms) / run_frames : 0.0);
    fmt::print("peak_rss_mb         {:.1f}\n", usage_end.peak_rss_mb);
    fmt::print("vm_rss_mb           {:.1f}\n", usage_end.vm_rss_mb);
    fmt::print("rss_anon_mb         {:.1f}\n", usage_end.rss_anon_mb);
    fmt::print("perf_system_fps     {:.2f}\n", perf.system_fps);
    fmt::print("perf_game_fps       {:.2f}\n", perf.game_fps);
    fmt::print("perf_emulation_speed {:.3f}\n", perf.emulation_speed);
    fmt::print("perf_vblank_ms      {:.3f}\n", perf.time_vblank_interval * 1000.0);
    fmt::print("perf_hle_svc_ms     {:.3f}\n", perf.time_hle_svc * 1000.0);
    fmt::print("perf_hle_ipc_ms     {:.3f}\n", perf.time_hle_ipc * 1000.0);
    fmt::print("perf_gpu_ms         {:.3f}\n", perf.time_gpu * 1000.0);
    fmt::print("perf_swap_ms        {:.3f}\n", perf.time_swap * 1000.0);
    fmt::print("perf_remaining_ms   {:.3f}\n", perf.time_remaining * 1000.0);
    if (save_state) {
        fmt::print("savestate_save_ms   {:.1f}\n", save_state->save_ms);
        fmt::print("savestate_load_ms   {:.1f}\n", save_state->load_ms);
        fmt::print("savestate_bytes     {}\n", save_state->bytes);
    }

    system.Shutdown();
    Common::Log::Stop();
    if (temp_user_dir && !options.keep_user_dir) {
        FileUtil::DeleteDirRecursively(options.user_dir);
    }
    return exit_code;
}
