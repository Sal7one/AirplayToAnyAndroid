#include "airplay_crypto/fairplay_session.h"

#include <array>
#include <cassert>
#include <cstring>
#include <iostream>

int main() {
    fossmirror::FairPlaySession session;
    std::vector<uint8_t> response;
    std::string error;
    std::array<uint8_t, 16> setup{};
    std::array<uint8_t, 164> handshake{};
    std::array<uint8_t, 72> encrypted{};
    std::array<uint8_t, 16> key{};
    std::memcpy(setup.data(), "FPLY", 4);
    std::memcpy(handshake.data(), "FPLY", 4);
    setup[4] = handshake[4] = 3;
    assert(!session.decrypt_key(encrypted.data(), encrypted.size(), key, error));
    assert(!session.handle_setup(handshake.data(), handshake.size(), response, error));
    for (uint8_t mode = 0; mode < 4; ++mode) {
        setup[14] = mode;
        assert(session.handle_setup(setup.data(), setup.size(), response, error));
        assert(response.size() == 142 && response[13] == mode);
    }
    for (int mode = 4; mode < 256; ++mode) {
        handshake[12] = static_cast<uint8_t>(mode);
        assert(!session.handle_setup(handshake.data(), handshake.size(), response, error));
        assert(!session.can_decrypt_key());
    }
    handshake[12] = 0;
    assert(session.handle_setup(handshake.data(), handshake.size(), response, error));
    assert(response.size() == 32 && session.can_decrypt_key());
    assert(!session.decrypt_key(encrypted.data(), 71, key, error));
    handshake[0] = 0;
    assert(!session.handle_setup(handshake.data(), handshake.size(), response, error));
    assert(!session.can_decrypt_key());
    setup[14] = 4;
    assert(!session.handle_setup(setup.data(), setup.size(), response, error));
    std::cout << "FairPlay framing/state/mode validation passed\n";
}
