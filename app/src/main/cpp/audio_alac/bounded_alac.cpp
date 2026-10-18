// SPDX-License-Identifier: Apache-2.0
#include "bounded_alac.h"

#include "apple/ALACBitUtilities.h"
#include "apple/ALACDecoder.h"

#include <algorithm>
#include <array>
#include <stdexcept>

namespace fossmirror::alac {
namespace {

uint32_t validate(int rate, int channels, int frames) {
    if (rate != 44100 || channels != 2 || frames < 352 || frames > 4096)
        throw std::invalid_argument("ALAC requires 44100 Hz, stereo PCM16, and 352..4096 samples/frame");
    return static_cast<uint32_t>(frames);
}

void put32(std::array<uint8_t, 24>& cookie, size_t offset, uint32_t value) {
    for (size_t i = 0; i < 4; ++i) cookie[offset + i] = static_cast<uint8_t>(value >> (24 - 8 * i));
}

}  // namespace

Decoder::Decoder(int rate, int channels, int frames)
    : samplesPerFrame_(validate(rate, channels, frames)), pcm_(samplesPerFrame_ * 2) {
    flush();
}

Decoder::~Decoder() = default;

void Decoder::flush() {
    std::array<uint8_t, 24> cookie{};
    put32(cookie, 0, samplesPerFrame_);
    cookie[5] = 16;
    cookie[6] = 40;
    cookie[7] = 10;
    cookie[8] = 14;
    cookie[9] = 2;
    cookie[11] = 255;
    put32(cookie, 20, 44100);
    auto fresh = std::make_unique<ALACDecoder>();
    const auto status = fresh->Init(cookie.data(), cookie.size());
    if (status == kALAC_MemFullError) throw std::bad_alloc();
    if (status != ALAC_noErr) throw std::runtime_error("ALAC configuration failed");
    decoder_ = std::move(fresh);
    std::fill(pcm_.begin(), pcm_.end(), 0);
}

std::vector<uint8_t> Decoder::decode(const uint8_t* packet, size_t size) {
    if (packet == nullptr || size == 0 || size > kMaxPacketBytes)
        throw std::invalid_argument("ALAC packet must contain 1..65536 bytes");
    BitBuffer bits;
    BitBufferInit(&bits, const_cast<uint8_t*>(packet), static_cast<uint32_t>(size));
    std::fill(pcm_.begin(), pcm_.end(), 0);
    uint32_t count = 0;
    const auto status = decoder_->Decode(&bits, reinterpret_cast<uint8_t*>(pcm_.data()),
                                         samplesPerFrame_, 2, &count);
    if (status != ALAC_noErr || bits.error || count == 0 || count > samplesPerFrame_) {
        flush();
        throw std::invalid_argument("Malformed, truncated, or unsupported ALAC frame");
    }
    std::vector<uint8_t> output(static_cast<size_t>(count) * 4);
    for (size_t i = 0; i < static_cast<size_t>(count) * 2; ++i) {
        const auto value = static_cast<uint16_t>(pcm_[i]);
        output[2 * i] = static_cast<uint8_t>(value);
        output[2 * i + 1] = static_cast<uint8_t>(value >> 8);
    }
    return output;
}

}  // namespace fossmirror::alac
