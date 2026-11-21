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

#include <atomic>
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

constexpr int kMaxSub = 12;

struct Demux {
    AVFormatContext *fmt = nullptr;
    /* stop 时置位：打断阻塞中的 open/read（MediaExtractor 打断不了，只能干等 8 秒） */
    std::atomic<bool> abort{false};
    int audioIdx = -1;
    /*
     * 全部「文字」字幕轨各自一个解码上下文。
     *
     * 必须全读、不能只读选中的那条：切换字幕轨要零等待，就得每条轨的包都已经在往里解；
     * 只读一条的话，切轨仍要重定位重读，又退回"要等"（豆豆 2026-11-22）。
     */
    int subIdx[kMaxSub] = {0};
    AVCodecContext *subDec[kMaxSub] = {nullptr};
    int subN = 0;
    AVPacket *pkt = nullptr;
};

std::string errStr(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return std::string(buf);
}

void freeDemux(Demux *d) {
    if (!d) return;
    for (int i = 0; i < kMaxSub; ++i) {
        if (d->subDec[i]) avcodec_free_context(&d->subDec[i]);
    }
    if (d->pkt) av_packet_free(&d->pkt);
    if (d->fmt) avformat_close_input(&d->fmt);
    delete d;
}

Demux *fromHandle(jlong h) { return reinterpret_cast<Demux *>(static_cast<intptr_t>(h)); }

