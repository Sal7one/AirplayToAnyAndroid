// SPDX-License-Identifier: Apache-2.0
#include "../bounded_alac.h"
#include "../apple/ALACBitUtilities.h"

#include <algorithm>
#include <fstream>
#include <iostream>
#include <iterator>
#include <random>
#include <stdexcept>
#include <string>

using fossmirror::alac::Decoder;

static void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template<class Action> static void rejected(Action action) {
    try { action(); } catch (const std::invalid_argument&) { return; }
    throw std::runtime_error("Malformed input was accepted");
}

static std::vector<uint8_t> read(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    check(file.good(), "Fixture not found");
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

static std::vector<uint8_t> declaredCount(uint32_t count) {
    std::vector<uint8_t> bytes(8, 0);
    size_t position = 0;
    auto put = [&](uint32_t value, unsigned width) {
        for (unsigned i = 0; i < width; ++i, ++position)
            bytes[position / 8] |= ((value >> (width - i - 1)) & 1) << (7 - position % 8);
    };
    put(1, 3); put(0, 4); put(0, 12); put(1, 1); put(0, 2); put(1, 1);
    put(count, 32); put(7, 3);
    return bytes;
}

int main(int argc, char** argv) {
    try {
        check(argc == 2, "Pass fixture directory");
        const std::string root = argv[1];
        Decoder decoder(44100, 2, 4096);
        for (const auto* name : {"ramp4096", "ramp352", "noise352"}) {
            std::cerr << "Fixture " << name << '\n';
            auto packet = read(root + "/" + name + ".alac");
            const auto expected = read(root + "/" + name + ".pcm");
            check(decoder.decode(packet.data(), packet.size()) == expected, "PCM differs from original samples");
            decoder.flush();
            check(decoder.decode(packet.data(), packet.size()) == expected, "Flush changed PCM");
            for (size_t size = 0; size < packet.size(); ++size)
                rejected([&] { decoder.decode(packet.data(), size); });
            packet.push_back(0);
            rejected([&] { decoder.decode(packet.data(), packet.size()); });
        }
        rejected([] { Decoder invalid(48000, 2, 4096); });
        rejected([] { Decoder invalid(44100, 1, 4096); });
        rejected([] { Decoder invalid(44100, 2, 351); });
        rejected([] { Decoder invalid(44100, 2, 4097); });
        for (const auto count : {0u, 4097u, UINT32_MAX}) {
            const auto packet = declaredCount(count);
            rejected([&] { decoder.decode(packet.data(), packet.size()); });
        }
        auto large = std::vector<uint8_t>(65537, 0);
        rejected([&] { decoder.decode(large.data(), large.size()); });
        const auto shortPacket = read(root + "/ramp352.alac");
        const auto longPacket = read(root + "/ramp4096.alac");
        Decoder small(44100, 2, 352);
        check(small.decode(shortPacket.data(), shortPacket.size()) == read(root + "/ramp352.pcm"), "352-frame decode failed");
        rejected([&] { small.decode(longPacket.data(), longPacket.size()); });

        // Exact-size buffers expose any lookahead beyond the packet to ASan.
        std::cerr << "Mutating 12000 packets\n";
        std::mt19937 random(0x414c4143);
        for (size_t attempt = 0; attempt < 12000; ++attempt) {
            if (attempt % 1000 == 0) std::cerr << "Mutation " << attempt << '\n';
            std::vector<uint8_t> packet;
            if (attempt % 2 == 0) {
                packet = shortPacket;
                for (unsigned n = 0; n < 1 + attempt % 8; ++n)
                    packet[random() % packet.size()] ^= static_cast<uint8_t>(1u << (random() % 8));
            } else {
                packet.resize(1 + random() % 2048);
                for (auto& value : packet) value = static_cast<uint8_t>(random());
            }
            try {
                const auto output = decoder.decode(packet.data(), packet.size());
                check(!output.empty() && output.size() <= 4096 * 4 && output.size() % 4 == 0,
                      "Native output bound violated");
            } catch (const std::invalid_argument&) { }
        }
        uint8_t byte = 0xa5;
        for (unsigned offset = 0; offset <= 8; ++offset) {
            BitBuffer bits;
            BitBufferInit(&bits, &byte, 1);
            BitBufferAdvance(&bits, offset);
            check(!bits.error && BitBufferRemaining(&bits) == 8 - offset, "Bitreader advance failed");
            BitBufferRead(&bits, 9 - offset);
            check(bits.error, "Bitreader accepted an overread");
            BitBufferReset(&bits);
            check(BitBufferRead(&bits, 8) == 0xa5, "Bitreader reset failed");
        }
        check(decoder.decode(shortPacket.data(), shortPacket.size()) == read(root + "/ramp352.pcm"),
              "Malformed packets poisoned the next decode");
        std::cout << "ALAC fixtures, every truncation, bounds, flush, and 12000 mutations passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
