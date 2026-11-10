// Eclipse's bridge to Dolphin's disc converter (DiscIO): GameCube / Wii disc images → RVZ, and a full read-back check.
// Licensed GPL-3.0-or-later as part of Eclipse; the Dolphin code it calls is GPL-2.0-or-later.
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "DiscIO/Blob.h"
#include "DiscIO/WIABlob.h"

namespace
{
std::string Str(JNIEnv* env, jstring s)
{
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c ? c : "");
  if (c)
    env->ReleaseStringUTFChars(s, c);
  return out;
}
}  // namespace

extern "C" {

// Dolphin reports progress from its own worker threads, which can't call into Java, so the conversion only records it
// here: Rvz.kt reads it with nativeProgress() and stops it with nativeCancel(). Eclipse runs one conversion at a time.
static std::atomic<float> s_progress{0.f};
static std::atomic<bool> s_cancel{false};

JNIEXPORT jfloat JNICALL Java_com_eclipse_launcher_data_Rvz_nativeProgress(JNIEnv*, jclass)
{
  return s_progress.load();
}

JNIEXPORT void JNICALL Java_com_eclipse_launcher_data_Rvz_nativeCancel(JNIEnv*, jclass)
{
  s_cancel.store(true);
}

// Blocks until done. null = done; otherwise why it failed ("cancelled" when Eclipse stopped it).
JNIEXPORT jstring JNICALL Java_com_eclipse_launcher_data_Rvz_nativeConvert(JNIEnv* env, jclass, jstring jin,
                                                                           jstring jout, jint level)
{
  const std::string in = Str(env, jin), out = Str(env, jout);
  s_progress.store(0.f);
  s_cancel.store(false);
  auto reader = DiscIO::CreateBlobReader(in);
  if (!reader)
    return env->NewStringUTF("can't read this disc image");
  // RVZ with Zstandard and 128 KiB chunks: Dolphin's own recommended settings. Lossless (nothing is scrubbed).
  const bool ok = DiscIO::ConvertToWIAOrRVZ(
      reader.get(), in, out, true, DiscIO::WIARVZCompressionType::Zstd, std::clamp<int>(level, 1, 22), 128 * 1024,
      [](const std::string&, float p) {
        s_progress.store(p);
        return !s_cancel.load();
      });
  if (ok)
    return nullptr;
  return env->NewStringUTF(s_cancel.load() ? "cancelled" : "the conversion failed");
}

// Reads [a] and [b] (any format Dolphin reads) and checks every byte is the same, reporting progress like the
// conversion. 1 = same, 0 = different or unreadable, -1 = cancelled.
JNIEXPORT jint JNICALL Java_com_eclipse_launcher_data_Rvz_nativeSame(JNIEnv* env, jclass, jstring ja, jstring jb)
{
  s_progress.store(0.f);
  s_cancel.store(false);
  auto a = DiscIO::CreateBlobReader(Str(env, ja));
  auto b = DiscIO::CreateBlobReader(Str(env, jb));
  if (!a || !b)
    return 0;
  const u64 size = a->GetDataSize();
  if (size == 0 || b->GetDataSize() != size)
    return 0;
  constexpr u64 CHUNK = 2 * 1024 * 1024;
  std::vector<u8> x(CHUNK), y(CHUNK);
  for (u64 pos = 0; pos < size; pos += CHUNK)
  {
    if (s_cancel.load())
      return -1;
    const u64 n = std::min(CHUNK, size - pos);
    if (!a->Read(pos, n, x.data()) || !b->Read(pos, n, y.data()))
      return 0;
    if (!std::equal(x.begin(), x.begin() + n, y.begin()))
      return 0;
    s_progress.store(static_cast<float>(pos + n) / static_cast<float>(size));
  }
  return 1;
}

}  // extern "C"
