// Eclipse: Dolphin's Hash.cpp uses crc32_z / adler32_z (zlib 1.2.9+, Dolphin bundles zlib-ng). Android's own zlib
// doesn't declare them, so Hash.cpp gets these wrappers (force-included by CMakeLists.txt; same results, done in
// pieces small enough for the classic crc32 / adler32).
#pragma once
#include <stddef.h>
#include <zlib.h>

static inline uLong eclipse_crc32_z(uLong crc, const Bytef* buf, size_t len)
{
  if (buf == nullptr)
    return crc32(crc, nullptr, 0);
  while (len > 0)
  {
    const uInt n = len > 0x40000000u ? 0x40000000u : static_cast<uInt>(len);
    crc = crc32(crc, buf, n);
    buf += n;
    len -= n;
  }
  return crc;
}

static inline uLong eclipse_adler32_z(uLong adler, const Bytef* buf, size_t len)
{
  if (buf == nullptr)
    return adler32(adler, nullptr, 0);
  while (len > 0)
  {
    const uInt n = len > 0x40000000u ? 0x40000000u : static_cast<uInt>(len);
    adler = adler32(adler, buf, n);
    buf += n;
    len -= n;
  }
  return adler;
}

#define crc32_z eclipse_crc32_z
#define adler32_z eclipse_adler32_z
