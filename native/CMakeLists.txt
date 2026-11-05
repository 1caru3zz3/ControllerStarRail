cmake_minimum_required(VERSION 3.22.1)
project(uinput_x360 C)

add_executable(uinput_x360 uinput_x360.c)

target_compile_options(uinput_x360 PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_options(uinput_x360 PRIVATE -static)

# The APK reads the binary from assets/bin/<abi>/uinput_x360.
# scripts/build-native.sh builds all supported ABIs in one go; this file is
# provided for IDE / standalone CMake users (pass -DCMAKE_ANDROID_NDK=<ndk>).
