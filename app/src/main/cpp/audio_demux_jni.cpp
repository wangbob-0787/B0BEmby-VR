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
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
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
    /*
     * 打开期的打断源：Java 侧传进来的 AtomicBoolean（全局引用）。
     * 打开时 Java 还没有句柄，nativeDemuxAbort 叫不动它，只能靠这个标志
     * 让 avformat_open_input 与首次 seek 能被打断（豆豆 2026-11-22 P0-1）。
     */
    JavaVM *vm = nullptr;
    jobject abortFlag = nullptr;
    jmethodID abortGet = nullptr;
    /*
     * 打开相位：只有打开期间才去读 Java 的打断标志。
     * 打开结束后立刻关相位 —— 否则"晚到的打断置位"会毒死一个已经成功的会话，
     * 之后每次读包都被判中断（豆豆 2026-11-22 P0-2）。
     */
    std::atomic<bool> opening{false};
    int subDbg = 0;   // 字幕时长取证计数
    int audioIdx = -1;
    int videoIdx = -1;
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
    /* 最后一个音频包的片源时间（毫秒）：判"真结尾"的第二道判据 */
    int64_t lastAudioPtsMs = -1;
    int64_t durMs = 0;
    /* 首包位置校验：确认源真的按 Range 给了我们要的那一段 */
    bool firstAudioChecked = false;
    int64_t openStartMs = 0;
    /* 三级 seek 全失败：只说明"不能从中间定位"，不代表文件不能播 */
    bool seekFailed = false;
};

std::string errStr(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return std::string(buf);
}

