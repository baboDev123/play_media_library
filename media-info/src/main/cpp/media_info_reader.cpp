#include "media_info_reader.h"
#include "avio_stream_context.h"
#include <cmath>

jobject MediaInfoReaderNative::parseMediaInfo(JNIEnv* env, const std::string& filePath) {
#ifndef PLAYME_STUB_FFMPEG
    AVFormatContext* formatCtx = nullptr;
    if (avformat_open_input(&formatCtx, filePath.c_str(), nullptr, nullptr) != 0) {
        LOGE("Failed to open file: %s", filePath.c_str());
        return nullptr;
    }

    if (avformat_find_stream_info(formatCtx, nullptr) < 0) {
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    jobject result = parseFromFormatContext(env, formatCtx, filePath);
    avformat_close_input(&formatCtx);
    return result;
#else
    return nullptr;
#endif
}

jobject MediaInfoReaderNative::parseMediaInfoStream(JNIEnv* env, jobject jInputStream, int64_t streamLength) {
#ifndef PLAYME_STUB_FFMPEG
    AVFormatContext* formatCtx = AvioStreamContext::openInputStream(env, jInputStream, streamLength);
    if (!formatCtx) {
        LOGE("Failed to open InputStream via custom AVIOContext");
        return nullptr;
    }

    if (avformat_find_stream_info(formatCtx, nullptr) < 0) {
        AvioStreamContext::closeContext(formatCtx);
        return nullptr;
    }

    jobject result = parseFromFormatContext(env, formatCtx, "network://stream");
    AvioStreamContext::closeContext(formatCtx);
    return result;
#else
    return nullptr;
#endif
}

#ifndef PLAYME_STUB_FFMPEG
jobject MediaInfoReaderNative::parseFromFormatContext(
    JNIEnv* env,
    AVFormatContext* formatCtx,
    const std::string& sourcePath
) {
    int64_t durationMs = (formatCtx->duration != AV_NOPTS_VALUE) ? (formatCtx->duration * 1000 / AV_TIME_BASE) : 0;
    int64_t bitRate = formatCtx->bit_rate;
    const char* formatName = formatCtx->iformat ? formatCtx->iformat->name : "unknown";

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListInit = env->GetMethodID(arrayListClass, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jobject videoList = env->NewObject(arrayListClass, arrayListInit);
    jobject audioList = env->NewObject(arrayListClass, arrayListInit);
    jobject subtitleList = env->NewObject(arrayListClass, arrayListInit);
    jobject chapterList = env->NewObject(arrayListClass, arrayListInit);
    jobject attachmentList = env->NewObject(arrayListClass, arrayListInit);

    jclass videoStreamClass = env->FindClass("io/github/playme/media/info/VideoStream");
    jmethodID videoStreamInit = env->GetMethodID(
        videoStreamClass, "<init>",
        "(ILjava/lang/String;IIJFIILio/github/playme/media/info/HdrType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"
    );

    jclass audioStreamClass = env->FindClass("io/github/playme/media/info/AudioStream");
    jmethodID audioStreamInit = env->GetMethodID(
        audioStreamClass, "<init>", "(ILjava/lang/String;IIJLjava/lang/String;Ljava/lang/String;Z)V");

    jclass subStreamClass = env->FindClass("io/github/playme/media/info/SubtitleStream");
    jmethodID subStreamInit = env->GetMethodID(
        subStreamClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V");

    jclass hdrTypeClass = env->FindClass("io/github/playme/media/info/HdrType");
    jfieldID hdrNoneField = env->GetStaticFieldID(hdrTypeClass, "NONE", "Lio/github/playme/media/info/HdrType;");
    jfieldID hdr10Field = env->GetStaticFieldID(hdrTypeClass, "HDR10", "Lio/github/playme/media/info/HdrType;");
    jfieldID hdrHlgField = env->GetStaticFieldID(hdrTypeClass, "HLG", "Lio/github/playme/media/info/HdrType;");

    jbyteArray coverArtByteArray = nullptr;

    for (unsigned int i = 0; i < formatCtx->nb_streams; i++) {
        AVStream* stream = formatCtx->streams[i];
        AVCodecParameters* params = stream->codecpar;

        // Check for attached picture (Cover art)
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) && coverArtByteArray == nullptr) {
            if (stream->attached_pic.size > 0 && stream->attached_pic.data) {
                coverArtByteArray = env->NewByteArray(stream->attached_pic.size);
                env->SetByteArrayRegion(
                    coverArtByteArray, 0, stream->attached_pic.size,
                    reinterpret_cast<jbyte*>(stream->attached_pic.data)
                );
            }
        }

        AVDictionaryEntry* langEntry = av_dict_get(stream->metadata, "language", nullptr, 0);
        jstring jLang = langEntry ? env->NewStringUTF(langEntry->value) : nullptr;

        AVDictionaryEntry* titleEntry = av_dict_get(stream->metadata, "title", nullptr, 0);
        jstring jTitle = titleEntry ? env->NewStringUTF(titleEntry->value) : nullptr;

        bool isDefault = (stream->disposition & AV_DISPOSITION_DEFAULT) != 0;
        bool isForced = (stream->disposition & AV_DISPOSITION_FORCED) != 0;

        const char* codecName = avcodec_get_name(params->codec_id);
        jstring jCodec = env->NewStringUTF(codecName);

        if (params->codec_type == AVMEDIA_TYPE_VIDEO && !(stream->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
            float fps = 0.0f;
            if (stream->avg_frame_rate.den > 0) {
                fps = static_cast<float>(stream->avg_frame_rate.num) / stream->avg_frame_rate.den;
            }

            // Rotation
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

            // HDR & Color specifics
            jobject hdrObj = env->GetStaticObjectField(hdrTypeClass, hdrNoneField);
            if (params->color_trc == AVCOL_TRC_SMPTE2084) {
                hdrObj = env->GetStaticObjectField(hdrTypeClass, hdr10Field);
            } else if (params->color_trc == AVCOL_TRC_ARIB_STD_B67) {
                hdrObj = env->GetStaticObjectField(hdrTypeClass, hdrHlgField);
            }

            int bitDepth = params->bits_per_raw_sample > 0 ? params->bits_per_raw_sample : 8;
            const char* csName = av_color_space_name(params->color_space);
            const char* trcName = av_color_transfer_name(params->color_trc);
            const char* primName = av_color_primaries_name(params->color_primaries);

            jstring jCs = csName ? env->NewStringUTF(csName) : nullptr;
            jstring jTrc = trcName ? env->NewStringUTF(trcName) : nullptr;
            jstring jPrim = primName ? env->NewStringUTF(primName) : nullptr;

            jobject vObj = env->NewObject(
                videoStreamClass, videoStreamInit,
                static_cast<jint>(i), jCodec, params->width, params->height,
                static_cast<jlong>(params->bit_rate), fps, rotation, bitDepth,
                hdrObj, jCs, jTrc, jPrim, isDefault ? JNI_TRUE : JNI_FALSE
            );

            env->CallBooleanMethod(videoList, arrayListAdd, vObj);
            env->DeleteLocalRef(vObj);
            if (jCs) env->DeleteLocalRef(jCs);
            if (jTrc) env->DeleteLocalRef(jTrc);
            if (jPrim) env->DeleteLocalRef(jPrim);

        } else if (params->codec_type == AVMEDIA_TYPE_AUDIO) {
            jobject aObj = env->NewObject(
                audioStreamClass, audioStreamInit,
                static_cast<jint>(i), jCodec, params->sample_rate, params->ch_layout.nb_channels,
                static_cast<jlong>(params->bit_rate), jLang, jTitle, isDefault ? JNI_TRUE : JNI_FALSE
            );
            env->CallBooleanMethod(audioList, arrayListAdd, aObj);
            env->DeleteLocalRef(aObj);

        } else if (params->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            jobject sObj = env->NewObject(
                subStreamClass, subStreamInit,
                static_cast<jint>(i), jCodec, jLang, jTitle,
                isDefault ? JNI_TRUE : JNI_FALSE, isForced ? JNI_TRUE : JNI_FALSE
            );
            env->CallBooleanMethod(subtitleList, arrayListAdd, sObj);
            env->DeleteLocalRef(sObj);

        } else if (params->codec_type == AVMEDIA_TYPE_ATTACHMENT) {
            // MKV Embedded Attachments (Fonts, images)
            jclass attachmentClass = env->FindClass("io/github/playme/media/info/MediaAttachment");
            jmethodID attachmentInit = env->GetMethodID(
                attachmentClass, "<init>", "(JLjava/lang/String;Ljava/lang/String;J[B)V");

            AVDictionaryEntry* filenameEntry = av_dict_get(stream->metadata, "filename", nullptr, 0);
            AVDictionaryEntry* mimeEntry = av_dict_get(stream->metadata, "mimetype", nullptr, 0);

            jstring jAttName = env->NewStringUTF(filenameEntry ? filenameEntry->value : "attachment");
            jstring jAttMime = env->NewStringUTF(mimeEntry ? mimeEntry->value : "application/octet-stream");

            jbyteArray jAttData = nullptr;
            if (params->extradata_size > 0 && params->extradata) {
                jAttData = env->NewByteArray(params->extradata_size);
                env->SetByteArrayRegion(jAttData, 0, params->extradata_size, reinterpret_cast<jbyte*>(params->extradata));
            }

            jobject attObj = env->NewObject(
                attachmentClass, attachmentInit,
                static_cast<jlong>(i), jAttName, jAttMime,
                static_cast<jlong>(params->extradata_size), jAttData
            );

            env->CallBooleanMethod(attachmentList, arrayListAdd, attObj);
            env->DeleteLocalRef(attObj);
            env->DeleteLocalRef(jAttName);
            env->DeleteLocalRef(jAttMime);
            if (jAttData) env->DeleteLocalRef(jAttData);
        }

        env->DeleteLocalRef(jCodec);
        if (jLang) env->DeleteLocalRef(jLang);
        if (jTitle) env->DeleteLocalRef(jTitle);
    }

    // Chapters
    jclass chapterClass = env->FindClass("io/github/playme/media/info/Chapter");
    jmethodID chapterInit = env->GetMethodID(chapterClass, "<init>", "(JJJLjava/lang/String;)V");

    for (unsigned int c = 0; c < formatCtx->nb_chapters; c++) {
        AVChapter* ch = formatCtx->chapters[c];
        int64_t startMs = ch->start * 1000 * ch->time_base.num / ch->time_base.den;
        int64_t endMs = ch->end * 1000 * ch->time_base.num / ch->time_base.den;

        AVDictionaryEntry* chTitle = av_dict_get(ch->metadata, "title", nullptr, 0);
        jstring jChTitle = chTitle ? env->NewStringUTF(chTitle->value) : nullptr;

        jobject chObj = env->NewObject(chapterClass, chapterInit, static_cast<jlong>(ch->id), startMs, endMs, jChTitle);
        env->CallBooleanMethod(chapterList, arrayListAdd, chObj);
        env->DeleteLocalRef(chObj);
        if (jChTitle) env->DeleteLocalRef(jChTitle);
    }

    // Build MediaInfo
    jclass mediaInfoClass = env->FindClass("io/github/playme/media/info/MediaInfo");
    jmethodID mediaInfoInit = env->GetMethodID(
        mediaInfoClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJLjava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;[B)V"
    );

    jstring jPath = env->NewStringUTF(sourcePath.c_str());
    jstring jFormat = env->NewStringUTF(formatName);

    jobject result = env->NewObject(
        mediaInfoClass, mediaInfoInit,
        jPath, jFormat, durationMs, bitRate,
        videoList, audioList, subtitleList, chapterList, attachmentList, coverArtByteArray
    );

    env->DeleteLocalRef(jPath);
    env->DeleteLocalRef(jFormat);
    env->DeleteLocalRef(videoList);
    env->DeleteLocalRef(audioList);
    env->DeleteLocalRef(subtitleList);
    env->DeleteLocalRef(chapterList);
    env->DeleteLocalRef(attachmentList);
    if (coverArtByteArray) env->DeleteLocalRef(coverArtByteArray);

    return result;
}
#endif
