cmake_minimum_required(VERSION 3.22.1)
project(playmedia LANGUAGES C CXX)

# FFmpeg and dav1d, built by ffmpeg/build.sh as static libraries.
set(DEPS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../ffmpeg/output/${ANDROID_ABI}")
if(NOT EXISTS "${DEPS}/lib/libavcodec.a")
    message(FATAL_ERROR "FFmpeg for ${ANDROID_ABI} is missing at ${DEPS}. Run ffmpeg/build.sh first.")
endif()

foreach(name avformat avcodec swscale swresample avutil dav1d)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${DEPS}/lib/lib${name}.a")
endforeach()

add_library(playmedia SHARED
    common.cpp
    video_decoder.cpp
    frame_window.cpp
    audio_decoder.cpp
    media_input.cpp
    media_probe.cpp
    demuxer.cpp)

target_include_directories(playmedia PRIVATE "${DEPS}/include")
target_compile_features(playmedia PRIVATE cxx_std_17)
target_compile_options(playmedia PRIVATE
    -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(playmedia PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1)
# FFmpeg's libraries depend on each other in this order.
target_link_libraries(playmedia PRIVATE
    avformat avcodec swscale swresample avutil dav1d
    android jnigraphics EGL GLESv2 log dl z m)
