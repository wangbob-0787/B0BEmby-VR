package com.xxxx.emby_vr.player

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mpv 解码内核（父亲 2026-10-08）。
 *
 * ### 为什么要有它
 * 系统解码器（MediaCodec / Media3）吃不下杜比视界的 H.265 流：装了《挑情丑闻》
 * 只出声音不出画面。而 PICO 上 4XVR 能正常播、颜色也对 —— 说明片源、网盘、网络
 * 都没问题，差在客户端解码能力。4XVR 的做法是自带解码内核（它包里就是 ffmpeg）。
 * 这里走同一条路：libmpv（FFmpeg 驱动，包 `dev.jdtech.mpv:libmpv:0.4.1`）。
 *
 * ### 接入方式
 * 和 ExoPlayer 一样，我们只给它一块 Surface —— 那块 Surface 背后是原生层的 OES
 * 纹理，最终贴到 VR 银幕上。所以接法就是 `attachSurface(同一个 surface)`。
 *
 * ### 与 ExoPlayer 的分工
 * 默认仍走 ExoPlayer（性能好、字幕与弹幕的时间轴现成）。只有系统解码器明确
 * 吃不下（杜比视界）时，才切到这条内核。
 *
 * 注意：MPVLib 是静态单例（native 层一份实例），本类只做参数与命令封装。
 */
class MpvBackend(private val context: Context) {

