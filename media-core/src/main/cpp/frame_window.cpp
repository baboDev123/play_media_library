// Draws decoded frames onto a Surface, for io.github.playme.media.decoder.PlayMeVideoRenderer.
//
// Frames are drawn with OpenGL ES 2: the Y, U and V planes go up as three textures and a shader
// turns them into RGB with the frame's own colour matrix (BT.601, BT.709, BT.2020; limited or
// full range) and scales them to the Surface. 8-bit 4:2:0 frames, nearly all video, upload as
// they are; other pixel formats are converted to it first. Going through EGL also matters for
// switching decoders: destroying the EGL surface lets go of the Surface, so a MediaCodec decoder
// can take it over afterwards, which a CPU-locked window would not allow.
//
// Everything here runs on the playback thread, which holds the EGL context while drawing.

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include "common.h"

extern "C" {
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

namespace {

constexpr char kVertexShader[] = R"(
attribute vec2 position;
attribute vec2 texturePosition;
varying vec2 sample;
void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    sample = texturePosition;
}
)";

constexpr char kFragmentShader[] = R"(
precision mediump float;
varying vec2 sample;
uniform sampler2D lumaPlane;
uniform sampler2D bluePlane;
uniform sampler2D redPlane;
// The part of each texture row that is picture rather than padding.
uniform float lumaVisible;
uniform float chromaVisible;
uniform mat3 toRgb;
uniform vec3 offset;
void main() {
    vec2 luma = vec2(sample.x * lumaVisible, sample.y);
    vec2 chroma = vec2(sample.x * chromaVisible, sample.y);
    vec3 yuv = vec3(texture2D(lumaPlane, luma).r, texture2D(bluePlane, chroma).r, texture2D(redPlane, chroma).r) - offset;
    gl_FragColor = vec4(toRgb * yuv, 1.0);
}
)";

// Columns: what Y, Cb and Cr each add to R, G and B.
struct ColourMatrix {
    GLfloat toRgb[9];
    GLfloat offset[3];
};

constexpr ColourMatrix kBt601Limited = {{1.164f, 1.164f, 1.164f, 0.0f, -0.392f, 2.017f, 1.596f, -0.813f, 0.0f}, {16.0f / 255, 0.5f, 0.5f}};
constexpr ColourMatrix kBt709Limited = {{1.164f, 1.164f, 1.164f, 0.0f, -0.213f, 2.112f, 1.793f, -0.533f, 0.0f}, {16.0f / 255, 0.5f, 0.5f}};
constexpr ColourMatrix kBt2020Limited = {{1.164f, 1.164f, 1.164f, 0.0f, -0.188f, 2.142f, 1.678f, -0.650f, 0.0f}, {16.0f / 255, 0.5f, 0.5f}};
constexpr ColourMatrix kBt601Full = {{1.0f, 1.0f, 1.0f, 0.0f, -0.344f, 1.772f, 1.402f, -0.714f, 0.0f}, {0.0f, 0.5f, 0.5f}};
constexpr ColourMatrix kBt709Full = {{1.0f, 1.0f, 1.0f, 0.0f, -0.187f, 1.856f, 1.575f, -0.468f, 0.0f}, {0.0f, 0.5f, 0.5f}};

const ColourMatrix& matrixFor(const AVFrame* frame) {
    const bool full = frame->color_range == AVCOL_RANGE_JPEG || frame->format == AV_PIX_FMT_YUVJ420P;
    switch (frame->colorspace) {
        case AVCOL_SPC_BT709: return full ? kBt709Full : kBt709Limited;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return kBt2020Limited;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M: return full ? kBt601Full : kBt601Limited;
        default:
            // Unlabelled: HD video is BT.709, smaller video BT.601.
            if (frame->height >= 720) return full ? kBt709Full : kBt709Limited;
            return full ? kBt601Full : kBt601Limited;
    }
}

