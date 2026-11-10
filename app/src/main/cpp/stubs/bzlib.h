// Eclipse: bzip2 isn't included (only Dolphin's older WIA format uses it; RVZ uses Zstandard). These declarations let
// WIACompression.cpp compile; every call fails, so WIA files compressed with bzip2 report an error.
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef struct { char* next_in; unsigned int avail_in; unsigned int total_in_lo32; unsigned int total_in_hi32;
  char* next_out; unsigned int avail_out; unsigned int total_out_lo32; unsigned int total_out_hi32;
  void* state; void* (*bzalloc)(void*, int, int); void (*bzfree)(void*, void*); void* opaque; } bz_stream;
#define BZ_RUN 0
#define BZ_FINISH 2
#define BZ_OK 0
#define BZ_RUN_OK 1
#define BZ_FINISH_OK 3
#define BZ_STREAM_END 4
#define BZ_CONFIG_ERROR (-9)
int BZ2_bzCompressInit(bz_stream*, int, int, int);
int BZ2_bzCompress(bz_stream*, int);
int BZ2_bzCompressEnd(bz_stream*);
int BZ2_bzDecompressInit(bz_stream*, int, int);
int BZ2_bzDecompress(bz_stream*);
int BZ2_bzDecompressEnd(bz_stream*);
#ifdef __cplusplus
}
#endif
