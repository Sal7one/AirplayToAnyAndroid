cmake_minimum_required(VERSION 3.22.1)
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(fossmirror_alac_host LANGUAGES C CXX)
    enable_testing()
endif()

set(ALAC_SOURCES
    bounded_alac.cpp
    apple/ALACDecoder.cpp
    apple/ALACBitUtilities.c
    apple/EndianPortable.c
    apple/ag_dec.c
    apple/dp_dec.c
    apple/matrix_dec.c)

if(ANDROID)
    add_library(fossmirror_alac SHARED ${ALAC_SOURCES} alac_jni.cpp)
    set_source_files_properties(alac_jni.cpp PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
    set(JNI_EXPORTS "${CMAKE_CURRENT_SOURCE_DIR}/../jni/exports.map")
    target_link_options(fossmirror_alac PRIVATE "-Wl,--version-script=${JNI_EXPORTS}")
    set_property(TARGET fossmirror_alac APPEND PROPERTY LINK_DEPENDS "${JNI_EXPORTS}")
else()
    add_library(fossmirror_alac STATIC ${ALAC_SOURCES})
endif()
target_compile_features(fossmirror_alac PRIVATE cxx_std_17)
set_target_properties(fossmirror_alac PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED YES)
# Apple's predictor arithmetic intentionally wraps signed 32-bit accumulators.
target_compile_options(fossmirror_alac PRIVATE -fwrapv)

if(NOT ANDROID)
    option(ALAC_SANITIZERS "Enable address and undefined-behavior sanitizers" ON)
    add_executable(alac_host_test tests/alac_host_test.cpp)
    target_compile_features(alac_host_test PRIVATE cxx_std_17)
    target_link_libraries(alac_host_test PRIVATE fossmirror_alac)
    if(ALAC_SANITIZERS)
        foreach(target fossmirror_alac alac_host_test)
            target_compile_options(${target} PRIVATE -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
        endforeach()
        target_link_options(alac_host_test PRIVATE -fsanitize=address,undefined)
    endif()
    add_test(NAME alac_host_test COMMAND alac_host_test
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../androidTest/assets/alac")
endif()
