cmake_minimum_required(VERSION 3.22.1)
project("playme_decoder" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(JNI_GRAPHICS_LIB jnigraphics)

# Source files
set(SOURCE_FILES
    playme_jni.cpp
    playme_video.cpp
    playme_audio.cpp
)

add_library(playme_decoder SHARED ${SOURCE_FILES})

# Locate prebuilt FFmpeg if present in ffmpeg/output or system
set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ffmpeg/output/${ANDROID_ABI}")

if(EXISTS "${FFMPEG_DIR}/include")
    include_directories(${FFMPEG_DIR}/include)
    link_directories(${FFMPEG_DIR}/lib)
    target_link_libraries(
        playme_decoder
        avcodec
        avformat
        avutil
        swresample
        swscale
        ${LOG_LIB}
        ${ANDROID_LIB}
        ${JNI_GRAPHICS_LIB}
    )
else()
    # Stub compilation support for build test
    add_definitions(-DPLAYME_STUB_FFMPEG)
    target_link_libraries(
        playme_decoder
        ${LOG_LIB}
        ${ANDROID_LIB}
        ${JNI_GRAPHICS_LIB}
    )
endif()