    companion object {
        private const val TAG = "B0BEmbyVR-Mpv"

        /** 是否已初始化过（MPVLib 是静态的，重复 create 会重复建实例） */
        private val created = AtomicBoolean(false)

        /**
         * 运行时改内核属性（调参用，父亲 2026-10-08）。
         *
         * 参数名走 vr-tuning.txt 的 `mpv_<属性>` 行，例如 `mpv_tone-mapping=spline`。
         * 内核没起来时设置会失败，只记日志不抛。
         */
        fun setOptionRuntime(name: String, value: String) {
            try {
                MPVLib.setPropertyString(name, value)
                Log.i(TAG, "内核属性 → $name=$value")
            } catch (t: Throwable) {
                Log.w(TAG, "内核属性设置失败 $name=$value：${t.message}")
            }
        }

        /**
         * 执行一条内核命令（调参文件里写 `cmd=screenshot-to-file <路径> video`）。
         *
         * 用途：把内核渲染后的画面原样导出成文件。校色必须用内核渲染结果当标尺，
         * 头显整屏截图带双眼畸变、裁不出可用样本（父亲 2026-10-09 定）。
         */
        fun commandRuntime(args: List<String>) {
            try {
                MPVLib.command(args.toTypedArray())
                Log.i(TAG, "内核命令 → ${args.joinToString(" ")}")
            } catch (t: Throwable) {
                Log.w(TAG, "内核命令失败 ${args.joinToString(" ")}：${t.message}")
            }
        }

        /**
         * 初始化内核。幂等，重复调用直接返回。
         */
        fun ensureCreated(context: Context) {
            if (created.compareAndSet(false, true)) {
                try {
                    MPVLib.create(context.applicationContext)
                    /*
                     * 画面输出与色彩还原（2026-10-08 第二次调整，父亲拍板）。
                     *
                     * 前一版用 `vo=mediacodec_embed` + 硬解：画面正常、4K 流畅，但杜比视界
                     * 偏色 —— 硬解把画面转成普通色彩送出来，杜比视界那层私有色彩编码
                     * （Profile 5 的 IPTPQc2）在解码器出口就已经丢了，后面谁也救不回来。
                     *
                     * 资料结论（mpv 官方仓库 mpv-android #1081，维护者原话）：
                     * 「硬解 + 杜比视界色彩还原」这条路不存在；唯一正解是**软解 + gpu-next**，
                     * 由 gpu-next 背后的 libplacebo 做杜比视界的色彩还原。
                     * 我们这包 libmpv 里带着 libplacebo 与还原函数（pl_shader_dovi_reshape /
                     * pl_hdr_metadata_from_dovi_rpu），零件齐，只差把它用起来。
                     *
                     * 前一版试过 `vo=gpu` + 软解 → 整屏粉/蓝纯色闪。差别在于：
                     * 老渲染器 `gpu` 不做杜比视界还原，且渲染上下文没配对。
                     * 现在按资料给的可用组合来：新渲染器 + OpenGL + android 上下文。
                     */
                    MPVLib.setOptionString("vo", "gpu-next")
                    MPVLib.setOptionString("gpu-context", "android")
                    MPVLib.setOptionString("gpu-api", "opengl")
                    /*
                     * 硬解（父亲 2026-10-08 实测对比后定的方向）。
                     *
                     * 实测数据：同一部 4K 杜比视界片子，4XVR 只占 0.24 个核、画面流畅；
                     * 我们软解占 2.6 个核、只有 6 帧，整个 VR 场景都跟着抖。
                     * 4XVR 装着整套 ffmpeg 却不吃 CPU，说明视频是系统硬解器解的。
                     * 又查了系统的解码器配置：里面**没有任何杜比视界声明** ——
                     * 所以它是硬解之后自己把颜色算回来的。
                     *
                     * 我们前面只试过两种组合：硬解 + 不做处理（偏色）、软解 + 处理（太慢）。
                     * 「硬解 + 内核做色彩还原」没试过，这一版试它。
                     */
                    /*
                     * 硬解输出路径 —— mediacodec-copy（父亲 2026-10-08 晚实测定档，重要）。
                     *
                     * 原来用 `mediacodec`（零拷贝）：解码器解好的 4K 画面直接给渲染采样，
                     * 两边**共用同一块画面缓冲、没有任何协调**。解码器是自上而下逐行写下一帧的，
                     * 渲染/合成正好读到只写了一半的那块时，画面上就出现横向的、还没写完的条带
                     * —— 这就是父亲报了整晚的「整个场景的黑色细横纹」。
                     *
                     * 现象全部吻合：位置在场景层不在视频层（共享缓冲被两端同时读写）、
                     * 横向（按行写）、间歇且与晃动无关（取决于两条活儿的相对速度）、
                     * 画面内容与帧率都正常（读到的永远是合法像素，只是新旧混着）、
                     * 内录按帧采样多半错过这个中间状态、4XVR 不走这条共享路径所以没有。
                     *
                     * 换成 `mediacodec-copy`：解出来先完整拷贝一份再交给渲染，
                     * 写与读彻底分开。实测黑纹消失，帧率仍满 72Hz（每 3 秒 216 帧），
                     * CPU 约七成 —— 多一次拷贝扛得住。
                     *
                     * 试过但无效：glFlush（run 303）、glFinish（run 304 同步档）、
                     * 降画质到 720p、关视频独立层、加大内核缓冲、换刷新率档。
                     */
                    MPVLib.setOptionString("hwdec", "mediacodec-copy")
                    /*
                     * 关掉 ffmpeg 的直出渲染（direct rendering）。
                     * 杜比视界的 RPU 元数据挂在帧的附加数据上，直出模式下会被丢掉，
                     * 丢了就还原不了色彩。
                     */
                    MPVLib.setOptionString("vd-lavc-dr", "no")
                    /*
                     * 目标是 SDR：我们最终把画面贴到 VR 银幕上，那条链路是普通 SDR 输出。
                     * 所以让 libplacebo 把 HDR / 杜比视界老老实实映射到 bt.709 + gamma2.2，
                     * 别去暗示显示端切 HDR（前一版设过 yes，在 VR 这层没有意义还可能添乱）。
                     */
                    MPVLib.setOptionString("target-colorspace-hint", "no")
                    MPVLib.setOptionString("target-prim", "bt.709")
                    MPVLib.setOptionString("target-trc", "gamma2.2")
                    MPVLib.setOptionString("tone-mapping", "bt.2390")
                    /*
                     * 关键：关掉 HDR 逐帧峰值检测。
                     * 默认 auto 对 HDR 源会逐帧统计画面峰值，色调映射曲线跟着每帧变，
                     * 整幅画面就一会暖一会冷地"呼吸"。片源本身的杜比参数是恒定的
                     * （实测 9 个采样帧 DM-id 全 0、场景刷新全 0），所以抖动来自这里。
                     * 关掉后曲线固定，颜色全程稳定。
                     */
                    MPVLib.setOptionString("hdr-compute-peak", "no")
                    /*
                     * 内核快速档（2026-10-09 黑纹攻坚的收官手段）。
                     *
                     * 杜比还原那条链每帧吃 10~22ms GPU，是我们唯一真正吃紧的一环
                     * （我们自己的渲染只花 0.9ms）。它一旦顶出帧预算，运行时就用上一帧
                     * 做变形补偿，画面出现多条横向跳动的黑纹（详见台账 2026-10-09）。
                     *
                     * 快速档省掉一批**可选**的画质处理（去色带/抖动/高质量缩放等），
                     * 实测把 GPU 从 5.4~12.2ms 压到 3.7~4.9ms、迟到帧归零；
                     * 父亲实测确认**画面质量没有变化**。余量留着，设备烧热后也不会越界。
                     *
                     * 想还原画质可运行 `mpv_profile=` 清掉这一档。
                     */
                    MPVLib.setOptionString("profile", "fast")
                    /*
                     * 字幕交给**内核自己渲染**（2026-10-09 父亲拍板，Profile 5 专用结论）。
                     *
                     * 起因：Profile 5 走直链播放、Emby 不推流，服务端**根本没打开过那个文件**，
                     * 所以它的字幕接口对这些片子完全不理（实测：P5 那片 2/3/5 号字幕全部
                     * 25 秒超时零字节；同一台服务器上 8.1 那片 0.008 秒就返回）。
                     * 我们原来那套"从 Emby 取字幕后自绘"在 P5 上根本拿不到数据。
                     *
                     * 而内核手里就握着那个文件、内封字幕轨它自己就能读，还自带 libass 排版
                     * （`{\an8}` 这类 ASS 标记它认识，不会像我们那样画成乱字符）。
                     * 所以 P5 这条路：字幕由 mpv 直接画进画面，我们不再自绘。
                     *
                     * `sub-auto=no` 保留：只禁"自动挑一条"，不禁止手动选（选轨走 sid）。
                     * 起始不选任何字幕（sid 留空 = 由 mpv 按内封默认决定，通常是不显示）。
                     */
                    MPVLib.setOptionString("sub-auto", "no")
                    // 静音状态由我们控制，先不静音
                    MPVLib.setOptionString("mute", "no")
                    /*
                     * 性能：软解 4K 实测只有 6 帧（父亲 2026-10-08 报「卡」之前的日志
                     * 已经写着 display FPS 5.997）。先把对观感帮助小、开销大的几项关掉：
                     * 抖动、去色带；缩放走双线性（4K 缩到银幕本来就要重采样）。
                     * 解码线程交给内核按核数自己分配。
                     */
                    MPVLib.setOptionString("dither-depth", "no")
                    MPVLib.setOptionString("deband", "no")
                    MPVLib.setOptionString("scale", "bilinear")
                    MPVLib.setOptionString("cscale", "bilinear")
                    MPVLib.setOptionString("vd-lavc-threads", "0")
                    /*
                     * 调参文件里的内核选项放最后应用 —— 覆盖上面的默认值
                     * （父亲 2026-10-08：戴着调参不用重编）。文件没有或读不到时什么都不做。
                     */
                    com.xxxx.emby_vr.vr.VrTuning.mpvOptions(context).forEach { (k, v) ->
                        try {
                            MPVLib.setOptionString(k, v)
                            Log.i(TAG, "调参覆盖内核选项 $k=$v")
                        } catch (t: Throwable) {
                            Log.w(TAG, "调参覆盖失败 $k=$v：${t.message}")
                        }
                    }
                    // 把内核自己的日志接到 logcat：出问题时能直接看它内部报什么
                    MPVLib.addLogObserver { prefix, level, text ->
                        Log.i(TAG, "mpv[$level] ${prefix ?: ""}$text")
                    }
                    MPVLib.init()
                    Log.i(TAG, "mpv 内核已就绪（gpu-next + libplacebo，软解，杜比视界 Profile 5）")
                } catch (t: Throwable) {
                    created.set(false)
                    Log.e(TAG, "mpv 内核初始化失败: ${t.message}")
                }
            }
        }
    }

