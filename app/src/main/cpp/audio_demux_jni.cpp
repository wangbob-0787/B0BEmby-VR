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
#include <cerrno>
#include <chrono>
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

/* 连接生命周期计数（豆豆 2026-11-22：这一行是"旧连接有没有还"的铁证） */
static std::atomic<int> gConOpen{0};
static std::atomic<int> gConClose{0};
static std::atomic<int> gConFail{0};
static std::atomic<int> gConDupe{0};

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
    /* 真结尾判据：连续三次 AVERROR_EOF、间隔 300ms 以上、期间无网络错误（豆豆 2026-11-22） */
    int eofStreak = 0;
    int64_t lastEofMs = 0;
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
        gConFail.fetch_add(1);
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
    gConOpen.fetch_add(1);
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
    jint m[5] = {0, 0, 0, 0, 0};

    while (true) {
        int ret = av_read_frame(d->fmt, d->pkt);
        if (ret == AVERROR_EOF) {
            /*
             * 一次 EOF 不算结尾：假性 EOF 的特征是"偶发一次、伴随网络错误"。
             * 连续三次、间隔 300ms 以上，才判真结尾（豆豆 2026-11-22）。
             */
            int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
            if (now - d->lastEofMs > 300) {
                d->eofStreak = (d->eofStreak > 0 && now - d->lastEofMs < 1500) ? d->eofStreak + 1 : 1;
            } else {
                d->eofStreak++;
            }
            d->lastEofMs = now;
            if (d->eofStreak >= 3) {
                LOGI("连续三次读到结尾 → 判为真 EOF");
                return -1;
            }
            return -2;   // WAITING：上层等 300ms 再来
        }
        if (ret < 0) {
            d->eofStreak = 0;
            /* 网络类错误与致命错误分开：前者可以等/重连，后者重试无意义 */
            if (ret == AVERROR(EAGAIN) || ret == AVERROR(ETIMEDOUT) ||
                ret == AVERROR(ECONNRESET) || ret == AVERROR(EIO)) {
                LOGE("读包网络类错误: %s", errStr(ret).c_str());
                return -3;
            }
            LOGE("读包失败（致命）: %s", errStr(ret).c_str());
            return -4;
        }
        d->eofStreak = 0;
        if (d->pkt->stream_index == d->audioIdx) {
            int size = d->pkt->size;
            if (size > cap) {
                LOGE("输出缓冲不足 need=%d cap=%d → 致命（不是网络错误）", size, cap);
                av_packet_unref(d->pkt);
                return -4;
            }
            AVStream *st = d->fmt->streams[d->audioIdx];
            m[0] = 0;
            m[1] = size;
            m[2] = (jint) (d->pkt->pts == AV_NOPTS_VALUE ? -1
                    : av_rescale_q(d->pkt->pts, st->time_base, AVRational{1, 1000}));
            env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte *>(d->pkt->data));
            env->SetIntArrayRegion(meta, 0, 5, m);
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
                    /* 结束时间：AVSubtitle 给的是相对 start 的毫秒；缺失就用 0 让上层兜底 */
                    m[4] = (jint) ((sub.end_display_time > sub.start_display_time)
                                   ? (ptsMs + sub.end_display_time) : 0);
                    env->SetByteArrayRegion(out, 0, (jsize) text.size(),
                                            reinterpret_cast<const jbyte *>(text.data()));
                    env->SetIntArrayRegion(meta, 0, 5, m);
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

/**
 * 音频参数：out[0]=采样率 out[1]=声道数 out[2]=codec_id out[3]=文字字幕轨条数
 * 换了 MediaExtractor 之后，建 AudioTrack 与开解码器只能从这里拿参数。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxInfo(
        JNIEnv *env, jobject, jlong handle, jintArray out) {
    Demux *d = fromHandle(handle);
    if (!d || !d->fmt || d->audioIdx < 0) return -1;
    AVCodecParameters *p = d->fmt->streams[d->audioIdx]->codecpar;
    /* 时长三级回退：容器 → 音频流 → 无（无时长时上层只用 EOF 连击判据） */
    int64_t durMs = 0;
    if (d->fmt->duration != AV_NOPTS_VALUE && d->fmt->duration > 0) {
        durMs = d->fmt->duration / (AV_TIME_BASE / 1000);
    } else {
        AVStream *st = d->fmt->streams[d->audioIdx];
        if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
            durMs = (int64_t) (st->duration * av_q2d(st->time_base) * 1000.0);
            if (st->start_time != AV_NOPTS_VALUE) {
                durMs -= (int64_t) (st->start_time * av_q2d(st->time_base) * 1000.0);
            }
        }
    }
    if (durMs <= 0) LOGE("无容器时长，EOF 判据降级为仅连击");
    jint v[5] = {p->sample_rate, p->ch_layout.nb_channels, (jint) p->codec_id, d->subN, (jint) durMs};
    env->SetIntArrayRegion(out, 0, 5, v);
    return 0;
}

/** 音频解码器名（"eac3"/"truehd"/"dca" 等），给自带解码器用 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxCodecName(JNIEnv *env, jobject, jlong handle) {
    Demux *d = fromHandle(handle);
    if (!d || !d->fmt || d->audioIdx < 0) return env->NewStringUTF("");
    const AVCodecDescriptor *desc =
            avcodec_descriptor_get(d->fmt->streams[d->audioIdx]->codecpar->codec_id);
    if (!desc || !desc->name) return env->NewStringUTF("");
    return env->NewStringUTF(desc->name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxAbort(JNIEnv *, jobject, jlong handle) {
    Demux *d = fromHandle(handle);
    if (d) d->abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxClose(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) {
        gConDupe.fetch_add(1);
        LOGI("重复关闭（handle=0）→ 只记数，不重复减");
        return;
    }
    freeDemux(fromHandle(handle));
    /* 真正释放之后才减，"在飞"才不会少算（豆豆 2026-11-22） */
    gConClose.fetch_add(1);
}

/** out[0]=open out[1]=close out[2]=fail out[3]=dupe out[4]=inFlight */
extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxStats(
        JNIEnv *env, jobject, jintArray out) {
    int o = gConOpen.load(), c = gConClose.load();
    jint v[5] = {o, c, gConFail.load(), gConDupe.load(), o - c};
    env->SetIntArrayRegion(out, 0, 5, v);
    return 0;
}
