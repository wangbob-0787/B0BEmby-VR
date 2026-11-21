/*
 * 自带音频解码器（spike 阶段）
 *
 * 只做一件事：把系统解封装给出的**单个压缩音频包**解成 s16 交错 PCM，必要时重采样。
 * 不碰解封装、不碰播放时钟、不碰 AudioPipe —— 先单独验「解出来的字节数对不对」，
 * 验过再接进播放链路。
 *
 * 依据：FFmpeg n7.1，静态库由 .github/workflows/build-ffmpeg-audio.yml 产出，
 * 客户端编译时从 Release 标签 ffmpeg-audio-n7.1 现取（见 build.yml）。
 */
#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstring>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"
}

#define LOG_TAG "B0BEmbyVR-AudioDec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBytesPerSample = 2;   // s16
constexpr int kMaxOutChannels = 8;

struct AudioDec {
    AVCodecContext *ctx = nullptr;
    SwrContext *swr = nullptr;
    AVFrame *frame = nullptr;
    AVPacket *pkt = nullptr;
    int targetChannels = 0;   // 0 = 保持源布局
    int outChannels = 0;
    bool eofSent = false;
};

void freeDec(AudioDec *d) {
    if (!d) return;
    if (d->swr) swr_free(&d->swr);
    if (d->frame) av_frame_free(&d->frame);
    if (d->pkt) av_packet_free(&d->pkt);
    if (d->ctx) avcodec_free_context(&d->ctx);
    delete d;
}

std::string errStr(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return std::string(buf);
}

bool initResampler(AudioDec *d, const AVFrame *in) {
    AVChannelLayout outLayout;
    if (d->targetChannels == 2) {
        av_channel_layout_default(&outLayout, 2);
    } else if (d->targetChannels > 0 && d->targetChannels != in->ch_layout.nb_channels) {
        av_channel_layout_default(&outLayout, d->targetChannels);
    } else {
        av_channel_layout_copy(&outLayout, &in->ch_layout);
    }

    int ret = swr_alloc_set_opts2(&d->swr,
                                  &outLayout, AV_SAMPLE_FMT_S16, in->sample_rate,
                                  &in->ch_layout, (AVSampleFormat) in->format, in->sample_rate,
                                  0, nullptr);
    d->outChannels = outLayout.nb_channels;
    av_channel_layout_uninit(&outLayout);
    if (ret < 0) {
        LOGE("建重采样失败: %s", errStr(ret).c_str());
        return false;
    }
    ret = swr_init(d->swr);
    if (ret < 0) {
        LOGE("初始化重采样失败: %s", errStr(ret).c_str());
        return false;
    }
    LOGI("重采样就绪: 源 %d 声道 fmt=%d rate=%d → 输出 %d 声道 s16 rate=%d",
         in->ch_layout.nb_channels, in->format, in->sample_rate,
         d->outChannels, in->sample_rate);
    return true;
}

/** 把解码器里已解出的帧全部转成 s16 写入 out，返回写入字节数；负数为错误。 */
int drainFrames(AudioDec *d, uint8_t *out, int outCap) {
    int written = 0;
    while (true) {
        int ret = avcodec_receive_frame(d->ctx, d->frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) {
            LOGE("取帧失败: %s", errStr(ret).c_str());
            return ret;
        }
        if (!d->swr) {
            if (!initResampler(d, d->frame)) return AVERROR_EXTERNAL;
        }
        // 重采样后的上限（含内部残留）
        int maxSamples = swr_get_out_samples(d->swr, d->frame->nb_samples);
        int need = maxSamples * d->outChannels * kBytesPerSample;
        if (written + need > outCap) {
            // 调用方给的缓冲不够：这一帧不解，留给下一次。不能半帧写出，否则字节数不帧对齐。
            LOGE("输出缓冲不够: 已写 %d, 本帧最多需 %d, 容量 %d", written, need, outCap);
            return written > 0 ? written : AVERROR(ENOMEM);
        }
        uint8_t *dst = out + written;
        int got = swr_convert(d->swr, &dst, maxSamples,
                              (const uint8_t **) d->frame->extended_data,
                              d->frame->nb_samples);
        if (got < 0) {
            LOGE("重采样失败: %s", errStr(got).c_str());
            return got;
        }
        written += got * d->outChannels * kBytesPerSample;
        av_frame_unref(d->frame);
    }
    return written;
}

