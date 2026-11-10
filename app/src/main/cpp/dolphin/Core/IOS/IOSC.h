// Copyright 2017 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Eclipse: a cut-down IOSC with only what reading Wii discs needs (the common keys and AES decryption of a
// ticket's title key). The keys and handles are Dolphin's (Source/Core/Core/IOS/IOSC.cpp/.h).

#pragma once

#include <array>
#include <cstddef>

#include "Common/CommonTypes.h"
#include "Common/Crypto/ec.h"

namespace IOS
{
namespace ES
{
class CertReader;
}  // namespace ES

enum class SignatureType : u32
{
  RSA4096 = 0x00010000,
  RSA2048 = 0x00010001,
  ECC = 0x00010002,
};

enum class PublicKeyType : u32
{
  RSA4096 = 0,
  RSA2048 = 1,
  ECC = 2,
};

#pragma pack(push, 4)
struct SignatureRSA4096
{
  SignatureType type;
  u8 sig[0x200];
  u8 fill[0x3c];
  char issuer[0x40];
};
static_assert(sizeof(SignatureRSA4096) == 0x280, "Wrong size for SignatureRSA4096");

struct SignatureRSA2048
{
  SignatureType type;
  u8 sig[0x100];
  u8 fill[0x3c];
  char issuer[0x40];
};
static_assert(sizeof(SignatureRSA2048) == 0x180, "Wrong size for SignatureRSA2048");

struct SignatureECC
{
  SignatureType type;
  Common::ec::Signature sig;
  u8 fill[0x40];
  char issuer[0x40];
};
static_assert(sizeof(SignatureECC) == 0xc0, "Wrong size for SignatureECC");

// Source: https://wiibrew.org/wiki/Certificate_chain
struct CertHeader
{
  PublicKeyType public_key_type;
  char name[0x40];
  u32 id;
};

using RSA2048PublicKey = std::array<u8, 0x100>;

struct CertRSA4096RSA2048
{
  SignatureRSA4096 signature;
  CertHeader header;
  RSA2048PublicKey public_key;
  u8 exponent[0x4];
  u8 pad[0x34];
};
static_assert(sizeof(CertRSA4096RSA2048) == 0x400, "Wrong size for CertRSA4096RSA2048");

struct CertRSA2048RSA2048
{
  SignatureRSA2048 signature;
  CertHeader header;
  RSA2048PublicKey public_key;
  u8 exponent[0x4];
  u8 pad[0x34];
};
static_assert(sizeof(CertRSA2048RSA2048) == 0x300, "Wrong size for CertRSA2048RSA2048");

/// Used for device certificates
struct CertRSA2048ECC
{
  SignatureRSA2048 signature;
  CertHeader header;
  Common::ec::PublicKey public_key;
  std::array<u8, 60> padding;
};
static_assert(sizeof(CertRSA2048ECC) == 0x240, "Wrong size for CertRSA2048ECC");

/// Used for device signed certificates
struct CertECC
{
  SignatureECC signature;
  CertHeader header;
  Common::ec::PublicKey public_key;
  std::array<u8, 60> padding;
};
static_assert(sizeof(CertECC) == 0x180, "Wrong size for CertECC");
#pragma pack(pop)
}  // namespace IOS

namespace IOS::HLE
{
class IOSC final
{
public:
  enum class ConsoleType
  {
    Retail,
    RVT,
  };

  enum DefaultHandle : u32
  {
    HANDLE_COMMON_KEY = 4,
    HANDLE_NEW_COMMON_KEY = 11,
  };

  static constexpr std::array<DefaultHandle, 2> COMMON_KEY_HANDLES = {HANDLE_COMMON_KEY,
                                                                      HANDLE_NEW_COMMON_KEY};

  explicit IOSC(ConsoleType console_type = ConsoleType::Retail) : m_console_type(console_type) {}

  // AES-128-CBC decryption of [size] bytes with the given common key ([pid] is ignored).
  int Decrypt(u32 key_handle, u8* iv, const u8* input, size_t size, u8* output, u32 pid) const;

private:
  ConsoleType m_console_type;
};
}  // namespace IOS::HLE
