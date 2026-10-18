// SPDX-License-Identifier: Apache-2.0
#include "bounded_alac.h"
#include "../jni/jni_support.h"
#include "../jni/native_handles.h"

#include <vector>

namespace {

namespace bridge = fossmirror::jni;
using fossmirror::alac::Decoder;

auto& decoders() {
    static bridge::NativeHandles<Decoder, 0x41> registry;
    return registry;
}

jlong create(JNIEnv* env, jobject, jint rate, jint channels, jint frames) noexcept {
    return bridge::boundary<jlong>(env, 0, [&] { return decoders().create(rate, channels, frames); });
}

jbyteArray decode(JNIEnv* env, jobject, jlong handle, jbyteArray frame) noexcept {
    return bridge::boundary<jbyteArray>(env, nullptr, [&] {
        return decoders().with(handle, [&](Decoder& decoder) -> jbyteArray {
            const auto size = bridge::arraySize(env, frame, 1, Decoder::kMaxPacketBytes);
            std::vector<uint8_t> input(static_cast<size_t>(size));
            if (!bridge::readBytes(env, frame, size, input.data())) return nullptr;
            const auto pcm = decoder.decode(input.data(), input.size());
            return bridge::writeBytes(env, pcm.data(), pcm.size());
        });
    });
}

void flush(JNIEnv* env, jobject, jlong handle) noexcept {
    bridge::boundary(env, [&] { decoders().with(handle, [](Decoder& decoder) { decoder.flush(); }); });
}

void close(JNIEnv* env, jobject, jlong handle) noexcept {
    bridge::boundary(env, [&] { decoders().close(handle); });
}

const JNINativeMethod methods[] = {
    {"nativeCreate", "(III)J", reinterpret_cast<void*>(create)},
    {"nativeDecode", "(J[B)[B", reinterpret_cast<void*>(decode)},
    {"nativeFlush", "(J)V", reinterpret_cast<void*>(flush)},
    {"nativeClose", "(J)V", reinterpret_cast<void*>(close)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    return bridge::registerMethods(vm, "com/sal7one/tvtestproject/receiver/media/NativeAlacDecoder", methods);
}