void freeDemux(JNIEnv *env, Demux *d) {
    if (!d) return;
    if (d->abortFlag && env) {
        env->DeleteGlobalRef(d->abortFlag);
        d->abortFlag = nullptr;
    }
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
    if (!d) return 0;
    if (d->abort.load()) return 1;
    /* 打开结束后不再看 Java 标志：脏标志不许影响已经建好的会话 */
    if (!d->opening.load()) return 0;
    if (!d->abortFlag || !d->vm || !d->abortGet) return 0;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (d->vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (d->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }
    jboolean v = env->CallBooleanMethod(d->abortFlag, d->abortGet);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (attached) d->vm->DetachCurrentThread();
        return 0;
    }
    if (attached) d->vm->DetachCurrentThread();
    return v ? 1 : 0;
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
        JNIEnv *env, jobject, jstring url, jlong startMs, jint discardOn, jobject abortFlag) {
    const char *u = env->GetStringUTFChars(url, nullptr);
    if (!u) return 0;
    avformat_network_init();

    auto *d = new Demux();
    d->abort.store(false);
    d->opening.store(true);
    /* 绑定 Java 的打断标志：打开期只有它能让网络 IO 停下来 */
    if (abortFlag) {
        env->GetJavaVM(&d->vm);
        d->abortFlag = env->NewGlobalRef(abortFlag);
        jclass cls = env->GetObjectClass(abortFlag);
        d->abortGet = env->GetMethodID(cls, "get", "()Z");
        env->DeleteLocalRef(cls);
        /* 方法找不到会留下 pending 异常，返回 Java 时会抛出去；置空降级为"只能用 abort 打断" */
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            d->abortGet = nullptr;
            LOGE("绑定打断标志失败：找不到 AtomicBoolean.get()，降级为只能 abort 打断");
        }
    }
    AVFormatContext *fmt = avformat_alloc_context();
    if (!fmt) {
        d->opening.store(false);
        if (d->abortFlag) env->DeleteGlobalRef(d->abortFlag);
        delete d;
        return 0;
    }
    /*
     * 打断回调必须在 avformat_open_input **之前**挂上：
     * 打开自己慢起来的时候（DNS/首字节/重定向）同样要能被打断。
     */
    fmt->interrupt_callback.callback = interruptCb;
    fmt->interrupt_callback.opaque = d;
    AVDictionary *opt = nullptr;
    av_dict_set(&opt, "timeout", "20000000", 0);        // 20s：网络源首字节慢是常态
    av_dict_set(&opt, "reconnect", "1", 0);
    /*
     * 读超时：没有它，一条死掉的 TCP 会让 av_read_frame 永久阻塞
     * （真机 HDR10 实测：读到第 12 个样本后计数两分钟不动，无任何报错）。
     * 设了之后读会失败返回，读循环能报错并重试。
     */
    av_dict_set(&opt, "rw_timeout", "15000000", 0);
    /*
     * 打开前把地址打出来（只留前 120 字符、api_key 遮掉）：
     * 用来一次分清"连的是本地中转还是 Emby 原地址"，这条以前完全看不到。
     */
    {
        char head[160];
        snprintf(head, sizeof(head), "%s", u);
        char *k = strstr(head, "api_key=");
        if (k) head[k - head + 8] = '\0';
        LOGI("解封装打开：url=%s", head);
    }
    int ret = avformat_open_input(&fmt, u, nullptr, &opt);
    av_dict_free(&opt);
    env->ReleaseStringUTFChars(url, u);
    if (ret < 0) {
        LOGE("打开失败: %s", errStr(ret).c_str());
        gConFail.fetch_add(1);
        d->opening.store(false);
        if (d->abortFlag) env->DeleteGlobalRef(d->abortFlag);
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
        d->opening.store(false);
        freeDemux(env, d);
        return 0;
    }
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        AVCodecParameters *p = fmt->streams[i]->codecpar;
        if (d->audioIdx < 0 && p->codec_type == AVMEDIA_TYPE_AUDIO) d->audioIdx = (int) i;
        /* 全包档要出画面：记下第一条视频轨（d->videoIdx 之前一直是 -1，
           nativeDemuxVideoCsd 因此恒返回 null —— 豆豆 2026-11-22 查出） */
        if (d->videoIdx < 0 && p->codec_type == AVMEDIA_TYPE_VIDEO) d->videoIdx = (int) i;
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
    LOGI("DBG|ffmpeg=%s", avformat_configuration());
    LOGI("DBG|discard_set=%d streams=%d", discardOn, (int) fmt->nb_streams);
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        AVStream *st = fmt->streams[i];
        if ((int) i == d->audioIdx) continue;
        /*
         * 视频轨：discardOn=0（全包档）才保留，否则照旧丢弃 ——
         * 保留的那一路是画面的唯一来源；丢弃的那一路只是"音频+字幕"的对照档。
         */
        if ((int) i == d->videoIdx) {
            st->discard = (discardOn != 0) ? AVDISCARD_ALL : AVDISCARD_DEFAULT;
            continue;
        }
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
        d->opening.store(false);
        freeDemux(env, d);
        return 0;
    }
    d->pkt = av_packet_alloc();
    if (!d->pkt) { d->opening.store(false); freeDemux(env, d); return 0; }

    for (int k = 0; k < d->subN; ++k) {
        AVCodecParameters *sp = fmt->streams[d->subIdx[k]]->codecpar;
        const AVCodec *dec = avcodec_find_decoder(sp->codec_id);
        if (!dec) continue;
        AVCodecContext *ctx = avcodec_alloc_context3(dec);
        /* 字幕解码器要 pkt_timebase 才会把包时间换算进 sub.pts（我们不用 sub.pts，但补上更稳） */
        if (ctx) ctx->pkt_timebase = fmt->streams[d->subIdx[k]]->time_base;
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

    /* 诊断：可 seek 性、seek 前后位置、换算后的目标——"读不出包"必须先分清是源不可 seek 还是换算错 */
    d->durMs = 0;
    if (fmt->duration != AV_NOPTS_VALUE && fmt->duration > 0) {
        d->durMs = fmt->duration / (AV_TIME_BASE / 1000);
    } else if (d->audioIdx >= 0) {
        AVStream *as0 = fmt->streams[d->audioIdx];
        if (as0->duration != AV_NOPTS_VALUE && as0->duration > 0) {
            d->durMs = (int64_t) (as0->duration * av_q2d(as0->time_base) * 1000.0);
            if (as0->start_time != AV_NOPTS_VALUE) {
                d->durMs -= (int64_t) (as0->start_time * av_q2d(as0->time_base) * 1000.0);
            }
        }
    }
    LOGI("DBG|url_seekable=%d|fmt_seekable=%d",
         fmt->pb ? (int) (fmt->pb->seekable & AVIO_SEEKABLE_NORMAL) : -1,
         (int) (fmt->ctx_flags & AVFMTCTX_UNSEEKABLE ? 0 : 1));

    d->openStartMs = startMs;
    LOGI("DBG|default_stream=%d", av_find_default_stream_index(fmt));
    if (startMs > 3000 && fmt->pb && !(fmt->pb->seekable & AVIO_SEEKABLE_NORMAL)) {
        LOGE("端点不可 seek，无法从 %lldms 开始 → 判打开失败", (long long) startMs);
        d->opening.store(false);
        freeDemux(env, d);
        return 0;
    }
    if (startMs > 0) {
        /*
         * 按**视频流**定位到关键帧，音频跟着它走。
         * 用音频流定位在 mkv 上粒度太粗：实测目标 221.7s 落到 203.6s（早 18 秒），
         * 首包校验直接判失败（2026-11-22 真机）。
         */
        int seekIdx = d->videoIdx >= 0 ? d->videoIdx : d->audioIdx;
        AVStream *as = fmt->streams[seekIdx];
        /* 目标必须换算到**该流的**时间基：用微秒当流时间基会飞出片尾 */
        /* startMs 本身就是毫秒：直接按 1/1000 → 流时间基换算（多乘 1000 会把落点放大成片尾） */
        int64_t tsSb = av_rescale_q(startMs, AVRational{1, 1000}, as->time_base);
        int64_t tsUs = (int64_t) startMs * 1000;
        int64_t before = fmt->pb ? avio_tell(fmt->pb) : -1;
        /*
         * 三级回退：先按音频流定位（最准），再退回带 stream 的旧接口，
         * 最后才是全局时间基（那一版在真机上返回 EPERM，留作最后证据）。
         */
        int r1 = avformat_seek_file(fmt, seekIdx, INT64_MIN, tsSb, tsSb, AVSEEK_FLAG_BACKWARD);
        LOGI("DBG|seekTry=1 ret=%d err=%s stream=%d tb=%d/%d target_ts=%lld",
             r1, errStr(r1).c_str(), seekIdx, as->time_base.num, as->time_base.den, (long long) tsSb);
        int r2 = 0, r3 = 0;
        if (r1 < 0) {
            r2 = av_seek_frame(fmt, seekIdx, tsSb, AVSEEK_FLAG_BACKWARD);
            LOGI("DBG|seekTry=2 ret=%d err=%s", r2, errStr(r2).c_str());
            if (r2 < 0) {
                r3 = avformat_seek_file(fmt, -1, INT64_MIN, tsUs, tsUs, AVSEEK_FLAG_BACKWARD);
                LOGI("DBG|seekTry=3 ret=%d err=%s", r3, errStr(r3).c_str());
            }
        }
        int64_t after = fmt->pb ? avio_tell(fmt->pb) : -1;
        LOGI("DBG|seek前=%lld|seek后=%lld", (long long) before, (long long) after);
        if (r1 < 0 && r2 < 0 && r3 < 0) {
            /*
             * 只是"不能从中间定位"，不是"文件不能播"：句柄照交，把状态告诉上层。
             * 判成打开失败会让上层以为这个文件坏了。
             */
            LOGE("三级 seek 全失败：不能从 %lldms 定位（文件本身可播）", (long long) startMs);
            d->seekFailed = true;
        }
        for (int k = 0; k < d->subN; ++k) {
            if (d->subDec[k]) avcodec_flush_buffers(d->subDec[k]);
        }
    } else if (fmt->pb && !(fmt->pb->seekable & AVIO_SEEKABLE_NORMAL)) {
        LOGE("端点不可 seek，只支持从头播");
    }
    d->opening.store(false);
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

    if (d->abort.load()) return -5;   // 被上层主动打断：不是网络错，也不是结尾
    while (true) {
        int ret = av_read_frame(d->fmt, d->pkt);
        if (ret == AVERROR_EOF) {
            LOGE("DBG|EOF ret=%d abort=%d avio_error=%d eof_reached=%d pos=%lld",
                 ret, d->abort.load() ? 1 : 0,
                 d->fmt->pb ? d->fmt->pb->error : 0,
                 d->fmt->pb ? d->fmt->pb->eof_reached : -1,
                 d->fmt->pb ? (long long) avio_tell(d->fmt->pb) : -1);
            /*
             * 一次 EOF 不算结尾：假性 EOF 的特征是"偶发一次、伴随网络错误"。
             * 连续三次、间隔 300ms 以上，才判真结尾（豆豆 2026-11-22）。
             */
            /*
             * 计数照常走：真结尾时本来就没有新数据，"必须先读到数据才计数"会把片尾永远挡掉。
             * 真结尾的主判据是"位置已贴近片尾"（duration），连击只是辅助。
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
                /* 第二道判据：位置必须已经贴近片尾；否则这是"网络抖动造成的连续 EOF"，只能等 */
                if (d->durMs > 0) {
                    if (d->lastAudioPtsMs >= 0 && d->lastAudioPtsMs >= d->durMs - 5000) {
                        LOGI("真 EOF|位置=%lldms 时长=%lldms", (long long) d->lastAudioPtsMs,
                             (long long) d->durMs);
                        return -1;
                    }
                    LOGI("连续 EOF 但位置=%lldms 距片尾 %lldms → 判等待（不是结尾）",
                         (long long) d->lastAudioPtsMs,
                         (long long) (d->durMs - d->lastAudioPtsMs));
                    d->eofStreak = 0;
                    return -2;
                }
                LOGI("连续三次读到结尾（无容器时长，按连击判 EOF）");
                return -1;
            }
            return -2;   // WAITING：上层等 300ms 再来
        }
        if (d->abort.load()) return -5;
        if (ret == AVERROR_EXIT) {
            /* 被打断 ≠ 读完 ≠ 网络错误：单独记，否则日志只是一句"致命" */
            LOGE("DBG|读包被中断（interrupt）abort=%d", d->abort.load() ? 1 : 0);
            return -3;
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
            if (d->pkt->pts != AV_NOPTS_VALUE) {
                d->lastAudioPtsMs = av_rescale_q(d->pkt->pts, st->time_base, AVRational{1, 1000});
                if (!d->firstAudioChecked) {
                    d->firstAudioChecked = true;
                    int64_t diff = d->lastAudioPtsMs - d->openStartMs;
                    /*
                     * 落点必然落在关键帧上（BACKWARD），提前量取决于 GOP（实测约 4 秒，最坏十几秒）。
                     * 提前 15 秒以内算正常；只有"比目标还晚"或"早得离谱"才是源没按 Range 生效。
                     */
                    /*
                     * 只警告、不判死：落点必然在关键帧上（BACKWARD），提前量由 GOP 决定，
                     * 实测这集 18 秒。真落点由 Session 的 onSeekOrigin 回灌，不需要这条判生死
                     * （豆豆 2026-11-22 P0-2）。
                     */
                    if (diff > 5000 || diff < -15000) {
                        LOGW("首包位置偏差=%lldms（目标=%lldms，实际=%lldms）→ 仅记录，不判失败",
                             (long long) diff, (long long) d->openStartMs,
                             (long long) d->lastAudioPtsMs);
                    } else {
                        LOGI("首包位置：目标=%lldms 实际=%lldms 偏差=%lldms",
                             (long long) d->openStartMs, (long long) d->lastAudioPtsMs, (long long) diff);
                    }
                }
            }
            m[2] = (jint) (d->pkt->pts == AV_NOPTS_VALUE ? -1
                    : av_rescale_q(d->pkt->pts, st->time_base, AVRational{1, 1000}));
            env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte *>(d->pkt->data));
            env->SetIntArrayRegion(meta, 0, 5, m);
            av_packet_unref(d->pkt);
            return size;
        }
        /*
         * 视频包：全包档把它交给画面解码线程。
         * meta[0]=1；meta[3]=关键帧标志（0/1）；pts 换算成毫秒，与音频同一口径。
         */
        if (d->videoIdx >= 0 && d->pkt->stream_index == d->videoIdx) {
            int size = d->pkt->size;
            if (size > cap) {
                /* 丢这一包继续读：丢一个关键帧最多黑到下一个关键帧，比"整部片播不了"轻得多 */
                LOGE("视频包超过输出缓冲 need=%d cap=%d → 丢这一包继续读（豆豆 2026-11-22）", size, cap);
                av_packet_unref(d->pkt);
                continue;
            }
            AVStream *vs = d->fmt->streams[d->videoIdx];
            m[0] = 1;
            m[1] = size;
            m[2] = (jint) (d->pkt->pts == AV_NOPTS_VALUE ? -1
                    : av_rescale_q(d->pkt->pts, vs->time_base, AVRational{1, 1000}));
            m[3] = (d->pkt->flags & AV_PKT_FLAG_KEY) ? 1 : 0;
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
                /*
                 * 字幕时间戳**一律用这个包自己的 pts**（在流时间基下，唯一可靠来源）。
                 *
                 * 不用 `sub.pts`：它在 ffmpeg 里是 AV_TIME_BASE（微秒），而我们其它地方按流时间基算；
                 * 混用会差 1000 倍（豆豆 2026-11-23）。而且 subrip 解码通常根本不填它（我们建解码器时
                 * 没设 pkt_timebase），一律当 -1 会让全部字幕挤到片头 —— 真机"包到了、屏上没有"就是这个。
                 */
                int64_t srcPts = d->pkt->pts;
                int64_t ptsMs = srcPts != AV_NOPTS_VALUE
                                ? av_rescale_q(srcPts, st->time_base, AVRational{1, 1000})
                                : -1;   // 仍然未知：不能当 0
                if (!text.empty() && (int) text.size() <= cap) {
                    m[0] = 2;
                    m[1] = (jint) text.size();
                    m[2] = (jint) ptsMs;
                    m[3] = subK;   // 哪一条字幕轨（切轨时按它挑缓冲）
                    /* 结束时间：AVSubtitle 给的是相对 start 的毫秒；缺失就用 0 让上层兜底 */
                    /* 取证：subrip/ass 到底给不给时长（2026-11-23 字幕闪烁） */
                    if (d->subDbg < 5) {
                        d->subDbg++;
                        LOGI("字幕时长取证: start_display=%d end_display=%d pts=%lld",
                             (int) sub.start_display_time, (int) sub.end_display_time,
                             (long long) srcPts);
                    }
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
    /* 与起播一致：先按音频流时间基定位，再退回两级 */
    int seekIdx = d->videoIdx >= 0 ? d->videoIdx : d->audioIdx;
    AVStream *as = d->fmt->streams[seekIdx];
    int64_t tsSb = av_rescale_q(ms, AVRational{1, 1000}, as->time_base);
    int64_t tsUs = (int64_t) ms * 1000;
    int ret = avformat_seek_file(d->fmt, seekIdx, INT64_MIN, tsSb, tsSb, AVSEEK_FLAG_BACKWARD);
    LOGI("DBG|jump seekTry=1 ret=%d err=%s", ret, errStr(ret).c_str());
    if (ret < 0) {
        ret = av_seek_frame(d->fmt, d->audioIdx, tsSb, AVSEEK_FLAG_BACKWARD);
        LOGI("DBG|jump seekTry=2 ret=%d err=%s", ret, errStr(ret).c_str());
    }
    if (ret < 0) {
        ret = avformat_seek_file(d->fmt, -1, INT64_MIN, tsUs, tsUs, AVSEEK_FLAG_BACKWARD);
        LOGI("DBG|jump seekTry=3 ret=%d err=%s", ret, errStr(ret).c_str());
    }
    if (ret < 0) {
        LOGE("跳转三级全失败: %s", errStr(ret).c_str());
        return ret;
    }
    for (int k = 0; k < d->subN; ++k) {
        if (d->subDec[k]) avcodec_flush_buffers(d->subDec[k]);
    }
    /*
     * 跳转后这些字段必须全部重置，否则：
     * ① firstAudioChecked 还是 true → 新位置的首包不校验，"读回文件头"这条防不住；
     * ② eofStreak / lastEofMs 残留 → 新位置几次 EOF 就凑满连击；
     * ③ lastAudioPtsMs 是旧位置 → 尾判据拿旧值比，必然不满足 → 判"等待" → 8 秒超时没声音。
     */
    d->openStartMs = ms;
    d->firstAudioChecked = false;
    d->eofStreak = 0;
    d->lastEofMs = 0;
    d->lastAudioPtsMs = -1;
    LOGI("DBG|seek ok target=%lld seek后=%lld", (long long) ms,
         (long long) (d->fmt->pb ? avio_tell(d->fmt->pb) : -1));
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
    /* 时长三级回退：容器 → 音频流 → 无（无时长时只能用 EOF 连击判据） */
    int64_t durMs = d->durMs;
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
    jint v[6] = {p->sample_rate, p->ch_layout.nb_channels, (jint) p->codec_id, d->subN,
                 (jint) durMs, d->seekFailed ? 1 : 0};
    env->SetIntArrayRegion(out, 0, 6, v);
    return 0;
}

/** 音频解码器名（"eac3"/"truehd"/"dca" 等），给自带解码器用 */
/**
 * 视频轨的设备参数：out[0]=宽 out[1]=高 out[2]=extradata 长度 out[3]=codec_id
 * 返回的 byte[] 就是 MediaCodec 的 csd（mkv 里是 hvcC，正是它要的格式）。
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxVideoCsd(
        JNIEnv *env, jobject, jlong handle, jintArray out) {
    Demux *d = fromHandle(handle);
    if (!d || !d->fmt || d->videoIdx < 0) return nullptr;
    AVCodecParameters *p = d->fmt->streams[d->videoIdx]->codecpar;
    jint v[4] = {p->width, p->height, p->extradata_size, (jint) p->codec_id};
    env->SetIntArrayRegion(out, 0, 4, v);
    if (!p->extradata || p->extradata_size <= 0) return nullptr;
    jbyteArray r = env->NewByteArray(p->extradata_size);
    env->SetByteArrayRegion(r, 0, p->extradata_size, (const jbyte *) p->extradata);
    return r;
}

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

/*
 * 字幕头的原始文本（ASS/SSA 的 [Script Info] / [V4+ Styles] 段就在里面）。
 * Java 侧解析样式表用；取不到返回 null。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxSubtitleHeader(
    JNIEnv *env, jobject, jlong handle, jint k) {
    Demux *d = reinterpret_cast<Demux *>(handle);
    if (!d || k < 0 || k >= d->subN) return nullptr;
    AVCodecParameters *sp = d->fmt->streams[d->subIdx[k]]->codecpar;
    if (!sp->extradata || sp->extradata_size <= 0) return nullptr;
    /* extradata 不是以 0 结尾的，按长度造一条 Java 字符串 */
    jbyteArray arr = env->NewByteArray(sp->extradata_size);
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, sp->extradata_size, reinterpret_cast<const jbyte *>(sp->extradata));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring cs = env->NewStringUTF("UTF-8");
    jobject out = env->NewObject(strCls, ctor, arr, cs);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(cs);
    return static_cast<jstring>(out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_player_AudioDemuxNative_nativeDemuxClose(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) {
        gConDupe.fetch_add(1);
        LOGI("重复关闭（handle=0）→ 只记数，不重复减");
        return;
    }
    freeDemux(env, fromHandle(handle));
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
