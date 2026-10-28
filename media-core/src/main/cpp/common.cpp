#include "common.h"

#include <cstdarg>
#include <cstring>

namespace playmedia {
namespace {

JavaVM* gVm = nullptr;

struct MimeCodec {
    const char* mime;
    AVCodecID codec;
};

// Media3's MIME types (androidx.media3.common.MimeTypes) and the FFmpeg codec decoding each.
constexpr MimeCodec kVideo[] = {
    {"video/avc", AV_CODEC_ID_H264},
    {"video/hevc", AV_CODEC_ID_HEVC},
    {"video/x-vnd.on2.vp8", AV_CODEC_ID_VP8},
    {"video/x-vnd.on2.vp9", AV_CODEC_ID_VP9},
    {"video/av01", AV_CODEC_ID_AV1},
    {"video/mpeg", AV_CODEC_ID_MPEG1VIDEO},
    {"video/mpeg2", AV_CODEC_ID_MPEG2VIDEO},
    {"video/mp4v-es", AV_CODEC_ID_MPEG4},
    {"video/divx", AV_CODEC_ID_MPEG4},
    {"video/3gpp", AV_CODEC_ID_H263},
    {"video/wvc1", AV_CODEC_ID_VC1},
    {"video/mjpeg", AV_CODEC_ID_MJPEG},
};

constexpr MimeCodec kAudio[] = {
    {"audio/ac3", AV_CODEC_ID_AC3},
    {"audio/eac3", AV_CODEC_ID_EAC3},
    {"audio/eac3-joc", AV_CODEC_ID_EAC3},
    {"audio/vnd.dts", AV_CODEC_ID_DTS},
    {"audio/vnd.dts.hd", AV_CODEC_ID_DTS},
    {"audio/true-hd", AV_CODEC_ID_TRUEHD},
    {"audio/flac", AV_CODEC_ID_FLAC},
    {"audio/vorbis", AV_CODEC_ID_VORBIS},
    {"audio/opus", AV_CODEC_ID_OPUS},
    {"audio/alac", AV_CODEC_ID_ALAC},
    {"audio/mp4a-latm", AV_CODEC_ID_AAC},
    {"audio/mpeg", AV_CODEC_ID_MP3},
    {"audio/mpeg-L1", AV_CODEC_ID_MP1},
    {"audio/mpeg-L2", AV_CODEC_ID_MP2},
    {"audio/g711-alaw", AV_CODEC_ID_PCM_ALAW},
    {"audio/g711-mlaw", AV_CODEC_ID_PCM_MULAW},
    {"audio/3gpp", AV_CODEC_ID_AMR_NB},
    {"audio/amr-wb", AV_CODEC_ID_AMR_WB},
};

template <size_t N>
const AVCodec* find(const MimeCodec (&table)[N], const char* mimeType) {
    if (mimeType == nullptr) return nullptr;
    for (const MimeCodec& entry : table) {
        if (strcmp(entry.mime, mimeType) != 0) continue;
        // AV1 is decoded by dav1d: FFmpeg's own AV1 decoder only drives hardware.
        if (entry.codec == AV_CODEC_ID_AV1) return avcodec_find_decoder_by_name("libdav1d");
        return avcodec_find_decoder(entry.codec);
    }
    return nullptr;
}

// FFmpeg's own messages go to logcat, so decoding problems in the field can be diagnosed.
void logToLogcat(void*, int level, const char* format, va_list arguments) {
    if (level > av_log_get_level()) return;
    const int priority = level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : level <= AV_LOG_WARNING ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_vprint(priority, "PlayMedia/FFmpeg", format, arguments);
}

}  // namespace

const AVCodec* videoDecoderFor(const char* mimeType) { return find(kVideo, mimeType); }

const AVCodec* audioDecoderFor(const char* mimeType) { return find(kAudio, mimeType); }

std::string describeError(int error) {
    char text[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(error, text, sizeof(text));
    return text;
}

JNIEnv* currentEnv() {
    JNIEnv* env = nullptr;
    if (gVm == nullptr || gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}

uint8_t* copyWithPadding(JNIEnv* env, jbyteArray bytes, int* size) {
    *size = 0;
    if (bytes == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0) return nullptr;
    auto* copy = static_cast<uint8_t*>(av_mallocz(length + AV_INPUT_BUFFER_PADDING_SIZE));
    if (copy == nullptr) return nullptr;
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(copy));
    *size = length;
    return copy;
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

}  // namespace playmedia

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    playmedia::gVm = vm;
    av_log_set_level(AV_LOG_WARNING);
    av_log_set_callback(playmedia::logToLogcat);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_playme_media_core_PlayMediaNative_ffmpegVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(av_version_info());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_core_PlayMediaNative_supportsVideo(JNIEnv* env, jclass, jstring mimeType) {
    const char* mime = env->GetStringUTFChars(mimeType, nullptr);
    const bool supported = playmedia::videoDecoderFor(mime) != nullptr;
    env->ReleaseStringUTFChars(mimeType, mime);
    return supported;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_core_PlayMediaNative_supportsAudio(JNIEnv* env, jclass, jstring mimeType) {
    const char* mime = env->GetStringUTFChars(mimeType, nullptr);
    const bool supported = playmedia::audioDecoderFor(mime) != nullptr;
    env->ReleaseStringUTFChars(mimeType, mime);
    return supported;
}
