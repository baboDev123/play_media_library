#include "thumbnail_extractor.h"
#include "avio_stream_context.h"
#include <cmath>

#ifndef PLAYME_STUB_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/display.h>
#include <libswscale/swscale.h>
}
#endif

#ifndef PLAYME_STUB_FFMPEG
static jobject extractFromFormatContext(
    JNIEnv* env,
    AVFormatContext* formatCtx,
    int64_t timeUs,
    int targetWidth,
    int targetHeight
) {
    if (!formatCtx) return nullptr;

    if (avformat_find_stream_info(formatCtx, nullptr) < 0) {
        return nullptr;
    }

    int videoStreamIdx = -1;
    int attachedPicIdx = -1;

    for (unsigned int i = 0; i < formatCtx->nb_streams; i++) {
        if (formatCtx->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            attachedPicIdx = static_cast<int>(i);
        } else if (formatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && videoStreamIdx < 0) {
            videoStreamIdx = static_cast<int>(i);
        }
    }

    // Cover Art Fallback: If audio file with embedded cover art or no video stream
    if (videoStreamIdx < 0 && attachedPicIdx >= 0) {
        AVPacket& pic = formatCtx->streams[attachedPicIdx]->attached_pic;
        if (pic.data && pic.size > 0) {
            jclass bmfClass = env->FindClass("android/graphics/BitmapFactory");
            jmethodID decodeByteMethod = env->GetStaticMethodID(
                bmfClass, "decodeByteArray", "([BII)Landroid/graphics/Bitmap;");
            jbyteArray bytes = env->NewByteArray(pic.size);
            env->SetByteArrayRegion(bytes, 0, pic.size, reinterpret_cast<jbyte*>(pic.data));
            jobject coverBitmap = env->CallStaticObjectMethod(bmfClass, decodeByteMethod, bytes, 0, pic.size);
            env->DeleteLocalRef(bytes);
            return coverBitmap;
        }
    }

    if (videoStreamIdx < 0) return nullptr;

    AVStream* stream = formatCtx->streams[videoStreamIdx];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) return nullptr;

    AVCodecContext* codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx) return nullptr;

    avcodec_parameters_to_context(codecCtx, stream->codecpar);
    codecCtx->thread_count = 2; // Fast decoding
    if (avcodec_open2(codecCtx, codec, nullptr) < 0) {
        avcodec_free_context(&codecCtx);
        return nullptr;
    }

    // Detect rotation
    int rotation = 0;
    AVDictionaryEntry* rotateEntry = av_dict_get(stream->metadata, "rotate", nullptr, 0);
    if (rotateEntry && rotateEntry->value) {
        rotation = atoi(rotateEntry->value);
    } else {
        int32_t* displayMatrix = reinterpret_cast<int32_t*>(
            av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, nullptr)
        );
        if (displayMatrix) {
            rotation = static_cast<int>(std::round(-av_display_rotation_get(displayMatrix)));
            if (rotation < 0) rotation += 360;
        }
    }

    // Seek to nearest keyframe
    int64_t seekPts = (timeUs * stream->time_base.den) / (1000000LL * stream->time_base.num);
    av_seek_frame(formatCtx, videoStreamIdx, seekPts, AVSEEK_FLAG_BACKWARD);

    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    bool frameDecoded = false;

    while (av_read_frame(formatCtx, packet) >= 0) {
        if (packet->stream_index == videoStreamIdx) {
            if (avcodec_send_packet(codecCtx, packet) == 0) {
                if (avcodec_receive_frame(codecCtx, frame) == 0) {
                    frameDecoded = true;
                    av_packet_unref(packet);
                    break;
                }
            }
        }
        av_packet_unref(packet);
    }

    jobject bitmap = nullptr;

    if (frameDecoded && frame->width > 0 && frame->height > 0) {
        int origW = frame->width;
        int origH = frame->height;

        // Aspect-ratio preserved scaling
        int outW = origW;
        int outH = origH;
        if (targetWidth > 0 && targetHeight > 0) {
            float aspect = static_cast<float>(origW) / origH;
            if (static_cast<float>(targetWidth) / targetHeight > aspect) {
                outH = targetHeight;
                outW = static_cast<int>(targetHeight * aspect);
            } else {
                outW = targetWidth;
                outH = static_cast<int>(targetWidth / aspect);
            }
        } else if (targetWidth > 0) {
            outW = targetWidth;
            outH = static_cast<int>(targetWidth * (static_cast<float>(origH) / origW));
        }

        jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
        jfieldID argb8888Field = env->GetStaticFieldID(
            bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jobject configObj = env->GetStaticObjectField(bitmapConfigClass, argb8888Field);

        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

        bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, outW, outH, configObj);

        if (bitmap) {
            void* pixels = nullptr;
            if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0 && pixels) {
                SwsContext* sws = sws_getContext(
                    origW, origH, static_cast<AVPixelFormat>(frame->format),
                    outW, outH, AV_PIX_FMT_RGBA,
                    SWS_BILINEAR, nullptr, nullptr, nullptr
                );

                if (sws) {
                    uint8_t* dstData[4] = { static_cast<uint8_t*>(pixels), nullptr, nullptr, nullptr };
                    int dstLinesize[4] = { outW * 4, 0, 0, 0 };

                    sws_scale(sws, frame->data, frame->linesize, 0, origH, dstData, dstLinesize);
                    sws_freeContext(sws);
                }
                AndroidBitmap_unlockPixels(env, bitmap);
            }

            // Apply rotation matrix if orientation is rotated
            if (rotation != 0) {
                jclass matrixClass = env->FindClass("android/graphics/Matrix");
                jmethodID matrixInit = env->GetMethodID(matrixClass, "<init>", "()V");
                jmethodID postRotateMethod = env->GetMethodID(matrixClass, "postRotate", "(F)Z");
                jobject matrixObj = env->NewObject(matrixClass, matrixInit);
                env->CallBooleanMethod(matrixObj, postRotateMethod, static_cast<jfloat>(rotation));

                jmethodID createRotatedBitmapMethod = env->GetStaticMethodID(
                    bitmapClass, "createBitmap",
                    "(Landroid/graphics/Bitmap;IIIILandroid/graphics/Matrix;Z)Landroid/graphics/Bitmap;");

                jobject rotatedBitmap = env->CallStaticObjectMethod(
                    bitmapClass, createRotatedBitmapMethod,
                    bitmap, 0, 0, outW, outH, matrixObj, JNI_TRUE
                );

                if (rotatedBitmap) {
                    env->DeleteLocalRef(bitmap);
                    bitmap = rotatedBitmap;
                }
                env->DeleteLocalRef(matrixObj);
            }
        }
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codecCtx);

    return bitmap;
}
#endif

