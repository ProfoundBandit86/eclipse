// Eclipse's bridge to Dolphin's disc converter (DiscIO): GameCube / Wii disc images → RVZ, and a full read-back check.
// Licensed GPL-3.0-or-later as part of Eclipse; the Dolphin code it calls is GPL-2.0-or-later.
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include <cstdio>
#include <cstring>

#include "DiscIO/Blob.h"
#include "DiscIO/WIABlob.h"
#include "zarchive/zarchivereader.h"
#include "zarchive/zarchivewriter.h"
#include "zstd.h"
#include "zstd_seekable.h"

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


// ---------------------------------------------------------------- Z3DS (Azahar's compressed 3DS files)
//
// A 0x20-byte header ("Z3DS", the original file's magic, version 1, header size, metadata size, compressed and
// uncompressed sizes), the metadata (padded to 16 bytes), then the original file as seekable Zstandard (zstd's
// contrib/seekable_format), as Azahar writes it (src/common/zstd_compression.cpp, file_derived.cpp).

static void put_le(unsigned char* p, uint64_t v, int n)
{
  for (int i = 0; i < n; i++)
    p[i] = static_cast<unsigned char>(v >> (8 * i));
}

static uint64_t get_le(const unsigned char* p, int n)
{
  uint64_t v = 0;
  for (int i = 0; i < n; i++)
    v |= static_cast<uint64_t>(p[i]) << (8 * i);
  return v;
}

// null = done; else why not ("cancelled" when stopped)
JNIEXPORT jstring JNICALL Java_com_eclipse_launcher_data_Archives_nativeZ3ds(JNIEnv* env, jclass, jstring jsrc,
                                                                             jstring jout, jstring jmagic,
                                                                             jint frame, jbyteArray jmeta)
{
  s_progress.store(0.f);
  s_cancel.store(false);
  const std::string src = Str(env, jsrc), out = Str(env, jout), magic = Str(env, jmagic);
  std::vector<unsigned char> meta(env->GetArrayLength(jmeta));
  if (!meta.empty())
    env->GetByteArrayRegion(jmeta, 0, static_cast<jsize>(meta.size()), reinterpret_cast<jbyte*>(meta.data()));
  FILE* in = std::fopen(src.c_str(), "rb");
  if (!in)
    return env->NewStringUTF("can't read the file");
  FILE* o = std::fopen(out.c_str(), "wb");
  if (!o)
  {
    std::fclose(in);
    return env->NewStringUTF("can't write the new file");
  }
  std::fseek(in, 0, SEEK_END);
  const uint64_t total = static_cast<uint64_t>(ftello(in));
  std::fseek(in, 0, SEEK_SET);

  unsigned char header[0x20] = {};
  std::memcpy(header, "Z3DS", 4);
  for (int i = 0; i < 4; i++)
    header[4 + i] = i < static_cast<int>(magic.size()) ? static_cast<unsigned char>(magic[i]) : 0;
  header[8] = 1;               // version
  put_le(header + 10, 0x20, 2);  // header size
  const size_t metaSize = (meta.size() + 15) / 16 * 16;
  put_le(header + 12, metaSize, 4);
  bool ok = std::fwrite(header, 1, 0x20, o) == 0x20;
  if (metaSize)
  {
    meta.resize(metaSize, 0);
    ok = ok && std::fwrite(meta.data(), 1, metaSize, o) == metaSize;
  }

  ZSTD_seekable_CStream* cs = ZSTD_seekable_createCStream();
  ok = ok && cs && !ZSTD_isError(ZSTD_seekable_initCStream(cs, ZSTD_CLEVEL_DEFAULT, 0, static_cast<unsigned>(frame)));
  std::vector<unsigned char> ibuf(ZSTD_CStreamInSize()), obuf(ZSTD_CStreamOutSize());
  uint64_t done = 0, compressed = 0;
  bool cancelled = false;
  while (ok)
  {
    if (s_cancel.load())
    {
      cancelled = true;
      ok = false;
      break;
    }
    const size_t n = std::fread(ibuf.data(), 1, ibuf.size(), in);
    if (n == 0)
      break;
    ZSTD_inBuffer input = {ibuf.data(), n, 0};
    while (ok && input.pos < input.size)
    {
      ZSTD_outBuffer output = {obuf.data(), obuf.size(), 0};
      const size_t r = ZSTD_seekable_compressStream(cs, &output, &input);
      if (ZSTD_isError(r) || std::fwrite(obuf.data(), 1, output.pos, o) != output.pos)
        ok = false;
      compressed += output.pos;
    }
    done += n;
    if (total)
      s_progress.store(static_cast<float>(done) / static_cast<float>(total));
  }
  size_t remaining = 1;
  while (ok && remaining)
  {
    ZSTD_outBuffer output = {obuf.data(), obuf.size(), 0};
    remaining = ZSTD_seekable_endStream(cs, &output);
    if (ZSTD_isError(remaining) || std::fwrite(obuf.data(), 1, output.pos, o) != output.pos)
      ok = false;
    compressed += output.pos;
  }
  if (cs)
    ZSTD_seekable_freeCStream(cs);
  if (ok)
  {
    put_le(header + 16, compressed, 8);
    put_le(header + 24, done, 8);
    ok = std::fseek(o, 0, SEEK_SET) == 0 && std::fwrite(header, 1, 0x20, o) == 0x20;
  }
  std::fclose(in);
  ok = (std::fclose(o) == 0) && ok && done == total;
  if (ok)
    return nullptr;
  std::remove(out.c_str());
  return env->NewStringUTF(cancelled ? "cancelled" : "the compression failed");
}

