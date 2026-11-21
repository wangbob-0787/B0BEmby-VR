/*
 * 自管音频那一路的解封装（FFmpeg avformat）。
 *
 * 为什么换掉 MediaExtractor：系统接口一次只能读「当前一条轨」，想同时拿音频包和字幕包
 * 只能多开连接，而网盘对同一文件只给两路 —— 那第三路必然被拒（2026-11-22 实测服务端取字幕
 * 90 秒零字节）。avformat 一次打开就能按块头把各轨拆开，连接数不变。
 *
 * 只服务音频这一侧：视频那一路仍用 MediaExtractor，一行不动（豆豆 2026-11-22 的边界）。
 *
 * 用法（Kotlin 侧）：
 *   h = nativeDemuxOpen(url, startMs)
 *   loop { n = nativeDemuxRead(h, buf) ... meta[0] = 0 音频包 / 2 字幕文本 }
 *   nativeDemuxSeek(h, ms) / nativeDemuxClose(h)
 */
#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/error.h"
#include "libavutil/opt.h"
}

#define LOG_TAG "B0BEmbyVR-Demux"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Demux {
    AVFormatContext *fmt = nullptr;
    int audioIdx = -1;
    int subIdx = -1;          // 第一条「文字」字幕轨（图形字幕不走这里）
    AVCodecContext *subDec = nullptr;
    AVPacket *pkt = nullptr;
    AVSubtitle sub{};
    bool subInited = false;
};

std::string errStr(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return std::string(buf);
}

void freeDemux(Demux *d) {
    if (!d) return;
    if (d->subInited && d->sub.num_rects > 0) avsubtitle_free(&d->sub);
    if (d->subDec) avcodec_free_context(&d->subDec);
    if (d->pkt) av_packet_free(&d->pkt);
    if (d->fmt) avformat_close_input(&d->fmt);
    delete d;
}

Demux *fromHandle(jlong h) { return reinterpret_cast<Demux *>(static_cast<intptr_t>(h)); }

