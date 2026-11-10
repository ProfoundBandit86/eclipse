// Copyright 2017 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Eclipse: see IOSC.h. Key values from Dolphin's Source/Core/Core/IOS/IOSC.cpp.

#include "Core/IOS/IOSC.h"

#include <cstring>

#include "Common/Crypto/AES.h"

namespace IOS::HLE
{
static constexpr std::array<u8, 16> RETAIL_COMMON_KEY = {0xeb, 0xe4, 0x2a, 0x22, 0x5e, 0x85, 0x93, 0xe4, 0x48,
                                          0xd9, 0xc5, 0x45, 0x73, 0x81, 0xaa, 0xf7};
static constexpr std::array<u8, 16> RVT_COMMON_KEY = {0xa1, 0x60, 0x4a, 0x6a, 0x71, 0x23, 0xb5, 0x29, 0xae,
                                          0x8b, 0xec, 0x32, 0xc8, 0x16, 0xfc, 0xaa};
static constexpr std::array<u8, 16> NEW_COMMON_KEY = {0x63, 0xb8, 0x2b, 0xb4, 0xf4, 0x61, 0x4e, 0x2e, 0x13,
                                            0xf2, 0xfe, 0xfb, 0xba, 0x4c, 0x9b, 0x7e};

int IOSC::Decrypt(u32 key_handle, u8* iv, const u8* input, size_t size, u8* output, u32) const
{
  const std::array<u8, 16>& key = key_handle == HANDLE_NEW_COMMON_KEY ? NEW_COMMON_KEY :
                                  m_console_type == ConsoleType::RVT ? RVT_COMMON_KEY :
                                                                       RETAIL_COMMON_KEY;
  auto ctx = Common::AES::CreateContextDecrypt(key.data());
  return ctx->Crypt(iv, iv, input, output, size) ? 0 : -1;  // (as IOSC::DecryptEncrypt: the IV is updated)
}
}  // namespace IOS::HLE