    private var surfaceAttached = false

    /**
     * 把画面输出接到这块 Surface（背后是原生层 OES 纹理 → VR 银幕）。
     */
    fun attachSurface(surface: Surface?) {
        ensureCreated(context)
        try {
            if (surface == null) {
                MPVLib.detachSurface()
                surfaceAttached = false
            } else {
                MPVLib.attachSurface(surface)
                surfaceAttached = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "接画面输出失败: ${t.message}")
        }
    }

    /**
     * 播一个地址。[startSec] 是起播位置（秒）。
     */
    fun play(url: String, startSec: Double = 0.0) {
        ensureCreated(context)
        try {
            MPVLib.command(arrayOf("loadfile", url, "replace"))
            if (startSec > 0.5) {
                // 起播位置：% 是百分比定位，秒数绝对定位更稳
                MPVLib.command(arrayOf("seek", startSec.toString(), "absolute+exact"))
            }
            setPaused(false)
        } catch (t: Throwable) {
            Log.e(TAG, "起播失败: ${t.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        try { MPVLib.setPropertyBoolean("pause", paused) } catch (_: Throwable) {}
    }

    fun isPaused(): Boolean = try { MPVLib.getPropertyBoolean("pause") ?: false } catch (_: Throwable) { false }

    /** 播放位置（秒） */
    fun positionSec(): Double = try { MPVLib.getPropertyDouble("time-pos") ?: 0.0 } catch (_: Throwable) { 0.0 }

    /** 总时长（秒） */
    fun durationSec(): Double = try { MPVLib.getPropertyDouble("duration") ?: 0.0 } catch (_: Throwable) { 0.0 }

    fun seekToSec(sec: Double) {
        try { MPVLib.command(arrayOf("seek", sec.toString(), "absolute")) } catch (_: Throwable) {}
    }

    fun setSpeed(speed: Float) {
        try { MPVLib.setPropertyDouble("speed", speed.toDouble()) } catch (_: Throwable) {}
    }

    /**
     * 告诉内核「画布多大」（父亲 2026-10-08）。
     *
     * 这是本轮画面变成纯色块的根因：我们的画面纹理从来没有设置过缓冲尺寸。
     * 硬解时解码器自己按视频尺寸设，所以一直没暴露；换成内核自己渲染之后，
     * 内核按默认尺寸画，屏幕上就是整屏拉伸的色块。
     *
     * 这里给内核报尺寸，配合 VrRenderer.setVideoBufferSize 给纹理设缓冲尺寸，
     * 两边一致才画得对。
     */
    fun setSurfaceSize(w: Int, h: Int) {
        if (w < 64 || h < 64) return
        try {
            MPVLib.setPropertyString("android-surface-size", "${w}x$h")
            Log.i(TAG, "内核画布尺寸 → ${w}x$h")
        } catch (t: Throwable) {
            Log.w(TAG, "内核画布尺寸设置失败: ${t.message}")
        }
    }

    /**
     * 切音轨（父亲 2026-10-08：内核模式下换音轨这一版要生效）。
     *
     * 换音轨不该牵动播放本身 —— 前一版走「重起播」，踩了 native 层的 stale Global 崩溃
     * （SIGABRT + attempt to use stale Global）。内核模式改用 mpv 自己的音轨选择。
     *
     * Emby 给的是「文件内的流序号」，mpv 的 aid 是它自己的 track id，两者不一定相等，
     * 所以按顺序映射：取内核侧音频轨列表的第 ordinal 个。
     */
    fun setAudioTrackByOrdinal(ordinal: Int): Boolean {
        val ids = audioTrackIds()
        if (ids.isEmpty()) {
            Log.w(TAG, "内核没有报出音轨列表，切轨失败")
            return false
        }
        val id = ids.getOrNull(ordinal)
        if (id == null) {
            Log.w(TAG, "音轨序号 $ordinal 超出范围（内核侧共 ${ids.size} 条）")
            return false
        }
        return try {
            MPVLib.setPropertyInt("aid", id)
            Log.i(TAG, "内核切音轨：第 ${ordinal + 1} 条（aid=$id）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "内核切音轨失败: ${t.message}")
            false
        }
    }

    /**
     * 内核侧的字幕轨 id 列表（按文件内顺序）；取不到返回空表。
     *
     * 与音轨同一套做法：Emby 的字幕清单里第几条 → 内核侧第几条，
     * 序号映射靠"两边都是文件内顺序"这个前提（外挂弹幕轨不在内核清单里）。
     */
    fun subtitleTrackIds(): List<Int> {
        val raw = try { MPVLib.getPropertyString("track-list") } catch (_: Throwable) { null }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "subtitle") out.add(o.optInt("id"))
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "解析内核字幕列表失败: ${t.message}")
            emptyList()
        }
    }

