#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# PlayMedia Engine: FFmpeg LGPL Build Script for Android
# Strictly LGPL v2.1/v3.0 Compliant (No GPL, No Non-free code)
# ==============================================================================

FFMPEG_VERSION="7.1"
FFMPEG_SRC_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCES_DIR="${BASE_DIR}/sources"
OUTPUT_DIR="${BASE_DIR}/output"

ANDROID_HOME="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
NDK_VERSION="${ANDROID_NDK_VERSION:-28.2.13676358}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/${NDK_VERSION}}"

ANDROID_API=23
ANDROID_ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "${HOST_OS}" in
    darwin*) HOST_TAG="darwin-x86_64" ;;
    linux*)  HOST_TAG="linux-x86_64" ;;
    *) echo "Unsupported Host OS: ${HOST_OS}"; exit 1 ;;
esac

TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}"

if [ ! -d "${TOOLCHAIN}" ]; then
    echo "Error: Toolchain directory not found at ${TOOLCHAIN}"
    exit 1
fi

mkdir -p "${SOURCES_DIR}"
mkdir -p "${OUTPUT_DIR}"

# 1. Download FFmpeg source if not already present
FFMPEG_DIR="${SOURCES_DIR}/ffmpeg-${FFMPEG_VERSION}"
if [ ! -d "${FFMPEG_DIR}" ]; then
    echo "Downloading FFmpeg ${FFMPEG_VERSION}..."
    curl -L "${FFMPEG_SRC_URL}" -o "${SOURCES_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.xz"
    tar -xf "${SOURCES_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.xz" -C "${SOURCES_DIR}"
fi

# 2. Build for each ABI
for ABI in "${ANDROID_ABIS[@]}"; do
    echo "=========================================================="
    echo "Building FFmpeg (LGPL) for ABI: ${ABI}"
    echo "=========================================================="

    BUILD_DIR="${BASE_DIR}/build/${ABI}"
    PREFIX="${OUTPUT_DIR}/${ABI}"
    mkdir -p "${BUILD_DIR}"
    cd "${BUILD_DIR}"

    case "${ABI}" in
        arm64-v8a)
            ARCH="aarch64"
            TARGET="aarch64-linux-android"
            CC="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang"
            CXX="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang++"
            EXTRA_CFLAGS="-march=armv8-a"
            ;;
        armeabi-v7a)
            ARCH="arm"
            TARGET="armv7a-linux-androideabi"
            CC="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang"
            CXX="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang++"
            EXTRA_CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"
            ;;
        x86)
            ARCH="x86"
            TARGET="i686-linux-android"
            CC="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang"
            CXX="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang++"
            EXTRA_CFLAGS="-march=i686 -mtune=intel -mssse3 -mfpmath=sse -m32"
            ;;
        x86_64)
            ARCH="x86_64"
            TARGET="x86_64-linux-android"
            CC="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang"
            CXX="${TOOLCHAIN}/bin/${TARGET}${ANDROID_API}-clang++"
            EXTRA_CFLAGS="-march=x86-64 -msse4.2 -mpopcnt -m64"
            ;;
    esac

    AR="${TOOLCHAIN}/bin/llvm-ar"
    AS="${CC}"
    RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    STRIP="${TOOLCHAIN}/bin/llvm-strip"

    # Configure strictly under LGPL:
    # --disable-gpl
    # --disable-nonfree
    # --enable-version3 (LGPLv3)
    "${FFMPEG_DIR}/configure" \
        --prefix="${PREFIX}" \
        --enable-cross-compile \
        --target-os=android \
        --arch="${ARCH}" \
        --cc="${CC}" \
        --cxx="${CXX}" \
        --ar="${AR}" \
        --as="${AS}" \
        --ranlib="${RANLIB}" \
        --strip="${STRIP}" \
        --sysroot="${TOOLCHAIN}/sysroot" \
        --extra-cflags="-O3 -fPIC -DANDROID -Wno-deprecated-declarations ${EXTRA_CFLAGS}" \
        --extra-ldflags="-Wl,-Bsymbolic" \
        --enable-shared \
        --disable-static \
        --disable-gpl \
        --disable-nonfree \
        --enable-version3 \
        --disable-programs \
        --disable-doc \
        --disable-avdevice \
        --disable-postproc \
        --disable-encoders \
        --disable-muxers \
        --enable-small \
        --enable-pic \
        --enable-jni \
        --enable-mediacodec \
        --enable-decoder=ac3 \
        --enable-decoder=eac3 \
        --enable-decoder=dca \
        --enable-decoder=truehd \
        --enable-decoder=flac \
        --enable-decoder=vorbis \
        --enable-decoder=opus \
        --enable-decoder=alac \
        --enable-decoder=aac \
        --enable-decoder=mp3 \
        --enable-decoder=pcm_s16le \
        --enable-decoder=pcm_s24le \
        --enable-decoder=h264 \
        --enable-decoder=hevc \
        --enable-decoder=vp8 \
        --enable-decoder=vp9 \
        --enable-decoder=av1 \
        --enable-decoder=mpeg2video \
        --enable-demuxer=matroska \
        --enable-demuxer=mov \
        --enable-demuxer=mp4 \
        --enable-demuxer=avi \
        --enable-demuxer=flv \
        --enable-demuxer=aac \
        --enable-demuxer=ac3 \
        --enable-demuxer=eac3 \
        --enable-demuxer=dts \
        --enable-demuxer=flac \
        --enable-demuxer=ogg \
        --enable-demuxer=wav \
        --enable-protocol=file \
        --enable-protocol=pipe \
        --enable-protocol=http \
        --enable-protocol=https

    make -j$(sysctl -n hw.ncpu 2>/dev/null || nproc || echo 4)
    make install
done

echo "FFmpeg LGPL build completed successfully. Outputs installed to: ${OUTPUT_DIR}"