// Decompresses [z3ds] and compares it with [src], byte for byte. 1 = same, 0 = not, -1 = cancelled.
JNIEXPORT jint JNICALL Java_com_eclipse_launcher_data_Archives_nativeZ3dsSame(JNIEnv* env, jclass, jstring jsrc,
                                                                              jstring jz)
{
  s_progress.store(0.f);
  s_cancel.store(false);
  const std::string src = Str(env, jsrc), z = Str(env, jz);
  FILE* a = std::fopen(src.c_str(), "rb");
  FILE* b = std::fopen(z.c_str(), "rb");
  int result = 0;
  if (a && b)
  {
    unsigned char h[0x20];
    if (std::fread(h, 1, 0x20, b) == 0x20 && std::memcmp(h, "Z3DS", 4) == 0 && h[8] == 1)
    {
      const uint64_t headerSize = get_le(h + 10, 2), metaSize = get_le(h + 12, 4);
      const uint64_t compressed = get_le(h + 16, 8), plain = get_le(h + 24, 8);
      std::fseek(a, 0, SEEK_END);
      const uint64_t srcSize = static_cast<uint64_t>(ftello(a));
      std::fseek(a, 0, SEEK_SET);
      if (plain == srcSize && fseeko(b, static_cast<off_t>(headerSize + metaSize), SEEK_SET) == 0)
      {
        ZSTD_DStream* ds = ZSTD_createDStream();
        ZSTD_initDStream(ds);
        std::vector<unsigned char> ibuf(ZSTD_DStreamInSize()), obuf(ZSTD_DStreamOutSize()), sbuf(ZSTD_DStreamOutSize());
        uint64_t left = compressed, checked = 0;
        bool good = true;
        while (good && left > 0)
        {
          if (s_cancel.load())
          {
            result = -1;
            good = false;
            break;
          }
          const size_t want = static_cast<size_t>(std::min<uint64_t>(left, ibuf.size()));
          const size_t n = std::fread(ibuf.data(), 1, want, b);
          if (n == 0)
          {
            good = false;
            break;
          }
          left -= n;
          ZSTD_inBuffer input = {ibuf.data(), n, 0};
          while (good && input.pos < input.size)
          {
            ZSTD_outBuffer output = {obuf.data(), obuf.size(), 0};
            if (ZSTD_isError(ZSTD_decompressStream(ds, &output, &input)))
            {
              good = false;
              break;
            }
            if (output.pos && (std::fread(sbuf.data(), 1, output.pos, a) != output.pos ||
                               std::memcmp(sbuf.data(), obuf.data(), output.pos) != 0))
              good = false;
            checked += output.pos;
          }
          if (plain)
            s_progress.store(static_cast<float>(checked) / static_cast<float>(plain));
        }
        ZSTD_freeDStream(ds);
        if (good && checked == plain)
          result = 1;
      }
    }
  }
  if (a)
    std::fclose(a);
  if (b)
    std::fclose(b);
  return result;
}

// ---------------------------------------------------------------- ZArchive (.zar), Exzap's format