    /**
     * 按序号选字幕（内核自渲染）。
     *
     * ordinal = 在 Emby 字幕清单（已排除弹幕轨）里的下标；null 或越界 = 关字幕。
     * 为什么改成走内核：见上面 `sub-auto` 那段的注释（P5 直链播放时 Emby 取不到字幕）。
     */
    fun setSubtitleByOrdinal(ordinal: Int?): Boolean {
        if (ordinal == null) {
            return try {
                MPVLib.setPropertyString("sid", "no")
                Log.i(TAG, "内核：字幕关闭（sid=no）")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "内核关字幕失败: ${t.message}")
                false
            }
        }
        val ids = subtitleTrackIds()
        if (ids.isEmpty()) {
            Log.w(TAG, "内核没有报出字幕轨，选字幕失败（文件里可能没有内封字幕）")
            return false
        }
        val id = ids.getOrNull(ordinal)
        if (id == null) {
            Log.w(TAG, "字幕序号 $ordinal 超出范围（内核侧共 ${ids.size} 条）")
            return false
        }
        return try {
            MPVLib.setPropertyInt("sid", id)
            Log.i(TAG, "内核选字幕：第 ${ordinal + 1} 条（sid=$id，共 ${ids.size} 条）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "内核选字幕失败: ${t.message}")
            false
        }
    }

