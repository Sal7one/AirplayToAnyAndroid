cmake_minimum_required(VERSION 3.22.1)
project(fossmirror_host_tests LANGUAGES C CXX)
enable_testing()
find_package(Threads REQUIRED)
find_package(JNI REQUIRED)
set(NATIVE "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp")
add_executable(fairplay_session_test
    fairplay_session_test.cpp
    "${NATIVE}/airplay_crypto/fairplay_session.cpp"
    "${NATIVE}/airplay_crypto/playfair/hand_garble.c"
    "${NATIVE}/airplay_crypto/playfair/modified_md5.c"
    "${NATIVE}/airplay_crypto/playfair/omg_hax.c"
    "${NATIVE}/airplay_crypto/playfair/playfair.c"
    "${NATIVE}/airplay_crypto/playfair/sap_hash.c")
target_include_directories(fairplay_session_test PRIVATE "${NATIVE}")
target_compile_features(fairplay_session_test PRIVATE cxx_std_17)
option(FOSSMIRROR_SANITIZE "Enable address sanitizer on the host test" OFF)
if(FOSSMIRROR_SANITIZE)
    target_compile_options(fairplay_session_test PRIVATE -fsanitize=address -fno-omit-frame-pointer)
    target_link_options(fairplay_session_test PRIVATE -fsanitize=address)
endif()
target_link_libraries(fairplay_session_test PRIVATE m)
add_test(NAME fairplay_session_test COMMAND fairplay_session_test)

add_executable(native_handles_test native_handles_test.cpp)
target_include_directories(native_handles_test PRIVATE "${NATIVE}")
target_compile_features(native_handles_test PRIVATE cxx_std_17)
target_link_libraries(native_handles_test PRIVATE Threads::Threads)
add_test(NAME native_handles_test COMMAND native_handles_test)

add_executable(jni_support_test jni_support_test.cpp)
target_include_directories(jni_support_test PRIVATE "${NATIVE}" ${JNI_INCLUDE_DIRS})
target_compile_features(jni_support_test PRIVATE cxx_std_17)
add_test(NAME jni_support_test COMMAND jni_support_test)

add_executable(avc_access_unit_test avc_access_unit_test.cpp "${NATIVE}/h264/avc_access_unit.cpp")
target_include_directories(avc_access_unit_test PRIVATE "${NATIVE}")
target_compile_features(avc_access_unit_test PRIVATE cxx_std_17)
target_compile_options(avc_access_unit_test PRIVATE -Wall -Wextra -Werror)
add_test(NAME avc_access_unit_test COMMAND avc_access_unit_test)

if(FOSSMIRROR_SANITIZE)
    foreach(target native_handles_test jni_support_test avc_access_unit_test)
        target_compile_options(${target} PRIVATE -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
        target_link_options(${target} PRIVATE -fsanitize=address,undefined)
    endforeach()
endif()