/** ASS 事件行取正文：前 9 段是层/时间/样式等，第 10 段才是文字 */
std::string assText(const char *ass) {
    if (!ass) return "";
    std::string s(ass);
    int commas = 0;
    size_t i = 0;
    for (; i < s.size(); ++i) {
        if (s[i] == ',') {
            if (++commas == 9) break;
        }
    }
    if (commas < 9) return s;   // 不是 ASS 事件行，按纯文本
    std::string out = s.substr(i + 1);
    /* 去掉 {\...} 样式标签与 \N 换行 */
    std::string clean;
    bool inTag = false;
    for (size_t k = 0; k < out.size(); ++k) {
        char c = out[k];
        if (c == '{') { inTag = true; continue; }
        if (c == '}') { inTag = false; continue; }
        if (inTag) continue;
        if (c == '\\' && k + 1 < out.size() && (out[k + 1] == 'N' || out[k + 1] == 'n')) {
            clean.push_back('\n'); ++k; continue;
        }
        clean.push_back(c);
    }
    return clean;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxOpen(
        JNIEnv *env, jobject, jstring url, jlong startMs) {
    const char *u = env->GetStringUTFChars(url, nullptr);
    if (!u) return 0;
    avformat_network_init();

    auto *d = new Demux();
    AVFormatContext *fmt = nullptr;
    AVDictionary *opt = nullptr;
    av_dict_set(&opt, "timeout", "20000000", 0);        // 20s：网络源首字节慢是常态
    av_dict_set(&opt, "reconnect", "1", 0);
    int ret = avformat_open_input(&fmt, u, nullptr, &opt);
    av_dict_free(&opt);
    env->ReleaseStringUTFChars(url, u);
    if (ret < 0) {
        LOGE("打开失败: %s", errStr(ret).c_str());
        delete d;
        return 0;
    }
    d->fmt = fmt;
    ret = avformat_find_stream_info(fmt, nullptr);
    if (ret < 0) {
        LOGE("读流信息失败: %s", errStr(ret).c_str());
        freeDemux(d);
        return 0;
    }
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        AVCodecParameters *p = fmt->streams[i]->codecpar;
        if (d->audioIdx < 0 && p->codec_type == AVMEDIA_TYPE_AUDIO) d->audioIdx = (int) i;
        if (d->subIdx < 0 && p->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            /* 只收文字字幕；位图字幕（PGS/DVD）留给内核那条路 */
            if (p->codec_id == AV_CODEC_ID_SUBRIP || p->codec_id == AV_CODEC_ID_ASS ||
                p->codec_id == AV_CODEC_ID_SSA || p->codec_id == AV_CODEC_ID_WEBVTT ||
                p->codec_id == AV_CODEC_ID_MOV_TEXT) {
                d->subIdx = (int) i;
            }
        }
    }
    if (d->audioIdx < 0) {
        LOGE("这条片没有音频轨");
        freeDemux(d);
        return 0;
    }
    d->pkt = av_packet_alloc();
    if (!d->pkt) { freeDemux(d); return 0; }

    if (d->subIdx >= 0) {
        AVCodecParameters *sp = fmt->streams[d->subIdx]->codecpar;
        const AVCodec *dec = avcodec_find_decoder(sp->codec_id);
        if (dec) {
            d->subDec = avcodec_alloc_context3(dec);
            if (d->subDec && avcodec_parameters_to_context(d->subDec, sp) >= 0 &&
                avcodec_open2(d->subDec, dec, nullptr) >= 0) {
                LOGI("字幕轨就绪: 流 %d 编码 %s", d->subIdx, dec->name);
            } else {
                avcodec_free_context(&d->subDec);
            }
        }
    }
    LOGI("解封装就绪: 音频流 %d%s", d->audioIdx, d->subIdx >= 0 ? "，字幕轨已接" : "，无文字字幕轨");

    if (startMs > 0) {
        int64_t ts = av_rescale(startMs, fmt->streams[d->audioIdx]->time_base.den,
                                fmt->streams[d->audioIdx]->time_base.num * 1000);
        av_seek_frame(fmt, d->audioIdx, ts, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(d->subDec);
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(d));
}

/**
 * 读下一个包。
 * meta[0] = 0 音频包 / 2 字幕文本；meta[1] = 字节数；meta[2] = 时间戳（毫秒）
 * 返回：>=0 包字节数；-1 文件结束；<0 出错。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxRead(
        JNIEnv *env, jobject, jlong handle, jbyteArray out, jintArray meta) {
    Demux *d = fromHandle(handle);
    if (!d || !d->fmt) return -2;
    jsize cap = env->GetArrayLength(out);
    jint m[4] = {0, 0, 0, 0};

    while (true) {
        int ret = av_read_frame(d->fmt, d->pkt);
        if (ret == AVERROR_EOF) return -1;
        if (ret < 0) {
            LOGE("读包失败: %s", errStr(ret).c_str());
            return ret;
        }
        if (d->pkt->stream_index == d->audioIdx) {
            int size = d->pkt->size;
            if (size > cap) { av_packet_unref(d->pkt); return -3; }
            AVStream *st = d->fmt->streams[d->audioIdx];
            m[0] = 0;
            m[1] = size;
            m[2] = (jint) (d->pkt->pts == AV_NOPTS_VALUE ? 0
                    : av_rescale_q(d->pkt->pts, st->time_base, AVRational{1, 1000}));
            env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte *>(d->pkt->data));
            env->SetIntArrayRegion(meta, 0, 4, m);
            av_packet_unref(d->pkt);
            return size;
        }
        if (d->pkt->stream_index == d->subIdx && d->subDec) {
            int got = 0;
            int used = avcodec_decode_subtitle2(d->subDec, &d->sub, &got, d->pkt);
            if (used >= 0 && got && d->sub.num_rects > 0) {
                std::string text;
                for (unsigned i = 0; i < d->sub.num_rects; ++i) {
                    const char *line = d->sub.rects[i]->ass ? d->sub.rects[i]->ass
                                                            : d->sub.rects[i]->text;
                    if (!line) continue;
                    std::string t = d->sub.rects[i]->ass ? assText(line) : std::string(line);
                    if (t.empty()) continue;
                    if (!text.empty()) text.push_back('\n');
                    text += t;
                }
                AVStream *st = d->fmt->streams[d->subIdx];
                int64_t ptsMs = d->sub.pts != AV_NOPTS_VALUE
                                ? av_rescale_q(d->sub.pts, st->time_base, AVRational{1, 1000})
                                : 0;
                if (!text.empty() && (int) text.size() <= cap) {
                    m[0] = 2;
                    m[1] = (jint) text.size();
                    m[2] = (jint) ptsMs;
                    m[3] = (jint) ((d->sub.end_display_time > 0) ? d->sub.end_display_time : 0);
                    env->SetByteArrayRegion(out, 0, (jsize) text.size(),
                                            reinterpret_cast<const jbyte *>(text.data()));
                    env->SetIntArrayRegion(meta, 0, 4, m);
                }
                if (d->sub.num_rects > 0) avsubtitle_free(&d->sub);
                av_packet_unref(d->pkt);
                if (m[1] > 0) return m[1];
                continue;   // 空字幕包，继续读
            }
            if (got && d->sub.num_rects > 0) avsubtitle_free(&d->sub);
            av_packet_unref(d->pkt);
            continue;
        }
        av_packet_unref(d->pkt);   // 视频包（这一路不管）直接丢
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxSeek(
        JNIEnv *env, jobject, jlong handle, jlong ms) {
    Demux *d = fromHandle(handle);
    if (!d || !d->fmt || d->audioIdx < 0) return -1;
    AVStream *st = d->fmt->streams[d->audioIdx];
    int64_t ts = av_rescale(ms, st->time_base.den, st->time_base.num * 1000);
    int ret = av_seek_frame(d->fmt, d->audioIdx, ts, AVSEEK_FLAG_BACKWARD);
    if (ret < 0) {
        LOGE("跳转失败: %s", errStr(ret).c_str());
        return ret;
    }
    if (d->subDec) avcodec_flush_buffers(d->subDec);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxClose(JNIEnv *, jobject, jlong handle) {
    freeDemux(fromHandle(handle));
}