int interruptCb(void *opaque) {
    auto *d = static_cast<Demux *>(opaque);
    return (d && d->abort.load()) ? 1 : 0;
}

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
    d->abort.store(false);
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
    /* 打断回调必须在 open 之前挂上才管用；这里补挂一次（open 已被打断时下面会失败） */
    fmt->interrupt_callback.callback = interruptCb;
    fmt->interrupt_callback.opaque = d;
    ret = avformat_find_stream_info(fmt, nullptr);
    if (ret < 0) {
        LOGE("读流信息失败: %s", errStr(ret).c_str());
        freeDemux(d);
        return 0;
    }
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        AVCodecParameters *p = fmt->streams[i]->codecpar;
        if (d->audioIdx < 0 && p->codec_type == AVMEDIA_TYPE_AUDIO) d->audioIdx = (int) i;
        if (p->codec_type == AVMEDIA_TYPE_SUBTITLE && d->subN < kMaxSub) {
            /* 只收文字字幕；位图字幕（PGS/DVD）留给内核那条路 */
            if (p->codec_id == AV_CODEC_ID_SUBRIP || p->codec_id == AV_CODEC_ID_ASS ||
                p->codec_id == AV_CODEC_ID_SSA || p->codec_id == AV_CODEC_ID_WEBVTT ||
                p->codec_id == AV_CODEC_ID_MOV_TEXT) {
                d->subIdx[d->subN++] = (int) i;
            }
        }
    }
    /*
     * 显式丢掉不要的轨。
     *
     * av_read_frame 返回**所有轨**的包；命令行里没暴露是因为 -map 会自动 discard 未映射的轨。
     * 不设的话这一路等于把 4K 视频也整片拉一遍 —— 网盘额度直接翻倍（豆豆 2026-11-22）。
     * 字幕轨全部保留：切轨要零等待。
     */
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        AVStream *st = fmt->streams[i];
        if ((int) i == d->audioIdx) continue;
        if (st->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            bool textSub = st->codecpar->codec_id == AV_CODEC_ID_SUBRIP ||
                           st->codecpar->codec_id == AV_CODEC_ID_ASS ||
                           st->codecpar->codec_id == AV_CODEC_ID_SSA ||
                           st->codecpar->codec_id == AV_CODEC_ID_WEBVTT ||
                           st->codecpar->codec_id == AV_CODEC_ID_MOV_TEXT;
            st->discard = textSub ? AVDISCARD_DEFAULT : AVDISCARD_ALL;
        } else {
            st->discard = AVDISCARD_ALL;   // 视频 + 未选中的音频
        }
    }
    if (d->audioIdx < 0) {
        LOGE("这条片没有音频轨");
        freeDemux(d);
        return 0;
    }
    d->pkt = av_packet_alloc();
    if (!d->pkt) { freeDemux(d); return 0; }

    for (int k = 0; k < d->subN; ++k) {
        AVCodecParameters *sp = fmt->streams[d->subIdx[k]]->codecpar;
        const AVCodec *dec = avcodec_find_decoder(sp->codec_id);
        if (!dec) continue;
        AVCodecContext *ctx = avcodec_alloc_context3(dec);
        if (ctx && avcodec_parameters_to_context(ctx, sp) >= 0 &&
            avcodec_open2(ctx, dec, nullptr) >= 0) {
            d->subDec[k] = ctx;
            LOGI("字幕轨就绪: 第 %d 条（流 %d，%s）", k, d->subIdx[k], dec->name);
        } else {
            if (ctx) avcodec_free_context(&ctx);
        }
    }
    LOGI("解封装就绪: 音频流 %d，文字字幕轨 %d 条", d->audioIdx, d->subN);

    if (startMs > 0) {
        int64_t ts = av_rescale(startMs, fmt->streams[d->audioIdx]->time_base.den,
                                fmt->streams[d->audioIdx]->time_base.num * 1000);
        av_seek_frame(fmt, d->audioIdx, ts, AVSEEK_FLAG_BACKWARD);
        for (int k = 0; k < d->subN; ++k) {
            if (d->subDec[k]) avcodec_flush_buffers(d->subDec[k]);
        }
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
            m[2] = (jint) (d->pkt->pts == AV_NOPTS_VALUE ? -1
                    : av_rescale_q(d->pkt->pts, st->time_base, AVRational{1, 1000}));
            env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte *>(d->pkt->data));
            env->SetIntArrayRegion(meta, 0, 4, m);
            av_packet_unref(d->pkt);
            return size;
        }
        /* 字幕：先看这个包属于哪一条字幕轨（多轨各解各的，切轨零等待） */
        int subK = -1;
        for (int k = 0; k < d->subN; ++k) {
            if (d->subIdx[k] == d->pkt->stream_index) { subK = k; break; }
        }
        if (subK >= 0 && d->subDec[subK]) {
            AVSubtitle sub{};
            int got = 0;
            int used = avcodec_decode_subtitle2(d->subDec[subK], &sub, &got, d->pkt);
            if (used >= 0 && got && sub.num_rects > 0) {
                std::string text;
                for (unsigned i = 0; i < sub.num_rects; ++i) {
                    const char *line = sub.rects[i]->ass ? sub.rects[i]->ass
                                                         : sub.rects[i]->text;
                    if (!line) continue;
                    std::string t = sub.rects[i]->ass ? assText(line) : std::string(line);
                    if (t.empty()) continue;
                    if (!text.empty()) text.push_back('\n');
                    text += t;
                }
                AVStream *st = d->fmt->streams[d->subIdx[subK]];
                int64_t ptsMs = sub.pts != AV_NOPTS_VALUE
                                ? av_rescale_q(sub.pts, st->time_base, AVRational{1, 1000})
                                : -1;   // 未知时间戳不能当 0，否则字幕会被排到片头
                if (!text.empty() && (int) text.size() <= cap) {
                    m[0] = 2;
                    m[1] = (jint) text.size();
                    m[2] = (jint) ptsMs;
                    m[3] = subK;   // 哪一条字幕轨（切轨时按它挑缓冲）
                    env->SetByteArrayRegion(out, 0, (jsize) text.size(),
                                            reinterpret_cast<const jbyte *>(text.data()));
                    env->SetIntArrayRegion(meta, 0, 4, m);
                }
                avsubtitle_free(&sub);
                av_packet_unref(d->pkt);
                if (m[1] > 0) return m[1];
                continue;
            }
            if (got) avsubtitle_free(&sub);
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
    for (int k = 0; k < d->subN; ++k) {
        if (d->subDec[k]) avcodec_flush_buffers(d->subDec[k]);
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxAbort(JNIEnv *, jobject, jlong handle) {
    Demux *d = fromHandle(handle);
    if (d) d->abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxClose(JNIEnv *, jobject, jlong handle) {
    freeDemux(fromHandle(handle));
}
