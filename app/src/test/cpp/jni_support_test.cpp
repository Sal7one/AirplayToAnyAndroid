#include "jni/jni_support.h"

#include <array>
#include <iostream>
#include <limits>
#include <string>

namespace bridge = fossmirror::jni;

namespace {

void check(bool condition) { if (!condition) throw std::runtime_error("JNI helper assertion failed"); }

// Fault injection for JNI error paths only. Real ART/CheckJNI is tested separately.
struct VmState : JNIEnv {
    JNIEnv& env;
    JNINativeInterface_ functions{};
    bool pending = false;
    bool classFailure = false;
    bool allocationFailure = false;
    bool copyFailure = false;
    bool registrationFailure = false;
    bool registrationThrows = false;
    int finds = 0;
    int throws = 0;
    int deletes = 0;
    int writes = 0;
    int registrations = 0;
    std::string type;
    std::string message;
    _jclass cls;
    _jbyteArray array;

    static VmState& state(JNIEnv* env) { return *static_cast<VmState*>(env); }
    VmState() : env(*this) {
        env.functions = &functions;
        functions.ExceptionCheck = [](JNIEnv* env) -> jboolean { return state(env).pending; };
        functions.FindClass = [](JNIEnv* env, const char* name) -> jclass {
            auto& vm = state(env); ++vm.finds;
            if (vm.classFailure) { vm.pending = true; return nullptr; }
            vm.type = name;
            return &vm.cls;
        };
        functions.ThrowNew = [](JNIEnv* env, jclass cls, const char* message) -> jint {
            auto& vm = state(env);
            check(cls == &vm.cls && !vm.pending);
            ++vm.throws; vm.pending = true; vm.message = message;
            return JNI_OK;
        };
        functions.DeleteLocalRef = [](JNIEnv* env, jobject) { ++state(env).deletes; };
        functions.NewByteArray = [](JNIEnv* env, jsize size) -> jbyteArray {
            auto& vm = state(env); check(size >= 0);
            if (vm.allocationFailure) { vm.pending = true; return nullptr; }
            return &vm.array;
        };
        functions.SetByteArrayRegion = [](JNIEnv* env, jbyteArray, jsize, jsize, const jbyte*) {
            auto& vm = state(env); ++vm.writes;
            if (vm.copyFailure) vm.pending = true;
        };
        functions.RegisterNatives = [](JNIEnv* env, jclass cls, const JNINativeMethod* methods, jint count) -> jint {
            auto& vm = state(env);
            check(cls == &vm.cls && !vm.pending && count == 1 && methods && methods[0].fnPtr);
            check(std::string(methods[0].name) == "nativeTest" && std::string(methods[0].signature) == "()V");
            ++vm.registrations;
            if (vm.registrationThrows) vm.pending = true;
            return vm.registrationFailure ? JNI_ERR : JNI_OK;
        };
    }
};

struct RegistrationVm : JavaVM {
    JNIInvokeInterface_ functions{};
    JNIEnv* environment;
    jint status = JNI_OK;
    int gets = 0;