struct FrameWindow {
    jobject surface = nullptr;  // global reference to the Surface being drawn on
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    GLuint program = 0;
    GLuint textures[3] = {};
    // Allocated size of each texture, so same-sized frames only replace the pixels.
    int textureWidths[3] = {};
    int textureHeights[3] = {};
    GLint lumaVisible = -1;
    GLint chromaVisible = -1;
    GLint toRgb = -1;
    GLint offset = -1;
    // Frames in other pixel formats are converted into this one first.
    SwsContext* converter = nullptr;
    AVFrame* converted = nullptr;
};

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader didn't compile: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool setUpGl(FrameWindow* target) {
    GLuint vertex = compile(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragment = compile(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) return false;
    target->program = glCreateProgram();
    glAttachShader(target->program, vertex);
    glAttachShader(target->program, fragment);
    glBindAttribLocation(target->program, 0, "position");
    glBindAttribLocation(target->program, 1, "texturePosition");
    glLinkProgram(target->program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(target->program, GL_LINK_STATUS, &linked);
    if (!linked) return false;

    glUseProgram(target->program);
    glUniform1i(glGetUniformLocation(target->program, "lumaPlane"), 0);
    glUniform1i(glGetUniformLocation(target->program, "bluePlane"), 1);
    glUniform1i(glGetUniformLocation(target->program, "redPlane"), 2);
    target->lumaVisible = glGetUniformLocation(target->program, "lumaVisible");
    target->chromaVisible = glGetUniformLocation(target->program, "chromaVisible");
    target->toRgb = glGetUniformLocation(target->program, "toRgb");
    target->offset = glGetUniformLocation(target->program, "offset");

    glGenTextures(3, target->textures);
    for (GLuint texture : target->textures) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    return true;
}

bool setUpEgl(FrameWindow* target) {
    target->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (target->display == EGL_NO_DISPLAY || !eglInitialize(target->display, nullptr, nullptr)) return false;
    const EGLint configAttributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLint count = 0;
    if (!eglChooseConfig(target->display, configAttributes, &target->config, 1, &count) || count == 0) return false;
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    target->context = eglCreateContext(target->display, target->config, EGL_NO_CONTEXT, contextAttributes);
    return target->context != EGL_NO_CONTEXT;
}

// Lets go of the Surface, so another decoder can draw on it.
void detachSurface(JNIEnv* env, FrameWindow* target) {
    if (target->eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(target->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(target->display, target->eglSurface);
        target->eglSurface = EGL_NO_SURFACE;
    }
    if (target->window != nullptr) ANativeWindow_release(target->window);
    if (target->surface != nullptr) env->DeleteGlobalRef(target->surface);
    target->window = nullptr;
    target->surface = nullptr;
}

bool attachSurface(JNIEnv* env, FrameWindow* target, jobject surface) {
    if (target->surface != nullptr && env->IsSameObject(target->surface, surface)) {
        return eglMakeCurrent(target->display, target->eglSurface, target->eglSurface, target->context);
    }
    detachSurface(env, target);
    if (target->context == EGL_NO_CONTEXT && !setUpEgl(target)) return false;
    target->window = ANativeWindow_fromSurface(env, surface);
    if (target->window == nullptr) return false;
    target->surface = env->NewGlobalRef(surface);
    target->eglSurface = eglCreateWindowSurface(target->display, target->config, target->window, nullptr);
    if (target->eglSurface == EGL_NO_SURFACE) {
        LOGE("Can't draw on this Surface (EGL error 0x%x)", eglGetError());
        return false;
    }
    if (!eglMakeCurrent(target->display, target->eglSurface, target->eglSurface, target->context)) return false;
    return target->program != 0 || setUpGl(target);
}

// The frame as 8-bit 4:2:0, converting it when it's in another format.
const AVFrame* asYuv420(FrameWindow* target, const AVFrame* frame) {
    if (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P) return frame;
    AVFrame* converted = target->converted;
    if (converted == nullptr || converted->width != frame->width || converted->height != frame->height) {
        av_frame_free(&target->converted);
        converted = target->converted = av_frame_alloc();
        if (converted == nullptr) return nullptr;
        converted->format = AV_PIX_FMT_YUV420P;
        converted->width = frame->width;
        converted->height = frame->height;
        if (av_frame_get_buffer(converted, 0) < 0) {
            av_frame_free(&target->converted);
            return nullptr;
        }
    }
    target->converter = sws_getCachedContext(
        target->converter, frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
        frame->width, frame->height, AV_PIX_FMT_YUV420P, SWS_POINT, nullptr, nullptr, nullptr);
    if (target->converter == nullptr) return nullptr;
    sws_scale(target->converter, frame->data, frame->linesize, 0, frame->height, converted->data, converted->linesize);
    converted->colorspace = frame->colorspace;
    converted->color_range = frame->color_range;
    return converted;
}

void upload(FrameWindow* target, int unit, const uint8_t* plane, int stride, int rows) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, target->textures[unit]);
    // The whole stride goes up; the shader only samples the visible width.
    if (target->textureWidths[unit] != stride || target->textureHeights[unit] != rows) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, stride, rows, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, plane);
        target->textureWidths[unit] = stride;
        target->textureHeights[unit] = rows;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, stride, rows, GL_LUMINANCE, GL_UNSIGNED_BYTE, plane);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_playme_media_decoder_FrameWindowNative_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new FrameWindow());
}