jobject ThumbnailExtractorNative::extractFrameAtTime(
    JNIEnv* env,
    const std::string& filePath,
    int64_t timeUs,
    int targetWidth,
    int targetHeight
) {
#ifndef PLAYME_STUB_FFMPEG
    AVFormatContext* formatCtx = nullptr;
    if (avformat_open_input(&formatCtx, filePath.c_str(), nullptr, nullptr) != 0) {
        return nullptr;
    }
    jobject result = extractFromFormatContext(env, formatCtx, timeUs, targetWidth, targetHeight);
    avformat_close_input(&formatCtx);
    return result;
#else
    return nullptr;
#endif
}

jobject ThumbnailExtractorNative::extractFrameAtTimeStream(
    JNIEnv* env,
    jobject jInputStream,
    int64_t streamLength,
    int64_t timeUs,
    int targetWidth,
    int targetHeight
) {
#ifndef PLAYME_STUB_FFMPEG
    AVFormatContext* formatCtx = AvioStreamContext::openInputStream(env, jInputStream, streamLength);
    if (!formatCtx) return nullptr;
    jobject result = extractFromFormatContext(env, formatCtx, timeUs, targetWidth, targetHeight);
    AvioStreamContext::closeContext(formatCtx);
    return result;
#else
    return nullptr;
#endif
}
