// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>

namespace fossmirror::avc {

constexpr size_t kMaxBytes = 8 * 1024 * 1024;
constexpr size_t kMaxNals = 4096;
constexpr int kIdr = 1;
constexpr int kVcl = 2;
constexpr int kParameterSets = 4;

struct Inspection {
    size_t annexBytes;
    int flags;
    int ppsId;
    int nalCount;
};

Inspection inspect(const uint8_t* data, size_t size, int lengthBytes);
// Validates again before writing. Equal input/output pointers are allowed only for 4-byte lengths.
Inspection convert(const uint8_t* data, size_t size, int lengthBytes, uint8_t* output, size_t capacity);

}  // namespace fossmirror::avc
