// Copyright 2020-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <charconv>
#include <chrono>
#include <cstring>
#include <istream>
#include <ostream>
#include <cryptopp/hex.h>
#include <fmt/ranges.h>
#include "common/archives.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "common/scm_rev.h"
#include "common/swap.h"
#include "common/zstd_stream.h"
#include "core/core.h"
#include "core/loader/loader.h"
#include "core/movie.h"
#include "core/savestate.h"
#include "network/network.h"

namespace Core {

#pragma pack(push, 1)
struct CSTHeader {
    std::array<u8, 4> filetype{};    /// Unique Identifier to check the file type (always "CST"0x1B)
    u64_le program_id{};             /// ID of the ROM being executed. Also called title_id
    std::array<u8, 20> revision{};   /// Git hash of the revision this savestate was created with
    u64_le time{};                   /// The time when this save state was created
    std::array<u8, 20> build_name{}; /// The build name (Canary/Nightly) with the version number
    u32_le zero{};                   /// Should be zero, just in case.
    std::array<u8, 20> build_version{}; /// Latest build version, used as compatibility.
    u32_le zero_2{};                    /// Should be zero, just in case.

    std::array<u8, 168> reserved{}; /// Make heading 256 bytes so it has consistent size
};
static_assert(sizeof(CSTHeader) == 256, "CSTHeader should be 256 bytes");
#pragma pack(pop)

constexpr std::array<u8, 4> header_magic_bytes{{'C', 'S', 'T', 0x1B}};

static std::string GetSaveStatePath(u64 program_id, u64 movie_id, u32 slot) {
    const std::string slot_name =
        slot == AutoSaveStateSlot ? "autosave" : fmt::format("{:02d}", slot);
    if (movie_id) {
        return fmt::format("{}{:016X}.movie{:016X}.{}.cst",
                           FileUtil::GetUserPath(FileUtil::UserPath::StatesDir), program_id,
                           movie_id, slot_name);
    } else {
        return fmt::format("{}{:016X}.{}.cst", FileUtil::GetUserPath(FileUtil::UserPath::StatesDir),
                           program_id, slot_name);
    }
}

static std::string GetLastBootMarkerPath(u64 program_id) {
    return fmt::format("{}{:016X}.lastboot", FileUtil::GetUserPath(FileUtil::UserPath::StatesDir),
                       program_id);
}

static bool ValidateSaveState(const CSTHeader& header, SaveStateInfo& info, u64 program_id,
                              u64 movie_id) {
    const auto path = GetSaveStatePath(program_id, movie_id, info.slot);
    if (header.filetype != header_magic_bytes) {
        LOG_WARNING(Core, "Invalid save state file {}", path);
        return false;
    }
    info.time = header.time;

    if (header.program_id != program_id) {
        LOG_WARNING(Core, "Save state file isn't for the current game {}", path);
        return false;
    }
    const std::string revision = fmt::format("{:02x}", fmt::join(header.revision, ""));
    const std::string build_name =
        header.zero == 0 ? reinterpret_cast<const char*>(header.build_name.data()) : "";
    const std::string build_version =
        header.zero_2 == 0 ? reinterpret_cast<const char*>(header.build_version.data()) : "";

    if (revision == Common::g_scm_rev) {
        info.status = SaveStateInfo::ValidationStatus::OK;
    } else {
        info.build_name = build_name;
        info.build_version = build_version;

        info.status = Common::g_build_version == info.build_version
                          ? SaveStateInfo::ValidationStatus::RevisionMismatch
                          : SaveStateInfo::ValidationStatus::BuildMismatch;
    }
    return true;
}

std::vector<SaveStateInfo> ListSaveStates(u64 program_id, u64 movie_id) {
    std::vector<SaveStateInfo> result;
    result.reserve(SaveStateSlotCount);
    for (u32 slot = 0; slot <= SaveStateSlotCount; ++slot) {
        const auto path = GetSaveStatePath(program_id, movie_id, slot);
        if (!FileUtil::Exists(path)) {
            continue;
        }

        SaveStateInfo info;
        info.slot = slot;

        FileUtil::IOFile file(path, "rb");
        if (!file) {
            LOG_ERROR(Core, "Could not open file {}", path);
            continue;
        }
        CSTHeader header;
        if (file.GetSize() < sizeof(header)) {
            LOG_ERROR(Core, "File too small {}", path);
            continue;
        }
        if (file.ReadBytes(&header, sizeof(header)) != sizeof(header)) {
            LOG_ERROR(Core, "Could not read from file {}", path);
            continue;
        }
        if (!ValidateSaveState(header, info, program_id, movie_id)) {
            continue;
        }

        result.emplace_back(std::move(info));
    }
    return result;
}

SaveStateInfo GetSaveStateInfo(u64 program_id, u64 movie_id, u32 slot) {
    SaveStateInfo info{};
    info.slot = std::numeric_limits<u32>::max();

    const auto path = GetSaveStatePath(program_id, movie_id, slot);
    if (!FileUtil::Exists(path)) {
        return info;
    }

    FileUtil::IOFile file(path, "rb");
    if (!file) {
        LOG_ERROR(Core, "Could not open file {}", path);
        return info;
    }
    CSTHeader header;
    if (file.GetSize() < sizeof(header)) {
        LOG_ERROR(Core, "File too small {}", path);
        return info;
    }
    if (file.ReadBytes(&header, sizeof(header)) != sizeof(header)) {
        LOG_ERROR(Core, "Could not read from file {}", path);
        return info;
    }
    if (ValidateSaveState(header, info, program_id, movie_id)) {
        info.slot = slot;
    }
    return info;
}

SaveStateInfo GetAutoSaveStateInfo(u64 program_id, u64 movie_id) {
    return GetSaveStateInfo(program_id, movie_id, AutoSaveStateSlot);
}

u64 GetLastNormalBootTime(u64 program_id) {
    const auto path = GetLastBootMarkerPath(program_id);
    if (!FileUtil::Exists(path)) {
        return 0;
    }
    std::string contents;
    if (!FileUtil::ReadFileToString(true, path, contents)) {
        LOG_WARNING(Core, "Could not read boot marker {}", path);
        return 0;
    }
    u64 time{};
    const auto* end = contents.data() + contents.size();
    if (std::from_chars(contents.data(), end, time).ec != std::errc{}) {
        LOG_WARNING(Core, "Malformed boot marker {}", path);
        return 0;
    }
    return time;
}

void RecordNormalBoot(u64 program_id, u64 time) {
    const auto path = GetLastBootMarkerPath(program_id);
    if (!FileUtil::CreateFullPath(path)) {
        LOG_WARNING(Core, "Could not create path for boot marker {}", path);
        return;
    }
    if (!FileUtil::WriteStringToFile(true, path, fmt::format("{}", time))) {
        LOG_WARNING(Core, "Could not write boot marker {}", path);
    }
}

AutoSaveResumeStatus ClassifyAutoSaveState(const SaveStateInfo& autosave, u64 last_boot_time) {
    if (autosave.slot != AutoSaveStateSlot) {
        return AutoSaveResumeStatus::None;
    }
    if (autosave.status == SaveStateInfo::ValidationStatus::BuildMismatch) {
        return AutoSaveResumeStatus::BuildMismatch;
    }
    // The header time has one-second resolution, so a state written within the same second as
    // the boot that preceded it still counts as newer than that boot
    if (autosave.time < last_boot_time) {
        return AutoSaveResumeStatus::Stale;
    }
    return AutoSaveResumeStatus::Resumable;
}

AutoSaveResumeStatus CheckAutoSaveState(u64 program_id, u64 movie_id, SaveStateInfo* info) {
    const auto autosave = GetAutoSaveStateInfo(program_id, movie_id);
    if (info) {
        *info = autosave;
    }
    return ClassifyAutoSaveState(autosave, GetLastNormalBootTime(program_id));
}

static CSTHeader MakeHeader(u64 title_id) {
    CSTHeader header{};
    header.filetype = header_magic_bytes;
    header.program_id = title_id;
    std::string rev_bytes;
    CryptoPP::StringSource ss(Common::g_scm_rev, true,
                              new CryptoPP::HexDecoder(new CryptoPP::StringSink(rev_bytes)));
    std::memcpy(header.revision.data(), rev_bytes.data(),
                std::min(rev_bytes.size(), sizeof(header.revision)));
    header.time = std::chrono::duration_cast<std::chrono::seconds>(
                      std::chrono::system_clock::now().time_since_epoch())
                      .count();
    const std::string build_fullname = Common::g_build_fullname;
    std::memcpy(header.build_name.data(), build_fullname.c_str(),
                std::min(build_fullname.length(), sizeof(header.build_name) - 1));
    const std::string build_version = Common::g_build_version;
    std::memcpy(header.build_version.data(), build_version.c_str(),
                std::min(build_version.length(), sizeof(header.build_version) - 1));
    return header;
}

/**
 * Serializes the system through a Zstandard stream into `sink`, chunk by chunk. A New 3DS
 * state serializes to a few hundred MiB, so it is never held in memory as a whole: the
 * frontend that autosaves in the background on a memory-tight phone would otherwise provoke
 * the very low-memory kill it is trying to insure against.
 */
static void SerializeCompressed(const System& system,
                                Common::Compression::ZSTDOutputStreamBuf::Sink sink) {
    Common::Compression::ZSTDOutputStreamBuf compressor{std::move(sink)};
    {
        std::ostream stream{&compressor};
        oarchive oa{stream};
        oa & system;
        if (!stream) {
            throw std::runtime_error("Could not write the save state");
        }
    }
    if (!compressor.Finish()) {
        throw std::runtime_error("Could not compress the save state");
    }
}

/// Deserializes the system from a Zstandard stream pulled from `source`, chunk by chunk
static void DeserializeCompressed(System& system,
                                  Common::Compression::ZSTDInputStreamBuf::Source source) {
    Common::Compression::ZSTDInputStreamBuf decompressor{std::move(source)};
    std::istream stream{&decompressor};
    try {
        iarchive ia{stream};
        ia & system;
    } catch (const std::exception&) {
        if (decompressor.Failed()) {
            throw std::runtime_error("The save state is corrupt or truncated");
        }
        throw;
    }
}

void System::SaveState(u32 slot) const {
    if (app_loader) {
        if (!app_loader->SupportsSaveStates()) {
            throw std::runtime_error("The current app loader doesn't support save states");
        }
    }

    const u64 movie_id = movie.GetCurrentMovieID();
    const auto path = GetSaveStatePath(title_id, movie_id, slot);
    if (!FileUtil::CreateFullPath(path)) {
        throw std::runtime_error("Could not create path " + path);
    }

    // Written under a temporary name and moved into place once complete, so a save that fails
    // part-way (or a process killed during it) leaves the previous state in that slot intact
    const auto temp_path = path + ".tmp";
    {
        FileUtil::IOFile file(temp_path, "wb");
        if (!file) {
            throw std::runtime_error("Could not open file " + temp_path);
        }
        const CSTHeader header = MakeHeader(title_id);
        if (file.WriteBytes(&header, sizeof(header)) != sizeof(header)) {
            throw std::runtime_error("Could not write to file " + temp_path);
        }
        try {
            SerializeCompressed(*this, [&file](std::span<const u8> chunk) {
                return file.WriteBytes(chunk.data(), chunk.size()) == chunk.size();
            });
        } catch (...) {
            file.Close();
            FileUtil::Delete(temp_path);
            throw;
        }
    }
    // Rename replaces on every native filesystem; only fall back to delete-then-rename for
    // backends that refuse to overwrite, so the old state is kept as long as possible
    if (!FileUtil::Rename(temp_path, path) &&
        !(FileUtil::Delete(path) && FileUtil::Rename(temp_path, path))) {
        FileUtil::Delete(temp_path);
        throw std::runtime_error("Could not move " + temp_path + " to " + path);
    }
}

void System::LoadState(u32 slot) {
    if (app_loader) {
        if (!app_loader->SupportsSaveStates()) {
            throw std::runtime_error("The current app loader doesn't support save states");
        }
    }
    auto room_member = Network::GetRoomMember().lock();
    if (room_member && room_member->IsConnected()) {
        throw std::runtime_error("Unable to load while connected to multiplayer");
    }

    const u64 movie_id = movie.GetCurrentMovieID();
    const auto path = GetSaveStatePath(title_id, movie_id, slot);

    FileUtil::IOFile file(path, "rb");
    if (!file) {
        throw std::runtime_error("Could not open file " + path);
    }

    // load header
    CSTHeader header;
    if (file.ReadBytes(&header, sizeof(header)) != sizeof(header)) {
        throw std::runtime_error("Could not read from file at " + path);
    }

    // validate header
    SaveStateInfo info;
    info.slot = slot;
    if (!ValidateSaveState(header, info, title_id, movie_id) ||
        info.status == SaveStateInfo::ValidationStatus::BuildMismatch) {
        throw std::runtime_error("Invalid savestate");
    }

    // Deserialize
    DeserializeCompressed(
        *this, [&file](std::span<u8> chunk) { return file.ReadBytes(chunk.data(), chunk.size()); });
}

std::vector<u8> System::SaveStateBuffer() const {
    const CSTHeader header = MakeHeader(title_id);
    std::vector<u8> result(reinterpret_cast<const u8*>(&header),
                           reinterpret_cast<const u8*>(&header) + sizeof(header));
    SerializeCompressed(*this, [&result](std::span<const u8> chunk) {
        result.insert(result.end(), chunk.begin(), chunk.end());
        return true;
    });
    return result;
}

bool System::LoadStateBuffer(std::vector<u8> buffer) {
    CSTHeader header;

    if (buffer.size() < sizeof(header)) {
        LOG_ERROR(Core, "Save state too small");
        return false;
    }

    std::memcpy(&header, buffer.data(), sizeof(header));

    if (header.filetype != header_magic_bytes) {
        LOG_ERROR(Core, "Invalid save state");
        return false;
    }

    if (header.program_id != title_id) {
        LOG_ERROR(Core, "Save state isn't for the current game");
        return false;
    }
    std::string revision = fmt::format("{:02x}", fmt::join(header.revision, ""));
    if (revision != Common::g_scm_rev) {
        LOG_ERROR(Core,
                  "Save state file created from a different revision (core: {}, savestate: {})",
                  Common::g_scm_rev, revision);
        return false;
    }

    // Deserialize
    std::size_t offset = sizeof(CSTHeader);
    DeserializeCompressed(*this, [&buffer, &offset](std::span<u8> chunk) {
        const std::size_t count = std::min(chunk.size(), buffer.size() - offset);
        std::memcpy(chunk.data(), buffer.data() + offset, count);
        offset += count;
        return count;
    });

    return true;
}

} // namespace Core
