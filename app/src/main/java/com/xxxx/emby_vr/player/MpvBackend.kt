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
                    MPVLib.setOptionString("hwdec", "mediacodec")
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
                    // 字幕我们自己画（弹幕层带字幕位），别让 mpv 再画一遍
                    MPVLib.setOptionString("sub-auto", "no")
                    MPVLib.setOptionString("sid", "no")
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
