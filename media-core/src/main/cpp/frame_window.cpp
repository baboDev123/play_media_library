// Draws decoded frames onto a Surface, for io.github.playme.media.decoder.PlayMeVideoRenderer.
//
// Frames go to the window as YV12, so 8-bit 4:2:0 video (nearly all of it) is a plain copy of
// its three planes with no colour conversion on the CPU; the display hardware turns YUV into
// RGB. Other pixel formats (10-bit, 4:2:2, NV12...) are converted to YV12 first. Everything
// here runs on the playback thread.

#include <android/data_space.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include "common.h"

extern "C" {
#include <libswscale/swscale.h>
}

namespace {

// HAL_PIXEL_FORMAT_YV12: Y plane, then Cr, then Cb; chroma rows aligned to 16 bytes.
constexpr int32_t kFormatYv12 = 0x32315659;

struct FrameWindow {
    jobject surface = nullptr;  // global reference to the Surface [window] belongs to
    ANativeWindow* window = nullptr;
    int width = 0;
    int height = 0;
    int32_t dataSpace = -1;
    SwsContext* converter = nullptr;
};

void detach(JNIEnv* env, FrameWindow* target) {
    if (target->window != nullptr) ANativeWindow_release(target->window);
    if (target->surface != nullptr) env->DeleteGlobalRef(target->surface);
    target->window = nullptr;
    target->surface = nullptr;
    target->width = 0;
    target->height = 0;
    target->dataSpace = -1;
}

int align16(int value) { return (value + 15) & ~15; }

void copyPlane(uint8_t* to, int toStride, const uint8_t* from, int fromStride, int width, int height) {
    for (int row = 0; row < height; row++) {
        memcpy(to + static_cast<ptrdiff_t>(row) * toStride, from + static_cast<ptrdiff_t>(row) * fromStride, width);
    }
}

// How the display should read the frame's YUV values.
int32_t dataSpaceOf(const AVFrame* frame) {
    if (frame->color_range == AVCOL_RANGE_JPEG || frame->format == AV_PIX_FMT_YUVJ420P) return ADATASPACE_JFIF;
    switch (frame->colorspace) {
        case AVCOL_SPC_BT709: return ADATASPACE_BT709;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return ADATASPACE_BT2020;
        case AVCOL_SPC_BT470BG: return ADATASPACE_BT601_625;
        case AVCOL_SPC_SMPTE170M: return ADATASPACE_BT601_525;
        default: return frame->height >= 720 ? ADATASPACE_BT709 : ADATASPACE_BT601_625;
    }
}

bool prepare(JNIEnv* env, FrameWindow* target, jobject surface, const AVFrame* frame, int width, int height) {
    if (target->surface == nullptr || !env->IsSameObject(target->surface, surface)) {
        detach(env, target);
        target->window = ANativeWindow_fromSurface(env, surface);
        if (target->window == nullptr) return false;
        target->surface = env->NewGlobalRef(surface);
    }
    if (width != target->width || height != target->height) {
        if (ANativeWindow_setBuffersGeometry(target->window, width, height, kFormatYv12) != 0) return false;
        target->width = width;
        target->height = height;
    }
    const int32_t dataSpace = dataSpaceOf(frame);
    if (dataSpace != target->dataSpace) {
        if (__builtin_available(android 28, *)) ANativeWindow_setBuffersDataSpace(target->window, dataSpace);
        target->dataSpace = dataSpace;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_FrameWindowNative_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new FrameWindow());
}

// Draws [frameHandle] onto [surface]. False when the surface can't take it right now.
JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_FrameWindowNative_render(
    JNIEnv* env, jclass, jlong handle, jobject surface, jlong frameHandle) {
    auto* target = reinterpret_cast<FrameWindow*>(handle);
    const auto* frame = reinterpret_cast<const AVFrame*>(frameHandle);
    // YV12 needs even dimensions; an odd last row or column is dropped.
    const int width = frame->width & ~1;
    const int height = frame->height & ~1;
    if (surface == nullptr || width <= 0 || height <= 0) return false;
    if (!prepare(env, target, surface, frame, width, height)) return false;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(target->window, &buffer, nullptr) != 0) return false;
    auto* y = static_cast<uint8_t*>(buffer.bits);
    const int yStride = buffer.stride;
    const int chromaStride = align16(yStride / 2);
    uint8_t* cr = y + static_cast<ptrdiff_t>(yStride) * buffer.height;
    uint8_t* cb = cr + static_cast<ptrdiff_t>(chromaStride) * (buffer.height / 2);

    bool drawn = true;
    if (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P) {
        copyPlane(y, yStride, frame->data[0], frame->linesize[0], width, height);
        copyPlane(cb, chromaStride, frame->data[1], frame->linesize[1], width / 2, height / 2);
        copyPlane(cr, chromaStride, frame->data[2], frame->linesize[2], width / 2, height / 2);
    } else {
        target->converter = sws_getCachedContext(
            target->converter, frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
            width, height, AV_PIX_FMT_YUV420P, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        if (target->converter == nullptr) {
            drawn = false;
        } else {
            uint8_t* planes[] = {y, cb, cr, nullptr};
            const int strides[] = {yStride, chromaStride, chromaStride, 0};
            sws_scale(target->converter, frame->data, frame->linesize, 0, frame->height, planes, strides);
        }
    }
    ANativeWindow_unlockAndPost(target->window);
    return drawn;
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_FrameWindowNative_release(JNIEnv* env, jclass, jlong handle) {
    auto* target = reinterpret_cast<FrameWindow*>(handle);
    detach(env, target);
    sws_freeContext(target->converter);
    delete target;
}

}  // extern "C"
