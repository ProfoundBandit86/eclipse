# libeclipse_rvz.so: Dolphin's disc converter (DiscIO) for turning GameCube / Wii discs into RVZ.
# Only the parts of Dolphin the converter needs are here (dolphin/), with small trims marked "Eclipse".
# Licences: Dolphin GPL-2.0-or-later (dolphin/COPYING), zstd BSD, fmt MIT, liblzma public domain (0BSD),
# mbedtls Apache-2.0. Eclipse as a whole is GPL-3.0-or-later.
cmake_minimum_required(VERSION 3.22.1)
project(eclipse_rvz C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(EXT ${CMAKE_CURRENT_SOURCE_DIR}/externals)
set(DOL ${CMAKE_CURRENT_SOURCE_DIR}/dolphin)

# ---- zstd ----
# (plus zstd's seekable format, for Azahar's Z3DS files)
file(GLOB ZSTD_SRC ${EXT}/zstd/common/*.c ${EXT}/zstd/compress/*.c ${EXT}/zstd/decompress/*.c ${EXT}/zstd/contrib/seekable_format/*.c)
add_library(ezstd STATIC ${ZSTD_SRC})
target_include_directories(ezstd PUBLIC ${EXT}/zstd ${EXT}/zstd/contrib/seekable_format PRIVATE ${EXT}/zstd/common)
# no hand-written x86 assembly (it isn't used on ARM either)
target_compile_definitions(ezstd PRIVATE ZSTD_DISABLE_ASM)

# ---- liblzma (reading WIA / RVZ files made with LZMA) ----
file(GLOB LZMA_SRC
  ${EXT}/liblzma/check/*.c ${EXT}/liblzma/common/*.c ${EXT}/liblzma/delta/*.c ${EXT}/liblzma/lz/*.c
  ${EXT}/liblzma/lzma/*.c ${EXT}/liblzma/rangecoder/*.c ${EXT}/liblzma/simple/*.c ${EXT}/liblzma/tuklib/*.c)
list(FILTER LZMA_SRC EXCLUDE REGEX ".*_tablegen\.c$")
add_library(elzma STATIC ${LZMA_SRC})
target_include_directories(elzma PUBLIC ${EXT}/liblzma/api)
target_include_directories(elzma PRIVATE ${EXT}/liblzma ${EXT}/liblzma/common ${EXT}/liblzma/check ${EXT}/liblzma/delta
  ${EXT}/liblzma/lz ${EXT}/liblzma/lzma ${EXT}/liblzma/rangecoder ${EXT}/liblzma/simple ${EXT}/liblzma/tuklib)
target_compile_definitions(elzma PUBLIC LZMA_API_STATIC PRIVATE HAVE_CONFIG_H)

# ---- mbedtls (only AES, SHA-1, MD5) ----
add_library(embedtls STATIC
  ${EXT}/mbedtls/library/aes.c ${EXT}/mbedtls/library/aesni.c ${EXT}/mbedtls/library/sha1.c
  ${EXT}/mbedtls/library/md5.c ${EXT}/mbedtls/library/platform_util.c)
target_include_directories(embedtls PUBLIC ${EXT}/mbedtls/include PRIVATE ${EXT}/mbedtls/library)

# ---- ZArchive (.zar, Exzap's format: Xbox 360 discs for Xenia-based emulators). MIT-0. ----
add_library(ezarchive STATIC ${EXT}/zarchive/src/zarchivewriter.cpp ${EXT}/zarchive/src/zarchivereader.cpp ${EXT}/zarchive/src/sha_256.c)
target_include_directories(ezarchive PUBLIC ${EXT}/zarchive/include PRIVATE ${EXT}/zarchive/src)
target_compile_features(ezarchive PRIVATE cxx_std_20)
target_link_libraries(ezarchive PRIVATE ezstd)

# ---- Dolphin DiscIO ----
set(DOLPHIN_SRC
  ${DOL}/Common/ColorUtil.cpp ${DOL}/Common/Crypto/AES.cpp ${DOL}/Common/Crypto/SHA1.cpp
  ${DOL}/Common/DirectIOFile.cpp ${DOL}/Common/Hash.cpp ${DOL}/Common/IOFile.cpp
  ${DOL}/Core/IOS/ES/Formats.cpp ${DOL}/Core/IOS/IOSC.cpp
  ${DOL}/DiscIO/Blob.cpp ${DOL}/DiscIO/CISOBlob.cpp ${DOL}/DiscIO/CompressedBlob.cpp ${DOL}/DiscIO/DiscExtractor.cpp
  ${DOL}/DiscIO/DiscUtils.cpp ${DOL}/DiscIO/Enums.cpp ${DOL}/DiscIO/FileBlob.cpp ${DOL}/DiscIO/FileSystemGCWii.cpp
  ${DOL}/DiscIO/Filesystem.cpp ${DOL}/DiscIO/LaggedFibonacciGenerator.cpp ${DOL}/DiscIO/SplitFileBlob.cpp
  ${DOL}/DiscIO/Volume.cpp ${DOL}/DiscIO/VolumeDisc.cpp ${DOL}/DiscIO/VolumeGC.cpp ${DOL}/DiscIO/VolumeWad.cpp
  ${DOL}/DiscIO/VolumeWii.cpp ${DOL}/DiscIO/WIABlob.cpp ${DOL}/DiscIO/WIACompression.cpp ${DOL}/DiscIO/WbfsBlob.cpp
  ${DOL}/DiscIO/WiiEncryptionCache.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/eclipse_shims.cpp)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  # Dolphin's ARMv8 AES / SHA-1 / CRC32 paths (picked at run time from the CPU's features)
  list(APPEND DOLPHIN_SRC ${DOL}/Common/ArmCPUDetect.cpp)
  set(DOLPHIN_ARCH _ARCH_64=1 _M_ARM_64=1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
  # plain C paths on x86 (emulators / host tests)
  list(APPEND DOLPHIN_SRC ${DOL}/Common/x64CPUDetect.cpp)
  set(DOLPHIN_ARCH _ARCH_64=1)
else()
  list(APPEND DOLPHIN_SRC ${DOL}/Common/GenericCPUDetect.cpp)
  set(DOLPHIN_ARCH)
endif()

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  # as Dolphin: only these two use the ARMv8 crypto instructions, and only after checking the CPU has them
  # (CRC32 everywhere, as Dolphin's own build does)
  set_source_files_properties(${DOL}/Common/Crypto/AES.cpp ${DOL}/Common/Crypto/SHA1.cpp
    PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crc+crypto")
endif()
# Android's zlib has no crc32_z / adler32_z (see stubs/eclipse_zlib_compat.h)
set_source_files_properties(${DOL}/Common/Hash.cpp
  PROPERTIES COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/stubs/eclipse_zlib_compat.h")

if(ANDROID)
  add_library(eclipse_rvz SHARED ${DOLPHIN_SRC} ${CMAKE_CURRENT_SOURCE_DIR}/rvz_jni.cpp)
else()
  # host build for tests: a static library plus the JNI file when a JDK is around
  add_library(eclipse_rvz STATIC ${DOLPHIN_SRC})
endif()
target_include_directories(eclipse_rvz PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/stubs ${DOL} ${EXT}/fmt/include)
target_compile_definitions(eclipse_rvz PRIVATE FMT_HEADER_ONLY ${DOLPHIN_ARCH})
target_compile_options(eclipse_rvz PRIVATE -w -fvisibility=hidden)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  target_compile_options(eclipse_rvz PRIVATE -march=armv8-a+crc)
endif()
target_link_libraries(eclipse_rvz PRIVATE ezarchive ezstd elzma embedtls z)
if(ANDROID)
  find_library(log-lib log)
  target_link_libraries(eclipse_rvz PRIVATE ${log-lib})
  # 16 KB pages (Android 15+ devices)
  target_link_options(eclipse_rvz PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
  target_link_libraries(eclipse_rvz PRIVATE pthread)
endif()
