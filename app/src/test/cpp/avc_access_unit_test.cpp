// SPDX-License-Identifier: Apache-2.0
#include "h264/avc_access_unit.h"

#include <algorithm>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

namespace avc = fossmirror::avc;

void check(bool value) { if (!value) throw std::runtime_error("AVC assertion failed"); }
template<class Action> void rejects(Action action) {
    try { action(); } catch (const std::invalid_argument&) { return; }
    throw std::runtime_error("Invalid AVC input accepted");
}

std::vector<uint8_t> packet(int width, const std::vector<std::vector<uint8_t>>& nals) {
    std::vector<uint8_t> result;
    for (const auto& nal : nals) {
        for (int i = width - 1; i >= 0; --i) result.push_back(static_cast<uint8_t>(nal.size() >> (8 * i)));
        result.insert(result.end(), nal.begin(), nal.end());
    }
    return result;
}

int main() {
    try {
        for (const int width : {1, 2, 4}) {
            const auto input = packet(width, {{9, 0xf0}, {0x65, 0xb8}});
            const auto info = avc::inspect(input.data(), input.size(), width);
            check(info.flags == (avc::kIdr | avc::kVcl) && info.ppsId == 0 && info.nalCount == 2);
            std::vector<uint8_t> output(info.annexBytes);
            avc::convert(input.data(), input.size(), width, output.data(), output.size());
            check(output == std::vector<uint8_t>({0, 0, 0, 1, 9, 0xf0, 0, 0, 0, 1, 0x65, 0xb8}));
            auto unchanged = output;
            rejects([&] { avc::convert(input.data(), input.size(), width, output.data(), output.size() - 1); });
            check(output == unchanged);
            for (size_t size = 0; size < input.size(); ++size) {
                // A complete leading AUD by itself is valid; all partial NALs are rejected.
                if (size == static_cast<size_t>(width + 2)) continue;
                rejects([&] { avc::inspect(input.data(), size, width); });
            }
        }
        auto inPlace = packet(4, {{0x65, 0xb8}});
        avc::convert(inPlace.data(), inPlace.size(), 4, inPlace.data(), inPlace.size());
        check(inPlace == std::vector<uint8_t>({0, 0, 0, 1, 0x65, 0xb8}));
        for (int width : {1, 2}) {
            auto input = packet(width, {{0x65, 0xb8}});
            input.reserve(20);
            rejects([&] { avc::convert(input.data(), input.size(), width, input.data(), 20); });
        }
        auto overlapping = packet(4, {{0x65, 0xb8}});
        overlapping.resize(20);
        rejects([&] { avc::convert(overlapping.data(), 6, 4, overlapping.data() + 1, 19); });
        for (const auto header : {0, 0x80, 0x85, 5, 24, 0x62, 0x74}) {
            const auto input = packet(4, {{static_cast<uint8_t>(header), 0xb8}});
            rejects([&] { avc::inspect(input.data(), input.size(), 4); });
        }
        for (const auto& nals : std::vector<std::vector<std::vector<uint8_t>>>{
            {{0x65}}, {{0x65, 0, 0, 0}}, {{0x65, 0, 0, 3}}, {{0x65, 0, 0, 3, 4}},
            {{0x65, 0xb8}, {0x41, 0xb8}}, {{0x65, 0xb4}, {0x65, 0xb8}}}) {
            const auto input = packet(4, nals);
            rejects([&] { avc::inspect(input.data(), input.size(), 4); });
        }
        const auto params = packet(4, {{0x67, 0x42}, {0x68, 0xc0}});
        check(avc::inspect(params.data(), params.size(), 4).flags == avc::kParameterSets);
        const auto tooMany = packet(1, std::vector<std::vector<uint8_t>>(4097, {12, 0xff}));
        rejects([&] { avc::inspect(tooMany.data(), tooMany.size(), 1); });
        const auto seed = packet(4, {{0x65, 0xb8}});
        rejects([&] { avc::inspect(seed.data(), seed.size(), 3); });
        rejects([&] { avc::inspect(nullptr, 2, 4); });
        rejects([&] { avc::inspect(seed.data(), avc::kMaxBytes + 1, 4); });

        std::mt19937 random(0x415643);
        for (size_t i = 0; i < 30000; ++i) {
            auto bytes = seed;
            if (i % 2 == 0) bytes.resize(random() % 1024);
            for (auto& value : bytes) value ^= static_cast<uint8_t>(random());
            try {
                const int width = i % 3 == 0 ? 1 : i % 3 == 1 ? 2 : 4;
                const auto info = avc::inspect(bytes.data(), bytes.size(), width);
                std::vector<uint8_t> output(info.annexBytes);
                avc::convert(bytes.data(), bytes.size(), width, output.data(), output.size());
                check(info.annexBytes <= avc::kMaxBytes && info.nalCount <= 4096);
            } catch (const std::invalid_argument&) { }
        }
        std::cout << "AVC conversion, bounds, truncation, overlap, slice headers and 30000 mutations passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
