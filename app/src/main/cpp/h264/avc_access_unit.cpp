// SPDX-License-Identifier: Apache-2.0
#include "avc_access_unit.h"

#include <array>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace fossmirror::avc {
namespace {

void require(bool value, const char* message) {
    if (!value) throw std::invalid_argument(message);
}

size_t lengthAt(const uint8_t* data, size_t size, size_t& pos, int bytes) {
    require(size - pos >= static_cast<size_t>(bytes), "Truncated AVC NAL length");
    uint32_t length = 0;
    for (int i = 0; i < bytes; ++i) length = (length << 8) | data[pos++];
    require(length != 0 && length <= size - pos, "Invalid AVC NAL length");
    return length;
}

class SliceHeader {
public:
    SliceHeader(const uint8_t* nal, size_t size) {
        unsigned zeros = 0;
        for (size_t i = 1; i < size && count_ < bytes_.size(); ++i) {
            const uint8_t value = nal[i];
            if (zeros >= 2 && value == 3) {
                require(i + 1 < size && nal[i + 1] <= 3, "Invalid emulation-prevention byte");
                zeros = 0;
                continue;
            }
            require(zeros < 2 || value > 2, "Unescaped H.264 start-code sequence");
            bytes_[count_++] = value;
            zeros = value == 0 ? zeros + 1 : 0;
        }
    }

    uint32_t ue(uint32_t maximum = std::numeric_limits<int32_t>::max()) {
        unsigned zeros = 0;
        while (bit() == 0) require(++zeros <= 30, "H.264 Exp-Golomb value exceeds limit");
        uint32_t suffix = 0;
        for (unsigned i = 0; i < zeros; ++i) suffix = (suffix << 1) | bit();
        const uint32_t value = (uint32_t{1} << zeros) - 1 + suffix;
        require(value <= maximum, "H.264 syntax value exceeds limit");
        return value;
    }

private:
    uint32_t bit() {
        require(position_ < count_ * 8, "Truncated H.264 slice header");
        const auto value = static_cast<uint32_t>((bytes_[position_ / 8] >> (7 - position_ % 8)) & 1);
        ++position_;
        return value;
    }

    std::array<uint8_t, 32> bytes_{};
    size_t count_ = 0;
    size_t position_ = 0;
};

}  // namespace

Inspection inspect(const uint8_t* data, size_t size, int lengthBytes) {
    require(lengthBytes == 1 || lengthBytes == 2 || lengthBytes == 4, "Invalid AVC NAL length size");
    require(data && size > 0 && size <= kMaxBytes, "Invalid AVC access-unit size");
    Inspection result{0, 0, -1, 0};
    size_t pos = 0;
    bool firstSlice = false;
    bool nonIdr = false;
    while (pos < size) {
        require(static_cast<size_t>(result.nalCount++) < kMaxNals, "Too many AVC NALs");
        const size_t length = lengthAt(data, size, pos, lengthBytes);
        const auto header = data[pos];
        const int type = header & 31;
        require((header & 0x80) == 0 && type >= 1 && type <= 23, "Invalid AVC NAL header");
        if (type == 5 || type == 7 || type == 8)
            require((header & 0x60) != 0, "Reference AVC NAL has zero nal_ref_idc");
        switch (type) {
            case 1:
            case 5: {
                SliceHeader bits(data + pos, length);
                if (bits.ue() == 0) firstSlice = true;
                bits.ue(9);
                const int pps = static_cast<int>(bits.ue(255));
                require(result.ppsId == -1 || result.ppsId == pps, "Slices use different PPS ids");
                result.ppsId = pps;
                result.flags |= kVcl;
                if (type == 5) result.flags |= kIdr; else nonIdr = true;
                break;
            }
            case 7:
            case 8: result.flags |= kParameterSets; break;
            case 6:
            case 9:
            case 10:
            case 11:
            case 12: break;
            default: throw std::invalid_argument("Unsupported AVC NAL type");
        }
        require(length + 4 <= kMaxBytes - result.annexBytes, "Annex-B access unit exceeds limit");
        result.annexBytes += length + 4;
        pos += length;
    }
    require(!((result.flags & kIdr) && nonIdr), "Mixed IDR and non-IDR slices");
    require(!(result.flags & kVcl) || firstSlice, "Incomplete access unit: missing first picture slice");
    return result;
}

Inspection convert(const uint8_t* data, size_t size, int lengthBytes, uint8_t* output, size_t capacity) {
    const auto result = inspect(data, size, lengthBytes);
    require(output && capacity >= result.annexBytes, "AVC output buffer is too small");
    require(data != output || lengthBytes == 4, "In-place AVC expansion is unsupported");
    const auto inputAddress = reinterpret_cast<uintptr_t>(data);
    const auto outputAddress = reinterpret_cast<uintptr_t>(output);
    if (data != output) require(outputAddress >= inputAddress ? outputAddress - inputAddress >= size :
        inputAddress - outputAddress >= result.annexBytes, "Overlapping AVC buffers are unsupported");
    size_t pos = 0;
    size_t written = 0;
    while (pos < size) {
        const size_t length = lengthAt(data, size, pos, lengthBytes);
        output[written] = output[written + 1] = output[written + 2] = 0;
        output[written + 3] = 1;
        if (output + written + 4 != data + pos) std::memcpy(output + written + 4, data + pos, length);
        written += length + 4;
        pos += length;
    }
    return result;
}

}  // namespace fossmirror::avc
