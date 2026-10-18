// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

class ALACDecoder;

namespace fossmirror::alac {

class Decoder final {
public:
    static constexpr size_t kMaxPacketBytes = 65536;

    Decoder(int sampleRate, int channels, int samplesPerFrame);
    ~Decoder();
    Decoder(const Decoder&) = delete;
    Decoder& operator=(const Decoder&) = delete;

    std::vector<uint8_t> decode(const uint8_t* packet, size_t size);
    void flush();

private:
    const uint32_t samplesPerFrame_;
    std::unique_ptr<ALACDecoder> decoder_;
    std::vector<int16_t> pcm_;
};

}  // namespace fossmirror::alac
