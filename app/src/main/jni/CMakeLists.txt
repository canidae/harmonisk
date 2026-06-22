cmake_minimum_required(VERSION 3.18)
project("soundfont")

add_library(soundfont SHARED soundfont_jni.cpp)

target_include_directories(soundfont PRIVATE .)
target_link_libraries(soundfont log)

# 16 KB ELF alignment for Android 16+ page size compatibility
target_link_options(soundfont PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