namespace
{
struct ZarOut
{
  FILE* f = nullptr;
  bool ok = true;
  ZArchiveWriter* w = nullptr;
};
void ZarNewPart(const int32_t, void*) {}
void ZarWrite(const void* data, size_t length, void* ctx)
{
  auto* z = static_cast<ZarOut*>(ctx);
  if (z->ok && std::fwrite(data, 1, length, z->f) != length)
    z->ok = false;
}
}  // namespace

JNIEXPORT jlong JNICALL Java_com_eclipse_launcher_data_Archives_zarOpen(JNIEnv* env, jclass, jstring jout)
{
  auto* z = new ZarOut();
  z->f = std::fopen(Str(env, jout).c_str(), "wb");
  if (!z->f)
  {
    delete z;
    return 0;
  }
  z->w = new ZArchiveWriter(ZarNewPart, ZarWrite, z);
  return reinterpret_cast<jlong>(z);
}

JNIEXPORT jboolean JNICALL Java_com_eclipse_launcher_data_Archives_zarDir(JNIEnv* env, jclass, jlong h, jstring p)
{
  return reinterpret_cast<ZarOut*>(h)->w->MakeDir(Str(env, p).c_str(), true) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_eclipse_launcher_data_Archives_zarFile(JNIEnv* env, jclass, jlong h, jstring p)
{
  return reinterpret_cast<ZarOut*>(h)->w->StartNewFile(Str(env, p).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_eclipse_launcher_data_Archives_zarData(JNIEnv* env, jclass, jlong h, jbyteArray data,
                                                                       jint len)
{
  jbyte* b = env->GetByteArrayElements(data, nullptr);
  reinterpret_cast<ZarOut*>(h)->w->AppendData(b, static_cast<size_t>(len));
  env->ReleaseByteArrayElements(data, b, JNI_ABORT);
}

// finish = false: stop without finishing (cancelled / failed). Returns whether everything was written.
JNIEXPORT jboolean JNICALL Java_com_eclipse_launcher_data_Archives_zarClose(JNIEnv*, jclass, jlong h, jboolean finish)
{
  auto* z = reinterpret_cast<ZarOut*>(h);
  if (finish)
    z->w->Finalize();
  delete z->w;
  const bool ok = (std::fclose(z->f) == 0) && z->ok;
  delete z;
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_eclipse_launcher_data_Archives_zarReader(JNIEnv* env, jclass, jstring p)
{
  return reinterpret_cast<jlong>(ZArchiveReader::OpenFromFile(std::filesystem::path(Str(env, p))));
}

// a file's size, or -1 when there's no such file
JNIEXPORT jlong JNICALL Java_com_eclipse_launcher_data_Archives_zarSize(JNIEnv* env, jclass, jlong r, jstring p)
{
  auto* z = reinterpret_cast<ZArchiveReader*>(r);
  const ZArchiveNodeHandle n = z->LookUp(Str(env, p), true, false);
  if (n == ZARCHIVE_INVALID_NODE || !z->IsFile(n))
    return -1;
  return static_cast<jlong>(z->GetFileSize(n));
}

JNIEXPORT jint JNICALL Java_com_eclipse_launcher_data_Archives_zarRead(JNIEnv* env, jclass, jlong r, jstring p,
                                                                       jlong offset, jbyteArray buf, jint len)
{
  auto* z = reinterpret_cast<ZArchiveReader*>(r);
  const ZArchiveNodeHandle n = z->LookUp(Str(env, p), true, false);
  if (n == ZARCHIVE_INVALID_NODE)
    return -1;
  std::vector<unsigned char> tmp(static_cast<size_t>(len));
  const uint64_t got = z->ReadFromFile(n, static_cast<uint64_t>(offset), static_cast<uint64_t>(len), tmp.data());
  env->SetByteArrayRegion(buf, 0, static_cast<jsize>(got), reinterpret_cast<const jbyte*>(tmp.data()));
  return static_cast<jint>(got);
}

JNIEXPORT void JNICALL Java_com_eclipse_launcher_data_Archives_zarReaderClose(JNIEnv*, jclass, jlong r)
{
  delete reinterpret_cast<ZArchiveReader*>(r);
}

JNIEXPORT jfloat JNICALL Java_com_eclipse_launcher_data_Archives_nativeProgress(JNIEnv*, jclass)
{
  return s_progress.load();
}

JNIEXPORT void JNICALL Java_com_eclipse_launcher_data_Archives_nativeCancel(JNIEnv*, jclass)
{
  s_cancel.store(true);
}

}  // extern "C"
