#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fossmirror {

class FairPlaySession {
public:
    FairPlaySession() = default;
    FairPlaySession(const FairPlaySession&) = delete;
    FairPlaySession& operator=(const FairPlaySession&) = delete;
    ~FairPlaySession();
    bool handle_setup(const uint8_t* request,
                      size_t request_size,
                      std::vector<uint8_t>& response,
                      std::string& error);

    bool can_decrypt_key() const;
    bool decrypt_key(const uint8_t* encrypted_key,
                     size_t encrypted_key_size,
                     std::array<uint8_t, 16>& decrypted_key,
                     std::string& error) const;

private:
    std::array<uint8_t, 164> key_message_{};
    bool setup_started_ = false;
    bool has_key_message_ = false;
};

}  // namespace fossmirror
