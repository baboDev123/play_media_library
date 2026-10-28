#!/usr/bin/env bash
# Builds the native media engine's dependencies for every Android ABI:
#   dav1d (BSD-2, AV1) and FFmpeg (LGPL-2.1+, decoders and demuxers only),
# both as static, position-independent libraries under ffmpeg/output/<abi>.
#
# FFmpeg is configured without --enable-gpl / --enable-nonfree, so the result
# stays LGPL. The sources and build flags used are recorded in output/BUILD_INFO.
#
# Needs: Android NDK, curl, python3. Meson, Ninja and pkgconf are installed into
# a private virtualenv (ffmpeg/.tools) on first run.
set -euo pipefail

FFMPEG_VERSION="7.1.1"
DAV1D_VERSION="1.5.1"
MIN_API=23
ABIS=(${ABIS:-arm64-v8a armeabi-v7a x86_64 x86})

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCES="$ROOT/sources"
WORK="$ROOT/build"
OUTPUT="$ROOT/output"
TOOLS="$ROOT/.tools"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64"; JOBS="$(sysctl -n hw.ncpu)" ;;
    Linux) HOST_TAG="linux-x86_64"; JOBS="$(nproc)" ;;
    *) echo "Unsupported host" >&2; exit 1 ;;
esac
LLVM="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$LLVM" ] || { echo "NDK not found at $NDK" >&2; exit 1; }

# Build tools, kept out of the system Python.
if [ ! -x "$TOOLS/bin/meson" ]; then
    python3 -m venv "$TOOLS"
    "$TOOLS/bin/pip" install --quiet meson ninja pkgconf
fi
export PATH="$TOOLS/bin:$PATH"

fetch() { # url, directory it unpacks to
    if [ ! -d "$SOURCES/$2" ]; then
        mkdir -p "$SOURCES"
        curl -fsSL --retry 3 "$1" -o "$SOURCES/$2.tar.xz"
        tar -xf "$SOURCES/$2.tar.xz" -C "$SOURCES"
    fi
}
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION"
fetch "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" "dav1d-$DAV1D_VERSION"

VIDEO_DECODERS="h264 hevc vp8 vp9 libdav1d mpeg1video mpeg2video mpeg4 h263 vc1 wmv3 mjpeg"
AUDIO_DECODERS="ac3 eac3 dca truehd mlp flac vorbis opus alac aac aac_latm mp1 mp2 mp3 pcm_alaw pcm_mulaw amrnb amrwb"
PARSERS="h264 hevc vp8 vp9 av1 mpeg4video mpegvideo h263 vc1 mjpeg ac3 dca mlp flac vorbis opus aac aac_latm mpegaudio"
DEMUXERS="matroska mov avi flv asf mpegts mpegps m4v h264 hevc wav mp3 aac ac3 eac3 dts truehd flac ogg amr"

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a)   TRIPLE=aarch64-linux-android;     ARCH=aarch64; CPU=armv8-a; FAMILY=aarch64; ASM=true;  FLAGS="" ;;
        armeabi-v7a) TRIPLE=armv7a-linux-androideabi;  ARCH=arm;     CPU=armv7-a; FAMILY=arm;     ASM=true;  FLAGS="-mfpu=neon -mfloat-abi=softfp" ;;
        x86_64)      TRIPLE=x86_64-linux-android;      ARCH=x86_64;  CPU=x86-64;  FAMILY=x86_64;  ASM=false; FLAGS="" ;;
        x86)         TRIPLE=i686-linux-android;        ARCH=x86;     CPU=i686;    FAMILY=x86;     ASM=false; FLAGS="" ;;
        *) echo "Unknown ABI $ABI" >&2; exit 1 ;;
    esac
    CC="$LLVM/bin/$TRIPLE$MIN_API-clang"
    CXX="$LLVM/bin/$TRIPLE$MIN_API-clang++"
    PREFIX="$OUTPUT/$ABI"
    CFLAGS="-O3 -fPIC -ffunction-sections -fdata-sections $FLAGS"
    rm -rf "$PREFIX" "$WORK/$ABI"
    mkdir -p "$WORK/$ABI"

    echo "==> dav1d $DAV1D_VERSION for $ABI"
    cat > "$WORK/$ABI/cross.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$LLVM/bin/llvm-ar'
strip = '$LLVM/bin/llvm-strip'
[built-in options]
c_args = [$(printf "'%s'," $CFLAGS)]
[host_machine]
system = 'android'
cpu_family = '$FAMILY'
cpu = '$CPU'
endian = 'little'
EOF
    meson setup "$WORK/$ABI/dav1d" "$SOURCES/dav1d-$DAV1D_VERSION" \
        --cross-file "$WORK/$ABI/cross.txt" --prefix "$PREFIX" --libdir lib \
        --buildtype release --default-library static \
        -Denable_tools=false -Denable_tests=false -Denable_asm=$ASM >/dev/null
    ninja -C "$WORK/$ABI/dav1d" install >/dev/null

    echo "==> FFmpeg $FFMPEG_VERSION for $ABI"
    ASM_FLAGS=()
    case "$ABI" in
        armeabi-v7a) ASM_FLAGS=(--enable-neon) ;;
        x86_64) ASM_FLAGS=(--disable-x86asm) ;;
        x86) ASM_FLAGS=(--disable-asm) ;; # 32-bit x86 asm needs text relocations
    esac
    CONFIG=(
        --prefix="$PREFIX" --target-os=android --arch="$ARCH" --cpu="$CPU" --enable-cross-compile
        --cc="$CC" --cxx="$CXX" --ar="$LLVM/bin/llvm-ar" --nm="$LLVM/bin/llvm-nm"
        --ranlib="$LLVM/bin/llvm-ranlib" --strip="$LLVM/bin/llvm-strip"
        --pkg-config=pkgconf --pkg-config-flags=--static
        --extra-cflags="$CFLAGS" --extra-ldflags="-L$PREFIX/lib"
        --enable-static --disable-shared --enable-pic --disable-debug
        --disable-programs --disable-doc --disable-network --disable-autodetect --enable-zlib
        --disable-everything --disable-avdevice --disable-avfilter --disable-postproc
        --enable-avcodec --enable-avformat --enable-swresample --enable-swscale
        --enable-libdav1d --enable-protocol=file
        ${ASM_FLAGS[@]+"${ASM_FLAGS[@]}"}
    )
    for d in $VIDEO_DECODERS $AUDIO_DECODERS; do CONFIG+=(--enable-decoder="$d"); done
    for p in $PARSERS; do CONFIG+=(--enable-parser="$p"); done
    for m in $DEMUXERS; do CONFIG+=(--enable-demuxer="$m"); done

    mkdir -p "$WORK/$ABI/ffmpeg"
    (
        cd "$WORK/$ABI/ffmpeg"
        PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" "$SOURCES/ffmpeg-$FFMPEG_VERSION/configure" "${CONFIG[@]}" >/dev/null
        if grep -q "CONFIG_GPL 1\|CONFIG_NONFREE 1" config.h; then
            echo "FFmpeg was configured as GPL or nonfree; refusing to continue" >&2
            exit 1
        fi
        make -j"$JOBS" >/dev/null
        make install >/dev/null
    )
done

{
    echo "FFmpeg $FFMPEG_VERSION (LGPL-2.1-or-later): https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    echo "dav1d $DAV1D_VERSION (BSD-2-Clause): https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/"
    echo "Built by ffmpeg/build.sh with NDK $(basename "$NDK"), min API $MIN_API, ABIs: ${ABIS[*]}"
} > "$OUTPUT/BUILD_INFO"
echo "Done: $OUTPUT"
