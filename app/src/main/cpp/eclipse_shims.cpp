// Eclipse's RVZ converter: small replacements for parts of Dolphin that only matter inside the emulator (logging,
// pop-up messages, translations, text-encoding conversion of game titles, deleting files, bzip2).
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdio>
#include <string>
#include <string_view>

#include <bzlib.h>
#include <fmt/format.h>

#include "Common/FileUtil.h"
#include "Common/Logging/Log.h"
#include "Common/MsgHandler.h"
#include "Common/StringUtil.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace Common::Log
{
void GenericLogFmtImpl(LogLevel level, LogType, const char*, int, fmt::string_view format, const fmt::format_args& args)
{
  if (level > LogLevel::LWARNING) return;
  const std::string s = fmt::vformat(format, args);
#ifdef __ANDROID__
  __android_log_write(ANDROID_LOG_WARN, "EclipseRVZ", s.c_str());
#else
  std::fprintf(stderr, "[rvz] %s\n", s.c_str());
#endif
}
}  // namespace Common::Log

namespace Common
{
std::string GetStringT(const char* string) { return string; }

bool MsgAlertFmtImpl(bool, MsgType, Common::Log::LogType, const char*, int, fmt::string_view format, const fmt::format_args& args)
{
  const std::string s = fmt::vformat(format, args);
#ifdef __ANDROID__
  __android_log_write(ANDROID_LOG_ERROR, "EclipseRVZ", s.c_str());
#else
  std::fprintf(stderr, "[rvz] %s\n", s.c_str());
#endif
  return false;
}
}  // namespace Common

// Game titles: plain ASCII is kept, anything else becomes '?' (the converter never shows titles)
std::string CP1252ToUTF8(std::string_view input)
{
  std::string out;
  for (unsigned char c : input) out += c < 0x80 ? char(c) : '?';
  return out;
}
std::string SHIFTJISToUTF8(std::string_view input) { return CP1252ToUTF8(input); }
std::string UTF16BEToUTF8(const char16_t* str, size_t max_size)
{
  std::string out;
  for (size_t i = 0; i < max_size && str[i]; ++i)
  {
    const unsigned v = ((unsigned(str[i]) & 0xff) << 8) | ((unsigned(str[i]) >> 8) & 0xff);
    out += v < 0x80 ? char(v) : '?';
  }
  return out;
}

namespace File
{
bool Delete(const std::string& filename, IfAbsentBehavior) { return std::remove(filename.c_str()) == 0; }
}  // namespace File

extern "C" {
int BZ2_bzCompressInit(bz_stream*, int, int, int) { return BZ_CONFIG_ERROR; }
int BZ2_bzCompress(bz_stream*, int) { return BZ_CONFIG_ERROR; }
int BZ2_bzCompressEnd(bz_stream*) { return BZ_OK; }
int BZ2_bzDecompressInit(bz_stream*, int, int) { return BZ_CONFIG_ERROR; }
int BZ2_bzDecompress(bz_stream*) { return BZ_CONFIG_ERROR; }
int BZ2_bzDecompressEnd(bz_stream*) { return BZ_OK; }
}

// --- string and file helpers the disc code uses (simple versions of Dolphin's StringUtil / FileUtil) ---
#include <filesystem>
std::string ReplaceAll(std::string result, std::string_view src, std::string_view dest)
{
  if (src.empty()) return result;
  size_t pos = 0;
  while ((pos = result.find(src, pos)) != std::string::npos) { result.replace(pos, src.size(), dest); pos += dest.size(); }
  return result;
}
std::string_view StripSpaces(std::string_view s)
{
  const auto b = s.find_first_not_of(" \t\r\n");
  if (b == std::string_view::npos) return {};
  const auto e = s.find_last_not_of(" \t\r\n");
  return s.substr(b, e - b + 1);
}
void TruncateToCString(std::string* s)
{
  const size_t z = s->find('\0');
  if (z != std::string::npos) s->resize(z);
}
namespace File
{
bool Exists(const std::string& path) { std::error_code ec; return std::filesystem::exists(path, ec); }
u64 GetSize(const std::string& path) { std::error_code ec; const auto n = std::filesystem::file_size(path, ec); return ec ? 0 : n; }
bool CreateFullPath(std::string_view full_path)
{
  std::error_code ec;
  const std::filesystem::path p{std::string(full_path)};
  if (!p.has_parent_path()) return true;
  std::filesystem::create_directories(p.parent_path(), ec);
  return !ec;
}
bool Rename(const std::string& src, const std::string& dst) { std::error_code ec; std::filesystem::rename(src, dst, ec); return !ec; }
}  // namespace File
namespace File
{
u64 GetSize(FILE* f)
{
  const long pos = std::ftell(f);
  if (std::fseek(f, 0, SEEK_END) != 0) return 0;
  const long size = std::ftell(f);
  std::fseek(f, pos, SEEK_SET);
  return size < 0 ? 0 : u64(size);
}
}  // namespace File
