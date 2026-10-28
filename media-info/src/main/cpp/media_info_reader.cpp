#include "media_info_reader.h"

jobject MediaInfoReaderNative::parseMediaInfo(JNIEnv* env, const std::string& filePath) {
#ifndef PLAYME_STUB_FFMPEG
    AVFormatContext* formatCtx = nullptr;
    if (avformat_open_input(&formatCtx, filePath.c_str(), nullptr, nullptr) != 0) {
        LOGE("Failed to open file: %s", filePath.c_str());
        return nullptr;
    }

    if (avformat_find_stream_info(formatCtx, nullptr) < 0) {
        LOGE("Failed to find stream info");
        avformat_close_input(&formatCtx);
        return nullptr;
    }

    int64_t durationMs = (formatCtx->duration != AV_NOPTS_VALUE) ? (formatCtx->duration * 1000 / AV_TIME_BASE) : 0;
    int64_t bitRate = formatCtx->bit_rate;
    const char* formatName = formatCtx->iformat ? formatCtx->iformat->name : "unknown";

    // Java classes
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListInit = env->GetMethodID(arrayListClass, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jobject videoList = env->NewObject(arrayListClass, arrayListInit);
    jobject audioList = env->NewObject(arrayListClass, arrayListInit);
    jobject subtitleList = env->NewObject(arrayListClass, arrayListInit);
    jobject chapterList = env->NewObject(arrayListClass, arrayListInit);

    jclass videoStreamClass = env->FindClass("io/github/playme/media/info/VideoStream");
    jmethodID videoStreamInit = env->GetMethodID(
        videoStreamClass, "<init>", "(ILjava/lang/String;IIJFLjava/lang/String;Z)V");

    jclass audioStreamClass = env->FindClass("io/github/playme/media/info/AudioStream");
    jmethodID audioStreamInit = env->GetMethodID(
        audioStreamClass, "<init>", "(ILjava/lang/String;IIJLjava/lang/String;Ljava/lang/String;Z)V");

    jclass subStreamClass = env->FindClass("io/github/playme/media/info/SubtitleStream");
    jmethodID subStreamInit = env->GetMethodID(
        subStreamClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V");

    for (unsigned int i = 0; i < formatCtx->nb_streams; i++) {
        AVStream* stream = formatCtx->streams[i];
        AVCodecParameters* params = stream->codecpar;

        AVDictionaryEntry* langEntry = av_dict_get(stream->metadata, "language", nullptr, 0);
        jstring jLang = langEntry ? env->NewStringUTF(langEntry->value) : nullptr;

        AVDictionaryEntry* titleEntry = av_dict_get(stream->metadata, "title", nullptr, 0);
        jstring jTitle = titleEntry ? env->NewStringUTF(titleEntry->value) : nullptr;

        bool isDefault = (stream->disposition & AV_DISPOSITION_DEFAULT) != 0;
        bool isForced = (stream->disposition & AV_DISPOSITION_FORCED) != 0;

        const char* codecName = avcodec_get_name(params->codec_id);
        jstring jCodec = env->NewStringUTF(codecName);

        if (params->codec_type == AVMEDIA_TYPE_VIDEO) {
            float fps = 0.0f;
            if (stream->avg_frame_rate.den > 0) {
                fps = static_cast<float>(stream->avg_frame_rate.num) / stream->avg_frame_rate.den;
            }
            jobject vObj = env->NewObject(
                videoStreamClass, videoStreamInit,
                static_cast<jint>(i), jCodec, params->width, params->height,
                static_cast<jlong>(params->bit_rate), fps, nullptr, isDefault ? JNI_TRUE : JNI_FALSE
            );
            env->CallBooleanMethod(videoList, arrayListAdd, vObj);
            env->DeleteLocalRef(vObj);
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

    avformat_close_input(&formatCtx);

    // Build MediaInfo
    jclass mediaInfoClass = env->FindClass("io/github/playme/media/info/MediaInfo");
    jmethodID mediaInfoInit = env->GetMethodID(
        mediaInfoClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJLjava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V"
    );

    jstring jPath = env->NewStringUTF(filePath.c_str());
    jstring jFormat = env->NewStringUTF(formatName);

    jobject result = env->NewObject(
        mediaInfoClass, mediaInfoInit,
        jPath, jFormat, durationMs, bitRate,
        videoList, audioList, subtitleList, chapterList
    );

    env->DeleteLocalRef(jPath);
    env->DeleteLocalRef(jFormat);
    env->DeleteLocalRef(videoList);
    env->DeleteLocalRef(audioList);
    env->DeleteLocalRef(subtitleList);
    env->DeleteLocalRef(chapterList);

    return result;
#else
    return nullptr;
#endif
}