    explicit RegistrationVm(JNIEnv* env) : environment(env) {
        JavaVM::functions = &functions;
        functions.GetEnv = [](JavaVM* vm, void** result, jint version) -> jint {
            auto& state = *static_cast<RegistrationVm*>(vm);
            check(version == JNI_VERSION_1_6);
            ++state.gets;
            *result = state.status == JNI_OK ? state.environment : nullptr;
            return state.status;
        };
    }
};

void pendingExceptionsArePreserved() {
    VmState vm;
    vm.pending = true;
    bool called = false;
    check(bridge::boundary<int>(&vm.env, -1, [&] { called = true; return 1; }) == -1);
    bridge::throwJava(&vm.env, "bad/class", "must not replace original exception");
    check(!called && vm.finds == 0 && vm.throws == 0);
    VmState missingClass;
    missingClass.classFailure = true;
    bridge::throwJava(&missingClass.env, "bad/class", "message");
    check(missingClass.pending && missingClass.throws == 0 && missingClass.deletes == 0);
}

void cppFailuresBecomeJavaExceptions() {
    const std::array<const char*, 5> expected = {"java/lang/OutOfMemoryError", "java/lang/IllegalArgumentException",
        "java/lang/IllegalStateException", "java/lang/IllegalStateException", "java/lang/IllegalStateException"};
    for (size_t kind = 0; kind < expected.size(); ++kind) {
        VmState vm;
        const auto result = bridge::boundary<int>(&vm.env, -1, [&]() -> int {
            if (kind == 0) throw std::bad_alloc();
            if (kind == 1) throw std::invalid_argument("invalid");
            if (kind == 2) throw std::logic_error("closed");
            if (kind == 3) throw std::runtime_error("unexpected");
            throw 7;
        });
        check(result == -1 && vm.pending && vm.type == expected[kind] && vm.throws == 1 && vm.deletes == 1);
    }
    VmState vm;
    const std::string unsafe = std::string(400, static_cast<char>(0xff));
    bridge::throwJava(&vm.env, "java/lang/IllegalArgumentException", unsafe.c_str());
    check(vm.message == std::string(255, '?'));
}

void outputAllocationAndCopyFailuresDoNotLeakReferences() {
    const uint8_t data[] = {1, 2, 3};
    VmState allocation;
    allocation.allocationFailure = true;
    check(bridge::writeBytes(&allocation.env, data, sizeof(data)) == nullptr);
    check(allocation.pending && allocation.writes == 0 && allocation.deletes == 0);
    VmState copy;
    copy.copyFailure = true;
    check(bridge::writeBytes(&copy.env, data, sizeof(data)) == nullptr);
    check(copy.pending && copy.deletes == 1);
    VmState success;
    check(bridge::writeBytes(&success.env, data, sizeof(data)) == &success.array);
    check(!success.pending && success.deletes == 0);
    VmState overflow;
    check(bridge::boundary<jbyteArray>(&overflow.env, nullptr, [&] {
        return bridge::writeBytes(&overflow.env, data, static_cast<size_t>(std::numeric_limits<jsize>::max()) + 1);
    }) == nullptr);
    check(overflow.type == "java/lang/IllegalArgumentException");
}

void secretsAreWipedOnSuccessAndFailure() {
    std::array<uint8_t, 16> secret{};
    secret.fill(123);
    { bridge::ScopedWipe wipe(secret.data(), secret.size()); }
    for (auto byte : secret) check(byte == 0);
    secret.fill(123);
    try {
        bridge::ScopedWipe wipe(secret.data(), secret.size());
        throw std::bad_alloc();
    } catch (const std::bad_alloc&) { }
    for (auto byte : secret) check(byte == 0);
}

void JNICALL testNative(JNIEnv*, jobject) {}

void registrationChecksVmLookupAndCleansClassReferences() {
    char name[] = "nativeTest";
    char signature[] = "()V";
    const JNINativeMethod methods[] = {{name, signature, reinterpret_cast<void*>(testNative)}};
    constexpr const char* className = "test/NativeBoundary";
    check(bridge::registerMethods(nullptr, className, methods) == JNI_ERR);
    {
        VmState env;
        RegistrationVm vm(&env.env);
        vm.status = JNI_EDETACHED;
        check(bridge::registerMethods(&vm, className, methods) == JNI_ERR);
        check(vm.gets == 1 && env.finds == 0 && env.registrations == 0);
        vm.status = JNI_OK;
        vm.environment = nullptr;
        check(bridge::registerMethods(&vm, className, methods) == JNI_ERR);
        check(env.finds == 0 && env.registrations == 0);
    }
    {
        VmState env;
        env.pending = true;
        RegistrationVm vm(&env.env);
        check(bridge::registerMethods(&vm, className, methods) == JNI_ERR);
        check(env.pending && env.finds == 0 && env.throws == 0);
    }
    {
        VmState env;
        env.classFailure = true;
        RegistrationVm vm(&env.env);
        check(bridge::registerMethods(&vm, className, methods) == JNI_ERR);
        check(env.pending && env.registrations == 0 && env.deletes == 0 && env.throws == 0);
    }
    for (const bool pending : {false, true}) {
        VmState env;
        env.registrationFailure = true;
        env.registrationThrows = pending;
        RegistrationVm vm(&env.env);
        check(bridge::registerMethods(&vm, className, methods) == JNI_ERR);
        check(env.pending == pending && env.registrations == 1 && env.deletes == 1 && env.throws == 0);
    }
    {
        VmState env;
        RegistrationVm vm(&env.env);
        check(bridge::registerMethods(&vm, className, methods) == JNI_VERSION_1_6);
        check(!env.pending && env.type == className && env.registrations == 1 && env.deletes == 1);
    }
}

}  // namespace

int main() {
    pendingExceptionsArePreserved();
    cppFailuresBecomeJavaExceptions();
    outputAllocationAndCopyFailuresDoNotLeakReferences();
    secretsAreWipedOnSuccessAndFailure();
    registrationChecksVmLookupAndCleansClassReferences();
    std::cout << "JNI registration, exceptions, allocation/copy failure, reference and secret cleanup checks passed\n";
}
