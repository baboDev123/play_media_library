#include "playme_audio.h"

PlayMeAudioDecoderNative::PlayMeAudioDecoderNative(const std::string& mimeType, int sampleRate, int channelCount)
    : mMimeType(mimeType),
      mSampleRate(sampleRate),
      mChannelCount(channelCount)
#ifndef PLAYME_STUB_FFMPEG
      , mCodec(nullptr),
      mCodecContext(nullptr),
      mFrame(nullptr),
      mPacket(nullptr),
      mSwrContext(nullptr)
#endif
{
#ifndef PLAYME_STUB_FFMPEG
    AVCodecID codecId = AV_CODEC_ID_NONE;
    if (mimeType == "audio/ac3") codecId = AV_CODEC_ID_AC3;
    else if (mimeType == "audio/eac3") codecId = AV_CODEC_ID_EAC3;
    else if (mimeType == "audio/vnd.dts" || mimeType == "audio/dts") codecId = AV_CODEC_ID_DTS;
    else if (mimeType == "audio/true-hd") codecId = AV_CODEC_ID_TRUEHD;
    else if (mimeType == "audio/flac") codecId = AV_CODEC_ID_FLAC;
    else if (mimeType == "audio/vorbis") codecId = AV_CODEC_ID_VORBIS;
    else if (mimeType == "audio/opus") codecId = AV_CODEC_ID_OPUS;
    else if (mimeType == "audio/alac") codecId = AV_CODEC_ID_ALAC;
    else if (mimeType == "audio/mp4a-latm") codecId = AV_CODEC_ID_AAC;

    if (codecId != AV_CODEC_ID_NONE) {
        mCodec = avcodec_find_decoder(codecId);
        if (mCodec) {
            mCodecContext = avcodec_alloc_context3(mCodec);
            if (mCodecContext) {
                mCodecContext->sample_rate = sampleRate;
                mCodecContext->ch_layout.nb_channels = channelCount;
                avcodec_open2(mCodecContext, mCodec, nullptr);
            }
        }
        mFrame = av_frame_alloc();
        mPacket = av_packet_alloc();
    }
#endif
}

PlayMeAudioDecoderNative::~PlayMeAudioDecoderNative() {
    release();
}

int PlayMeAudioDecoderNative::decodeAudio(const uint8_t* inData, size_t inSize, uint8_t* outData, size_t outCapacity) {
#ifndef PLAYME_STUB_FFMPEG
    if (!mCodecContext || !mFrame || !mPacket) return -1;

    mPacket->data = const_cast<uint8_t*>(inData);
    mPacket->size = static_cast<int>(inSize);

    int ret = avcodec_send_packet(mCodecContext, mPacket);
    if (ret < 0 && ret != AVERROR(EAGAIN)) {
        return ret;
    }

    ret = avcodec_receive_frame(mCodecContext, mFrame);
    if (ret < 0) {
        return (ret == AVERROR(EAGAIN)) ? 0 : ret;
    }

    // Initialize resampler to convert decoded audio to Signed 16-bit PCM
    if (!mSwrContext) {
        AVChannelLayout outLayout;
        av_channel_layout_default(&outLayout, mChannelCount);

        swr_alloc_set_opts2(
            &mSwrContext,
            &outLayout,
            AV_SAMPLE_FMT_S16,
            mSampleRate,
            &mFrame->ch_layout,
            static_cast<AVSampleFormat>(mFrame->format),
            mFrame->sample_rate,
            0,
            nullptr
        );
        if (mSwrContext) {
            swr_init(mSwrContext);
        }
    }

    if (!mSwrContext) return -1;

    int maxDstSamples = static_cast<int>(outCapacity / (mChannelCount * 2));
    uint8_t* dstData[1] = { outData };

    int convertedSamples = swr_convert(
        mSwrContext,
        dstData,
        maxDstSamples,
        const_cast<const uint8_t**>(mFrame->data),
        mFrame->nb_samples
    );

    if (convertedSamples < 0) return convertedSamples;

    return convertedSamples * mChannelCount * 2; // Total bytes written
#else
    return 0;
#endif
}

void PlayMeAudioDecoderNative::flush() {
#ifndef PLAYME_STUB_FFMPEG
    if (mCodecContext) {
        avcodec_flush_buffers(mCodecContext);
    }
#endif
}

void PlayMeAudioDecoderNative::release() {
#ifndef PLAYME_STUB_FFMPEG
    if (mSwrContext) {
        swr_free(&mSwrContext);
        mSwrContext = nullptr;
    }
    if (mFrame) {
        av_frame_free(&mFrame);
    }
    if (mPacket) {
        av_packet_free(&mPacket);
    }
    if (mCodecContext) {
        avcodec_free_context(&mCodecContext);
    }
#endif
}

