// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <stdexcept>
#include <utility>

namespace fossmirror::jni {

template <typename Reference>
class LocalRef {
public:
    LocalRef(JNIEnv* env, Reference value) noexcept : env_(env), value_(value) {}
    ~LocalRef() { if (value_) env_->DeleteLocalRef(value_); }
    LocalRef(const LocalRef&) = delete;
    LocalRef& operator=(const LocalRef&) = delete;
    Reference get() const noexcept { return value_; }
    Reference release() noexcept { return std::exchange(value_, nullptr); }

private:
    JNIEnv* env_;
    Reference value_;
};

class ScopedWipe {
public:
    ScopedWipe(void* bytes, size_t size) noexcept : bytes_(bytes), size_(size) {}
    ~ScopedWipe() {
        auto* bytes = static_cast<volatile uint8_t*>(bytes_);
        for (size_t i = 0; i < size_; ++i) bytes[i] = 0;
    }
    ScopedWipe(const ScopedWipe&) = delete;
    ScopedWipe& operator=(const ScopedWipe&) = delete;

private:
    void* bytes_;
    size_t size_;
};

inline void throwJava(JNIEnv* env, const char* type, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    LocalRef<jclass> cls(env, env->FindClass(type));
    if (!cls.get()) return; // Preserve FindClass's exception; never ThrowNew with a null class.
    // JNI exception messages use Modified UTF-8. Keep native diagnostics bounded and ASCII.
    char safeMessage[256];
    size_t length = 0;
    if (message) {
        while (length + 1 < sizeof(safeMessage) && message[length]) {
            const auto ch = static_cast<unsigned char>(message[length]);
            safeMessage[length] = ch >= 32 && ch <= 126 ? static_cast<char>(ch) : '?';
            ++length;
        }
    }
    safeMessage[length] = '\0';
    env->ThrowNew(cls.get(), safeMessage);
}

template <typename Result, typename Operation>
Result boundary(JNIEnv* env, Result failure, Operation&& operation) noexcept {
    if (env->ExceptionCheck()) return failure;
    try {
        return std::forward<Operation>(operation)();
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Native allocation failed");
    } catch (const std::invalid_argument& error) {
        throwJava(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::logic_error& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unexpected native failure");
    }
    return failure;
}

template <typename Operation>
void boundary(JNIEnv* env, Operation&& operation) noexcept {
    boundary<int>(env, 0, [&] { std::forward<Operation>(operation)(); return 0; });
}

inline jsize arraySize(JNIEnv* env, jbyteArray bytes, size_t minimum, size_t maximum) {
    if (!bytes) throw std::invalid_argument("Native byte array is null");
    const auto size = env->GetArrayLength(bytes);
    if (size < 0 || static_cast<size_t>(size) < minimum || static_cast<size_t>(size) > maximum)
        throw std::invalid_argument("Invalid native byte array length");
    return size;
}

inline bool readBytes(JNIEnv* env, jbyteArray bytes, jsize size, uint8_t* destination) noexcept {
    if (env->ExceptionCheck()) return false;
    if (size > 0) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(destination));
    return !env->ExceptionCheck();
}

inline jbyteArray writeBytes(JNIEnv* env, const uint8_t* bytes, size_t size) {
    if (env->ExceptionCheck()) return nullptr;
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max()) || (size > 0 && !bytes))
        throw std::invalid_argument("Invalid native output buffer");
    LocalRef<jbyteArray> output(env, env->NewByteArray(static_cast<jsize>(size)));
    if (!output.get()) return nullptr;
    if (size > 0) env->SetByteArrayRegion(output.get(), 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(bytes));
    return env->ExceptionCheck() ? nullptr : output.release();
}

template <size_t Count>
jint registerMethods(JavaVM* vm, const char* className, const JNINativeMethod (&methods)[Count]) noexcept {
    JNIEnv* env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    return boundary<jint>(env, JNI_ERR, [&]() -> jint {
        LocalRef<jclass> cls(env, env->FindClass(className));
        if (!cls.get() || env->RegisterNatives(cls.get(), methods, static_cast<jint>(Count)) != JNI_OK) return JNI_ERR;
        return JNI_VERSION_1_6;
    });
}

}  // namespace fossmirror::jni
