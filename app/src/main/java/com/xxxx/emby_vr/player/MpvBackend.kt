package com.xxxx.emby_vr.player

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.Executors
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

        /*
         * ── 属性缓存（2026-10-11，父亲报的「控制条点不动、海报墙拖不动」的根因修复）──
         *
         * mpv 的属性读取是**同步**的：`mpv_get_property` 把取值派给 mpv 自己的核心
         * 线程去执行，核心线程正在做网络 I/O（解复用器读流、ffmpeg 重连）时，
         * 这个读会一直等 —— 实测能等几十秒。
         *
         * 我们原来在**界面线程**上直接读：位置、时长、字幕文字、轨道清单，上一版
         * 又给进度条加了「缓冲点」（`demuxer-cache-time`）。这个属性恰好要在解复用器
         * 的锁上取，片源网络一抖界面线程就被按住几十秒 —— 表现就是控制条按钮点不动、
         * 菜单出不来、海报墙拖不动，等它松手时积压的点击一起生效（父亲实测：点了
         * 海报 46 秒后才换片）。之前几版没有这一条读，所以没这个毛病。
         *
         * 现在所有读取都在这条后台线程上做，界面侧只读下面的缓存变量，永不阻塞。
         */
        @Volatile private var cTimePos = 0.0
        @Volatile private var cDuration = 0.0
        @Volatile private var cCacheTime = 0.0
        @Volatile private var cVideoW = 0
        @Volatile private var cSubText = ""
        @Volatile private var cEof = false
        @Volatile private var cPaused = false
        @Volatile private var cTrackList = ""
        @Volatile private var cHasVideo = false

        /** 有没有在播：没播时不轮询（属性读不到会刷错误日志） */
        @Volatile private var polling = false

        private val pollerStarted = AtomicBoolean(false)

        /**
         * mpv 调用线程（2026-10-11，父亲实测《挑情丑闻》选字幕卡死 60 秒的修复）。
         *
         * 所有**改** mpv 的调用都排到这条线程上执行，界面线程一次都不碰 mpv。
         * 起因（logcat 硬证据）：菜单里点一条字幕 → `sub-add <Emby 字幕地址>`，
         * mpv 同步去打开那个地址；Emby 对直连播放的片子取不出字幕，ffmpeg 在
         * 网络读上等满 60 秒才报 Failed to open —— 这 60 秒里界面线程被按死，
         * 控制条点不动、海报墙拖不动，松手后积压的点击一起生效（日志里那一刻
         * 一堆"控制条按压被丢弃"同毫秒涌出）。
         *
         * 单线程是为了保序（play 的 start 选项必须先于 loadfile 生效）。
         */
        private val mpvExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mpv-cmd").also { it.isDaemon = true }
        }

        /** 把一次 mpv 调用排到 mpv 线程上（界面线程立即返回） */
        private fun post(block: () -> Unit) {
            runCatching { mpvExec.execute { runCatching(block) } }
        }

        /**
         * 外挂字幕加载单独一条线程。
         *
         * 原因：`sub-add <地址>` 可能在里面卡满 mpv 自己的网络超时（默认 60 秒）。
         * 跟其它命令共用一条线程的话，这 60 秒里用户的快进/暂停也得排在后面等 ——
         * 界面不卡了，但操作还是迟钝。分开之后它只拖住自己。
         */
        private val subExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mpv-sub").also { it.isDaemon = true }
        }


        /**
         * 两条后台轮询线程。
         *
         * 为什么分两条：`demuxer-cache-time` 是已知会长时间阻塞的那一个（它要在
         * 解复用器的锁上取值，解复用器正在读流时读它要等）。把它和位置/字幕放在
         * 同一条线程上，网络一抖位置也跟着冻住 —— 进度条就卡住了。分开之后，
         * 它最多拖住自己那条。
         */
        private fun startPropertyPoller() {
            if (!pollerStarted.compareAndSet(false, true)) return
            startPollThread("mpv-props", 100L, 400L, ::readFastProps)
            startPollThread("mpv-cache", 500L, 500L, ::readSlowProps)
        }

        private fun startPollThread(
            name: String,
            fastMs: Long,
            slowMs: Long,
            body: (Long) -> Boolean,
        ) {
            val t = Thread({
                var tick = 0L
                while (true) {
                    var ok = true
                    if (polling) {
                        ok = body(++tick)
                    } else {
                        tick = 0
                    }
                    // 读不到（文件还没打开）就放慢，别把日志刷满
                    try {
                        Thread.sleep(if (ok) fastMs else slowMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, name)
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY + 1
            t.start()
        }

        /** 便宜且随时可读的那几项：位置、字幕文字、暂停、播完、出画尺寸 */
        private fun readFastProps(tick: Long): Boolean {
            var ok = true
            fun d(name: String, apply: (Double) -> Unit) {
                val v = try { MPVLib.getPropertyDouble(name) } catch (_: Throwable) { null }
                if (v == null) ok = false else apply(v)
            }
            fun b(name: String, apply: (Boolean) -> Unit) {
                val v = try { MPVLib.getPropertyBoolean(name) } catch (_: Throwable) { null }
                if (v == null) ok = false else apply(v)
            }
            fun s(name: String, apply: (String) -> Unit) {
                val v = try { MPVLib.getPropertyString(name) } catch (_: Throwable) { null }
                if (v == null) ok = false else apply(v)
            }
            d("time-pos") { cTimePos = it }
            s("sub-text") { cSubText = it }
            b("pause") { cPaused = it }
            b("eof-reached") { cEof = it }
            b("video") { cHasVideo = it }
            if (tick % 2L == 1L) {
                val w = try { MPVLib.getPropertyInt("video-params/w") } catch (_: Throwable) { null }
                if (w == null) ok = false else cVideoW = w
            }
            return ok
        }

        /** 可能长时间阻塞的那几项：缓冲点、总时长、轨道清单 */
        private fun readSlowProps(tick: Long): Boolean {
            var ok = true
            val cached = try { MPVLib.getPropertyDouble("demuxer-cache-time") } catch (_: Throwable) { null }
            if (cached == null) ok = false else cCacheTime = cached
            if (tick % 2L == 0L) {
                val d = try { MPVLib.getPropertyDouble("duration") } catch (_: Throwable) { null }
                if (d == null) ok = false else cDuration = d
            }
            if (tick % 4L == 1L) {
                val tl = try { MPVLib.getPropertyString("track-list") } catch (_: Throwable) { null }
                if (tl == null) ok = false else cTrackList = tl
            }
            return ok
        }

        /** 起播：清缓存并开始轮询 */
        private fun beginPolling() {
            cTimePos = 0.0
            cDuration = 0.0
            cCacheTime = 0.0
            cVideoW = 0
            cSubText = ""
            cEof = false
            cTrackList = ""
            cHasVideo = false
            polling = true
        }

        /** 停止播放：不再轮询（属性不可读时读取会刷错误日志） */
        private fun endPolling() {
            polling = false
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
                    /*
                     * 内核**默认自己画**字幕（2026-10-09 父亲实测后定）。
                     *
                     * 文字字幕它画不出来 —— 这台设备上 libass 找不到字体（日志实证
                     * `sub/assfontselect: failed to find any fallback with glyph 0x0`，
                     * 截图核对银幕底部一个字都没有），所以文字那类改由 Java 侧取文自绘。
                     * 但**图形字幕（PGS/VobSub）内核画得了**：那是图片，不需要字体。
                     *
                     * 于是这里保持默认（能画），运行时再按实际情况收：
                     * Java 侧一旦从 `sub-text` 读到文字（说明选中了文字轨），
                     * 就把 sub-visibility 关掉，免得两边都画、字幕重影。
                     */
                    MPVLib.setOptionString("sub-visibility", "yes")
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
                    // 属性轮询线程：所有属性读取都归它，界面线程只读缓存（见上方属性缓存注释）
                    startPropertyPoller()
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
    /** 每次 loadfile 的序号：给延迟补跳用，避免上一部片排的回调把新片跳歪 */
    private var loadGen = 0

    fun play(url: String, startSec: Double = 0.0) {
        ensureCreated(context)
        beginPolling()
        loadGen += 1
        val gen = loadGen
        try {
            /*
             * 续播位置必须在**加载文件之前**交代下去（父亲 2026-10-09：
             * 「一切图片字幕就从零开始」）。
             *
             * 原因：loadfile 是异步的 —— 文件还没打开，紧跟其后的 seek 命令会被丢掉，
             * 于是内核永远从片头起播。正确做法是用 mpv 的 start 选项（对下一个加载的
             * 文件生效），另外再排一次延迟补跳兜底（万一 start 没被认）。
             */
            if (startSec > 0.5) {
                post { MPVLib.setPropertyString("start", "+" + startSec.toInt()) }
            }
            post { MPVLib.command(arrayOf("loadfile", url, "replace")) }
            if (startSec > 0.5) {
                val want = startSec
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (gen != loadGen) return@postDelayed        // 已经换片了，别动新片
                    // 走缓存（界面线程不碰 mpv 属性读取，见 companion 里的属性缓存注释）
                    val pos = positionSec()
                    if (pos < want - 5.0) {
                        post {
                            MPVLib.command(arrayOf("seek", want.toString(), "absolute+exact"))
                        }
                        Log.i(TAG, "内核起播位置补跳 → ${want.toInt()}s（当时 ${pos.toInt()}s）")
                    } else {
                        Log.i(TAG, "内核起播位置已对：${pos.toInt()}s")
                    }
                }, 1500L)
            }
            setPaused(false)
        } catch (t: Throwable) {
            Log.e(TAG, "起播失败: ${t.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        // 顺手更新缓存：界面按了暂停键立刻读 isPaused() 也要拿到新值，别等下一轮轮询
        cPaused = paused
        post { MPVLib.setPropertyBoolean("pause", paused) }
    }

    fun isPaused(): Boolean = cPaused

    /** 播放位置（秒） */
    /**
     * 当前这句内嵌字幕的纯文本（Profile 5 用，2026-10-09）。
     *
     * 内核自己画不出来（找不到字体），但文字本身它有 —— `sub-text` 返回的是当前播放位置上
     * 那句字幕的纯文本，不带 ASS 标记（`{\\an8}` 这类由内核剥掉），且不受 sub-visibility 影响。
     * 取不到（没选轨、此刻没有台词、属性不可读）时返回空串。
     */
    /** 收 / 放内核自己那层字幕（文字字幕由我们自绘，图形字幕留给内核画）。 */
    fun setSubVisible(visible: Boolean) {
        post { MPVLib.setPropertyString("sub-visibility", if (visible) "yes" else "no") }
    }

    /**
     * 内核真的解出画面了吗（父亲 2026-10-09：换片时黑幕过早收掉，露出上一部的画面）。
     *
     * 判据用 `video-params/w`：内核拿到第一个视频帧、认出画面尺寸之后才有值；
     * 没起播前它是空的（日志里那排 `格式=- 解码=-` 就是这个阶段）。
     * 比"原生纹理来了一帧"可靠 —— 换片瞬间纹理层里还留着上一部的旧帧。
     */
    fun videoReady(): Boolean = cVideoW > 0

    fun currentSubtitleText(): String = cSubText

    fun positionSec(): Double = cTimePos

    /** 总时长（秒） */
    fun durationSec(): Double = cDuration

    /**
     * 内核已经缓存到的时间轴位置（秒）—— 进度条上那段"比播放进度更长"的缓冲靠它。
     *
     * mpv 的 `demuxer-cache-time` 就是解复用器当前缓存到哪一秒；读不到（本地文件很快、
     * 或还没开始缓存）返回 0，调用方退回"当前位置"。
     *
     * 起因：父亲 2026-10-11 报内核这条路进度条上看不到任何缓冲、改档位也没变化 ——
     * 原来是 PlaybackControl.bufferedMs() 在内核路径直接返回了当前位置。
     *
     * 注意：这个属性**必须**走后台线程读（父亲 2026-10-11 实测：在界面线程上读它，
     * 片源网络一抖界面就卡住几十秒，控制条点不动）。这里只返回缓存值。
     */
    fun cachedTimeSec(): Double = cCacheTime

    fun seekToSec(sec: Double) {
        post { MPVLib.command(arrayOf("seek", sec.toString(), "absolute")) }
    }

    /**
     * 内核的缓冲档位（父亲 2026-10-09：控制条上的功能在 mpv 这条路上也要能用）。
     *
     * 系统播放器那边配的是 LoadControl 的缓冲上下限；内核走 mpv 自己的缓存策略，
     * 所以这里把"档位"翻译成 mpv 的两个属性：
     *  · cache-secs       —— 前后缓存多少秒（网络流靠它扛抖动）；
     *  · demuxer-max-bytes —— 解复用器最多缓存多少字节。
     * 运行中改这两个属性 mpv 会立刻生效，不用重起播。
     */
    fun applyBufferPreset(seconds: Int, maxBytesMb: Int) {
        post {
            MPVLib.setPropertyInt("cache-secs", seconds.coerceIn(5, 600))
            MPVLib.setPropertyInt("demuxer-max-bytes", (maxBytesMb.coerceIn(16, 2048)) * 1024 * 1024)
            Log.i(TAG, "内核缓冲档位 → cache-secs=${seconds}s 上限=${maxBytesMb}MB")
        }
    }

    /**
     * 播放模式里的"循环"（父亲 2026-10-09：控制条功能在内核这条路上也要能用）。
     *
     * 系统播放器那边设的是 ExoPlayer 的 repeatMode；内核要用 mpv 的 loop-file：
     *  · 列表循环 / 单集循环 → 单集本身循环（与系统播放器那边对单个片的语义一致）；
     *  · 播完停止 → 不循环，由界面在播完时收场。
     */
    fun setLoopFile(loop: Boolean) {
        post {
            MPVLib.setPropertyString("loop-file", if (loop) "inf" else "no")
            Log.i(TAG, "内核循环 → ${if (loop) "开" else "关"}")
        }
    }

    /** 内核有没有播到结尾（界面的"播完停止"靠它收场） */
    fun endReached(): Boolean = cEof

    fun setSpeed(speed: Float) {
        post { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
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
        post {
            MPVLib.setPropertyString("android-surface-size", "${w}x$h")
            Log.i(TAG, "内核画布尺寸 → ${w}x$h")
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
        post {
            MPVLib.setPropertyInt("aid", id)
            Log.i(TAG, "内核切音轨：第 ${ordinal + 1} 条（aid=$id）")
        }
        return true
    }

    /**
     * 内核侧**内封**字幕轨条数。
     *
     * 用途（2026-10-11）：判断"能不能按序号选字幕"。Emby 报出的内封轨条数与内核
     * 手里的条数一致时，两边顺序必然对得上，按序号从**文件里**直接选即可（秒出、
     * 不联网）；对不上（Emby 索引不全，如《蜘蛛侠》Emby 报 1 条、文件里 38 条）
     * 才需要按地址去服务器取。
     */
    fun embeddedSubtitleCount(): Int = parseTrackList(cTrackList).count {
        it.optString("type") == "sub" && !it.optBoolean("external", false)
    }

    /** 内核侧当前是否选中了一条**外挂**字幕轨（地址加载成功与否的判据） */
    fun externalSubtitleSelected(): Boolean = parseTrackList(cTrackList).any {
        it.optString("type") == "sub" && it.optBoolean("external", false) &&
            it.optBoolean("selected", false)
    }

    private fun parseTrackList(raw: String): List<org.json.JSONObject> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 内核侧的字幕轨 id 列表（按文件内顺序）；取不到返回空表。
     *
     * 与音轨同一套做法：Emby 的字幕清单里第几条 → 内核侧第几条，
     * 序号映射靠"两边都是文件内顺序"这个前提（外挂弹幕轨不在内核清单里）。
     */
    fun subtitleTrackIds(): List<Int> {
        val raw = cTrackList.takeIf { it.isNotBlank() }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                /*
                 * 注意：mpv 的 track-list 里字幕类型是 **"sub"**，不是 "subtitle"
                 * （2026-10-09 实测踩到：写成 "subtitle" 永远匹配不到，
                 * 日志表现为"内核没有报出字幕轨"，字幕怎么选都不出来）。
                 * 两个都认，防以后 mpv 改写法。
                 */
                val t = o.optString("type")
                /*
                 * 只数**内封**轨（2026-10-11）：外挂轨是地址加载（sub-add）加进来的，
                 * 而序号映射的另一头（Emby 清单）数的是内封轨 —— 不排除外挂，
                 * 加载过一次地址之后每一条序号都会错位。
                 */
                if ((t == "sub" || t == "subtitle") && !o.optBoolean("external", false)) {
                    out.add(o.optInt("id"))
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "解析内核字幕列表失败: ${t.message}")
            emptyList()
        }
    }

    /**
     * 让内核直接加载一条字幕地址（Emby 转出的那一条）。
     *
     * 为什么内核路径不能再按"第几条"选轨（2026-10-11 父亲实测《蜘蛛侠：崭新之日》）：
     * 内核手里是**原文件**，那部片内封字幕有 38 条（英/保加利亚/捷克/…/中文繁简），
     * 而 Emby 只索引出其中 1 条，菜单里另外 3 条是父亲自己下载的外挂 SRT。
     * 两边清单根本对不上号 —— 选"第一条外挂"落到内核第 2 条（英文），
     * 选第三条落到内核第 4 条（捷克语），父亲看到的就是"英文"和"乱字符"。
     * 走这条地址加载，内核拿到的就是 Emby 那一条，内容与菜单一一对应。
     */
    fun addSubtitleUrl(url: String): Boolean {
        /*
         * 排到 mpv 线程上（2026-10-11）：这一步会让 mpv 同步去打开那个地址，
         * 服务端取不出字幕时要等 60 秒才失败 —— 放在界面线程上就是把界面按死。
         * 这里立刻返回，结果靠 externalSubtitleSelected() 事后核对。
         */
        runCatching {
            subExec.execute {
                runCatching {
                    MPVLib.command(arrayOf("sub-add", url, "select"))
                    Log.i(TAG, "内核加载字幕地址：${url.take(140)}")
                }
            }
        }
        return true
    }

    /**
     * 按序号选字幕（内核自渲染）。
     *
     * ordinal = 在 Emby 字幕清单（已排除弹幕轨）里的下标；null 或越界 = 关字幕。
     * 为什么改成走内核：见上面 `sub-auto` 那段的注释（P5 直链播放时 Emby 取不到字幕）。
     */
    fun setSubtitleByOrdinal(ordinal: Int?): Boolean {
        if (ordinal == null) {
            post {
                MPVLib.setPropertyString("sid", "no")
                Log.i(TAG, "内核：字幕关闭（sid=no）")
            }
            return true
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
        post {
            MPVLib.setPropertyInt("sid", id)
            Log.i(TAG, "内核选字幕：第 ${ordinal + 1} 条（sid=$id，共 ${ids.size} 条）")
        }
        return true
    }

    /** 内核侧的音轨 id 列表（按文件内顺序）；取不到返回空表 */
    fun audioTrackIds(): List<Int> {
        val raw = cTrackList.takeIf { it.isNotBlank() }
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
        Log.i(TAG, "内核轨道清单：${cTrackList.ifBlank { "取不到" }}")
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
    fun hasVideo(): Boolean = cHasVideo

    fun stop() {
        endPolling()
        post { MPVLib.command(arrayOf("stop")) }
        try {
            MPVLib.detachSurface()
            surfaceAttached = false
        } catch (_: Throwable) {}
    }
}