AudioDec *fromHandle(jlong handle) {
    return reinterpret_cast<AudioDec *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_xxxx_emby_1vr_player_AudioDecoderNative_nativeNew(JNIEnv *, jobject) {
    auto *d = new AudioDec();
    d->frame = av_frame_alloc();
    d->pkt = av_packet_alloc();
    if (!d->frame || !d->pkt) {
        LOGE("分配帧/包失败");
        freeDec(d);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(d));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDecoderNative_nativeOpen(
        JNIEnv *env, jobject, jlong handle, jstring codec_name, jbyteArray extradata,
        jint target_channels) {
    AudioDec *d = fromHandle(handle);
    if (!d) return -1;

    const char *name = env->GetStringUTFChars(codec_name, nullptr);
    if (!name) return -1;
    const AVCodec *codec = avcodec_find_decoder_by_name(name);
    env->ReleaseStringUTFChars(codec_name, name);
    if (!codec) {
        LOGE("找不到解码器: %s", name);
        return -2;
    }

    d->targetChannels = target_channels;
    d->ctx = avcodec_alloc_context3(codec);
    if (!d->ctx) return -3;

    if (extradata) {
        jsize size = env->GetArrayLength(extradata);
        uint8_t *buf = (uint8_t *) av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!buf) return -4;
        env->GetByteArrayRegion(extradata, 0, size, reinterpret_cast<jbyte *>(buf));
        d->ctx->extradata = buf;
        d->ctx->extradata_size = size;
    }

    int ret = avcodec_open2(d->ctx, codec, nullptr);
    if (ret < 0) {
        LOGE("打开解码器失败: %s", errStr(ret).c_str());
        return -5;
    }
    LOGI("解码器就绪: %s", codec->name);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDecoderNative_nativeDecode(
        JNIEnv *env, jobject, jlong handle, jbyteArray input, jint input_len, jbyteArray out) {
    AudioDec *d = fromHandle(handle);
    if (!d || !d->ctx) return -1;

    jsize inCap = env->GetArrayLength(input);
    jsize outCap = env->GetArrayLength(out);
    if (input_len <= 0 || input_len > inCap) return -2;

    uint8_t *inBuf = (uint8_t *) env->GetPrimitiveArrayCritical(input, nullptr);
    if (!inBuf) return -3;
    av_new_packet(d->pkt, input_len);
    memcpy(d->pkt->data, inBuf, input_len);
    env->ReleasePrimitiveArrayCritical(input, inBuf, JNI_ABORT);

    int ret = avcodec_send_packet(d->ctx, d->pkt);
    av_packet_unref(d->pkt);
    if (ret < 0 && ret != AVERROR(EAGAIN)) {
        LOGE("送包失败: %s", errStr(ret).c_str());
        return ret;
    }

    uint8_t *outBuf = (uint8_t *) env->GetPrimitiveArrayCritical(out, nullptr);
    if (!outBuf) return -3;
    ret = drainFrames(d, outBuf, outCap);
    env->ReleasePrimitiveArrayCritical(out, outBuf, 0);
    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDecoderNative_nativeFlush(
        JNIEnv *env, jobject, jlong handle, jbyteArray out) {
    AudioDec *d = fromHandle(handle);
    if (!d || !d->ctx) return -1;

    if (!d->eofSent) {
        int ret = avcodec_send_packet(d->ctx, nullptr);
        if (ret < 0 && ret != AVERROR_EOF) {
            LOGE("送结束标记失败: %s", errStr(ret).c_str());
            return ret;
        }
        d->eofSent = true;
    }

    jsize outCap = env->GetArrayLength(out);
    uint8_t *outBuf = (uint8_t *) env->GetPrimitiveArrayCritical(out, nullptr);
    if (!outBuf) return -3;
    int ret = drainFrames(d, outBuf, outCap);
    env->ReleasePrimitiveArrayCritical(out, outBuf, 0);
    return ret;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDecoderNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    freeDec(fromHandle(handle));
}
