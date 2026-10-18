// SPDX-License-Identifier: Apache-2.0
#include "avc_access_unit.h"
#include "../jni/jni_support.h"

#include <memory>
#include <vector>

namespace {

namespace bridge = fossmirror::jni;
namespace avc = fossmirror::avc;

jbyteArray parse(JNIEnv* env, jobject, jbyteArray payload, jint lengthBytes, jintArray metadata) noexcept {
    return bridge::boundary<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        const auto size = bridge::arraySize(env, payload, 1, avc::kMaxBytes);
        if (!metadata || env->GetArrayLength(metadata) != 3)
            throw std::invalid_argument("AVC metadata must contain three integers");
        if (lengthBytes != 1 && lengthBytes != 2 && lengthBytes != 4)
            throw std::invalid_argument("Invalid AVC NAL length size");
        // GetByteArrayRegion initializes the entire buffer; avoid zero-filling it twice.
        std::unique_ptr<uint8_t[]> bytes(new uint8_t[static_cast<size_t>(size)]);
        bridge::ScopedWipe wipe(bytes.get(), static_cast<size_t>(size));
        if (!bridge::readBytes(env, payload, size, bytes.get())) return nullptr;
        if (lengthBytes == 4) {
            const auto result = avc::convert(bytes.get(), size, lengthBytes, bytes.get(), size);
            bridge::LocalRef<jbyteArray> converted(env, bridge::writeBytes(env, bytes.get(), size));
            if (!converted.get() || env->ExceptionCheck()) return nullptr;
            const jint values[] = {result.flags, result.ppsId, result.nalCount};
            env->SetIntArrayRegion(metadata, 0, 3, values);
            return env->ExceptionCheck() ? nullptr : converted.release();
        }
        const auto result = avc::inspect(bytes.get(), size, lengthBytes);
        std::vector<uint8_t> expanded(result.annexBytes);
        bridge::ScopedWipe wipeExpanded(expanded.data(), expanded.size());
        avc::convert(bytes.get(), size, lengthBytes, expanded.data(), expanded.size());
        bridge::LocalRef<jbyteArray> converted(env, bridge::writeBytes(env, expanded.data(), expanded.size()));
        if (!converted.get() || env->ExceptionCheck()) return nullptr;
        const jint values[] = {result.flags, result.ppsId, result.nalCount};
        env->SetIntArrayRegion(metadata, 0, 3, values);
        return env->ExceptionCheck() ? nullptr : converted.release();
    });
}

const JNINativeMethod methods[] = {
    {"nativeParse", "([BI[I)[B", reinterpret_cast<void*>(parse)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    return bridge::registerMethods(vm, "com/sal7one/tvtestproject/receiver/media/NativeAvcParser", methods);
}
