#include "thumbnail_extractor.h"

jobject ThumbnailExtractorNative::extractFrameAtTime(
    JNIEnv* env, const std::string& filePath, int64_t timeUs, int targetWidth, int targetHeight) {
#ifndef PLAYME_STUB_FFMPEG
    AVFormatContext* formatCtx = nullptr;
    if (avformat_open_input(&formatCtx, filePath.c_str(), nullptr, nullptr) != 0) {
        return nullptr;
    }

    if (avformat_find_stream_info(formatCtx, nullptr) < 0) {
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    int videoStreamIdx = -1;
    for (unsigned int i = 0; i < formatCtx->nb_streams; i++) {
        if (formatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIdx = static_cast<int>(i);
            break;
        }
    }

    if (videoStreamIdx < 0) {
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    AVStream* stream = formatCtx->streams[videoStreamIdx];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    AVCodecContext* codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx) {
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    avcodec_parameters_to_context(codecCtx, stream->codecpar);
    codecCtx->thread_count = 2; // Fast thread decoding for thumbnail
    if (avcodec_open2(codecCtx, codec, nullptr) < 0) {
        avcodec_free_context(&codecCtx);
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    // Seek to keyframe closest to timestamp
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
        int outWidth = (targetWidth > 0) ? targetWidth : frame->width;
        int outHeight = (targetHeight > 0) ? targetHeight : frame->height;

        // Create Android Bitmap
        jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
        jfieldID argb8888Field = env->GetStaticFieldID(
            bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jobject configObj = env->GetStaticObjectField(bitmapConfigClass, argb8888Field);

        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

        bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, outWidth, outHeight, configObj);

        if (bitmap) {
            void* pixels = nullptr;
            if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0 && pixels) {
                SwsContext* sws = sws_getContext(
                    frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
                    outWidth, outHeight, AV_PIX_FMT_RGBA,
                    SWS_BILINEAR, nullptr, nullptr, nullptr
                );

                if (sws) {
                    uint8_t* dstData[4] = { static_cast<uint8_t*>(pixels), nullptr, nullptr, nullptr };
                    int dstLinesize[4] = { outWidth * 4, 0, 0, 0 };

                    sws_scale(sws, frame->data, frame->linesize, 0, frame->height, dstData, dstLinesize);
                    sws_freeContext(sws);
                }
                AndroidBitmap_unlockPixels(env, bitmap);
            }
        }
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codecCtx);
    avformat_close_input(&formatCtx);

    return bitmap;
#else
    return nullptr;
#endif
}

