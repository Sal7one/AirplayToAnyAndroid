#include "airplay_crypto/fairplay_session.h"
#include "jni/jni_support.h"
#include "jni/native_handles.h"

#include <array>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using fossmirror::FairPlaySession;
namespace bridge = fossmirror::jni;

auto& sessions() {
    static bridge::NativeHandles<FairPlaySession, 0x46> registry;
    return registry;
}

jlong create(JNIEnv* env, jobject) noexcept {
    return bridge::boundary<jlong>(env, 0, [] { return sessions().create(); });
}

jbyteArray setup(JNIEnv* env, jobject, jlong handle, jbyteArray request) noexcept {
    return bridge::boundary<jbyteArray>(env, nullptr, [&] {
        return sessions().with(handle, [&](FairPlaySession& session) -> jbyteArray {
            const auto size = bridge::arraySize(env, request, 16, 164);
            if (size != 16 && size != 164) throw std::invalid_argument("Invalid FairPlay request length");
            std::array<uint8_t, 164> bytes{};
            bridge::ScopedWipe wipe(bytes.data(), bytes.size());
            if (!bridge::readBytes(env, request, size, bytes.data())) return nullptr;
            std::vector<uint8_t> response;
            std::string error;
            if (!session.handle_setup(bytes.data(), static_cast<size_t>(size), response, error))
                throw std::invalid_argument(error);
            return bridge::writeBytes(env, response.data(), response.size());
        });
    });
}

jbyteArray decryptKey(JNIEnv* env, jobject, jlong handle, jbyteArray encryptedKey) noexcept {
    return bridge::boundary<jbyteArray>(env, nullptr, [&] {
        return sessions().with(handle, [&](FairPlaySession& session) -> jbyteArray {
            const auto size = bridge::arraySize(env, encryptedKey, 72, 72);
            if (!session.can_decrypt_key()) throw std::logic_error("FairPlay handshake is not ready");
            std::array<uint8_t, 72> bytes{};
            bridge::ScopedWipe wipeInput(bytes.data(), bytes.size());
            if (!bridge::readBytes(env, encryptedKey, size, bytes.data())) return nullptr;
            std::array<uint8_t, 16> key{};
            bridge::ScopedWipe wipeKey(key.data(), key.size());
            std::string error;
            if (!session.decrypt_key(bytes.data(), bytes.size(), key, error)) throw std::invalid_argument(error);
            return bridge::writeBytes(env, key.data(), key.size());
        });
    });
}

void destroy(JNIEnv* env, jobject, jlong handle) noexcept {
    bridge::boundary(env, [&] { sessions().close(handle); });
}

const JNINativeMethod methods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(create)},
    {"nativeSetup", "(J[B)[B", reinterpret_cast<void*>(setup)},
    {"nativeDecryptKey", "(J[B)[B", reinterpret_cast<void*>(decryptKey)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(destroy)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    return bridge::registerMethods(vm, "com/sal7one/tvtestproject/receiver/protocol/NativeFairPlaySession", methods);
}