// Draws [frameHandle] onto [surface], filling it. False when the Surface can't be drawn on.
JNIEXPORT jboolean JNICALL
Java_io_github_playme_media_decoder_FrameWindowNative_render(
    JNIEnv* env, jclass, jlong handle, jobject surface, jlong frameHandle) {
    auto* target = reinterpret_cast<FrameWindow*>(handle);
    const auto* decoded = reinterpret_cast<const AVFrame*>(frameHandle);
    if (surface == nullptr || decoded->width <= 0 || decoded->height <= 0) return false;
    if (!attachSurface(env, target, surface)) return false;
    const AVFrame* frame = asYuv420(target, decoded);
    if (frame == nullptr) return false;

    EGLint width = 0;
    EGLint height = 0;
    eglQuerySurface(target->display, target->eglSurface, EGL_WIDTH, &width);
    eglQuerySurface(target->display, target->eglSurface, EGL_HEIGHT, &height);
    glViewport(0, 0, width, height);
    glUseProgram(target->program);

    const int chromaRows = (frame->height + 1) / 2;
    upload(target, 0, frame->data[0], frame->linesize[0], frame->height);
    upload(target, 1, frame->data[1], frame->linesize[1], chromaRows);
    upload(target, 2, frame->data[2], frame->linesize[2], chromaRows);
    glUniform1f(target->lumaVisible, static_cast<GLfloat>(frame->width) / frame->linesize[0]);
    glUniform1f(target->chromaVisible, static_cast<GLfloat>((frame->width + 1) / 2) / frame->linesize[1]);
    const ColourMatrix& matrix = matrixFor(decoded);
    glUniformMatrix3fv(target->toRgb, 1, GL_FALSE, matrix.toRgb);
    glUniform3fv(target->offset, 1, matrix.offset);

    // A full-screen quad; texture rows run top to bottom.
    static const GLfloat positions[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    static const GLfloat texturePositions[] = {0, 1, 1, 1, 0, 0, 1, 0};
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, positions);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, texturePositions);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    return eglSwapBuffers(target->display, target->eglSurface);
}

JNIEXPORT void JNICALL
Java_io_github_playme_media_decoder_FrameWindowNative_release(JNIEnv* env, jclass, jlong handle) {
    auto* target = reinterpret_cast<FrameWindow*>(handle);
    if (target->display != EGL_NO_DISPLAY && target->eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(target->display, target->eglSurface, target->eglSurface, target->context);
        if (target->program != 0) {
            glDeleteTextures(3, target->textures);
            glDeleteProgram(target->program);
        }
    }
    detachSurface(env, target);
    if (target->context != EGL_NO_CONTEXT) eglDestroyContext(target->display, target->context);
    sws_freeContext(target->converter);
    av_frame_free(&target->converted);
    delete target;
}

}  // extern "C"