    /** 内核侧的音轨 id 列表（按文件内顺序）；取不到返回空表 */
    fun audioTrackIds(): List<Int> {
        val raw = try { MPVLib.getPropertyString("track-list") } catch (_: Throwable) { null }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "audio") out.add(o.optInt("id"))
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "解析内核音轨列表失败: ${t.message}")
            emptyList()
        }
    }

    /** 起播后把内核侧轨道清单打进日志：切轨排错靠它 */
    fun dumpTracks() {
        val raw = try { MPVLib.getPropertyString("track-list") } catch (_: Throwable) { null }
        Log.i(TAG, "内核轨道清单：${raw ?: "取不到"}")
    }

    /**
     * 起播后把内核认到的画面参数打进日志（父亲 2026-10-08）。
     *
     * 颜色对不对，先看内核自己认到了什么：色彩范围、传输曲线、是否认出杜比视界、
     * 用的是不是硬解。这些一行日志就能定性，省得靠猜。
     */
    fun dumpVideoParams() {
        fun p(k: String): String = try {
            MPVLib.getPropertyString(k) ?: "-"
        } catch (_: Throwable) {
            "-"
        }
        Log.i(TAG, "内核画面参数：格式=${p("video-format")} 解码=${p("hwdec-current")} " +
            "编码=${p("video-codec")} 帧率=${p("container-fps")}")
        Log.i(TAG, "内核色彩参数：${p("video-params").replace("\n", " ")}")
    }

    /** 是否已经吃进流、开始出画 */
    fun hasVideo(): Boolean = try { MPVLib.getPropertyBoolean("video") ?: false } catch (_: Throwable) { false }

    fun stop() {
        try { MPVLib.command(arrayOf("stop")) } catch (_: Throwable) {}
        try {
            MPVLib.detachSurface()
            surfaceAttached = false
        } catch (_: Throwable) {}
    }
}
