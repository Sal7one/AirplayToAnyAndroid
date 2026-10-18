# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("fossmirror")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        fairplay_jni.cpp
        airplay_crypto/fairplay_session.cpp
        airplay_crypto/playfair/hand_garble.c
        airplay_crypto/playfair/modified_md5.c
        airplay_crypto/playfair/omg_hax.c
        airplay_crypto/playfair/playfair.c
        airplay_crypto/playfair/sap_hash.c)
target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_17)
set_source_files_properties(fairplay_jni.cpp PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
set(JNI_EXPORTS "${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map")
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,--version-script=${JNI_EXPORTS}")
set_property(TARGET ${CMAKE_PROJECT_NAME} APPEND PROPERTY LINK_DEPENDS "${JNI_EXPORTS}")

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE m)

add_subdirectory(audio_alac)

add_library(fossmirror_avc SHARED h264/avc_access_unit.cpp h264/avc_jni.cpp)
target_compile_features(fossmirror_avc PRIVATE cxx_std_17)
# Keep the hot parser optimized in debug APKs too; retain normal debug symbols.
target_compile_options(fossmirror_avc PRIVATE -O2 -Wall -Wextra -Werror)
target_link_options(fossmirror_avc PRIVATE "-Wl,--version-script=${JNI_EXPORTS}")
set_property(TARGET fossmirror_avc APPEND PROPERTY LINK_DEPENDS "${JNI_EXPORTS}")
