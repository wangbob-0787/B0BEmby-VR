package com.xxxx.emby_vr.vr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 运行时可调参数（父亲 2026-10-08 定：不要写死在代码里，戴着调参比反复编译划算）。
 *
 * 参数写在应用的外部私有目录，adb 可以直接改，改完 1 秒内生效（个别项要重新起播）：
 *
 * ```
 * adb shell "echo -e 'video_w=1280\nvideo_h=720' > /sdcard/Android/data/com.xxxx.emby_vr/files/vr-tuning.txt"
 * ```
 *
 * 参数表（每行 `key=value`，`#` 开头是注释）：
 * | 参数 | 说明 | 默认 |
 * |---|---|---|
 * | `video_w` / `video_h` | 视频链路画布尺寸（内核画布 + 画面缓冲），必须是两张一起写 | 1920x1080 |
 * | `super_sample` | 双眼渲染超采样倍数（0.6~2.0） | 1.25 |
 * | `swap_wait_ms` | 等交换链图像超时（毫秒，0~50） | 4 |
 * | `sync_mode` | 交回图像前的同步：0=只提交命令 1=等 GPU 画完 | 0 |
 * | `refresh_hz` | 刷新率档：0=自动（播放 72 / 界面 90）、72、90 | 0 |
 * | `layer_mask` | 层掩码 6 位：bit0 视频 / bit1 弹幕 / bit2 logo / bit3 海报墙 / bit4 控制条 / bit5 菜单 | 0x3F |
 * | `video_layer` `danmaku_layer` `logo_layer` `panel_layer` `osd_layer` `menu_layer` | 单层开关 0/1（写哪个改哪个位，其余保持默认） | — |
 * | `hide_projection` | 1=不提交投影层（只剩 quad 层），验证黑纹是否必须有投影层参与 | 0 |
 * | `test_pattern` | 1=用固定测试图替代视频纹理，切断 mpv/MediaCodec/SurfaceTexture 输入链 | 0 |
 * | `video_layer_max_w` | 视频层交换链宽度上限（0=按视频原分辨率），验证 4K 大图层是否本身是问题 | 0 |
 * | `no_downsample` | 1=视频走单次纹理采样（关掉 4×4 高斯降采样分支） | 0 |
 * | `no_jitter` | 1=抖动种子固定（排除逐帧变化的着色器输入） | 0 |
 * | `force_rgba8` | 1=视频层交换链强制 GL_RGBA8（不试 sRGB） | 0 |
 * | `no_postfx` | 1=画质增强与颜色调整全回中性 | 0 |
 * | `finish_before_texupdate` | 1=每次取帧前先 glFinish（防外部纹理缓冲被提前回收，黑纹主嫌疑开关） | 0 |
 * | `video_surface_w` | 内核视频处理宽度上限（0=1920）。杜比还原开销与像素量成正比，调小可直接砍 GPU | 0 |
 * | `screen_width` | 银幕宽度（米）。与 `screen_distance` 一起决定视角，默认按 IMAX 第10排几何 | 5.2 |
 * | `screen_distance` | 银幕距离（米）。IMAX 几何 = 宽 26 / 距 15 | 3.2 |
 * | `screen_preset` | 影院银幕两套预设（2026-10-10）：1 = 影厅小屏 5.2/3.2 · 2 = IMAX 大屏 26/15 | 1 |
 * | `min_screen_gap` | 最近允许走到离银幕多少米（再走近场景整体后推，不许穿过；0.3~5.0） | 1.0 |
 * | `follow_viewer` | 控制条/菜单/海报墙是否跟随观影位（1=跟，0=固定；2026-10-10） | 1 |
 * | `ray_scale` | 光柱粗细倍率（1.0 = 银幕 3.2 米时代的角粗细） | 0.35 |
 * | `mpv_<属性>` | 透传给内核的同名属性，例如 `mpv_tone-mapping=spline` | — |
 * | `cmd` | 直接执行一条内核命令，例如 `cmd=screenshot-to-file <路径> video`（导出渲染画面，校色用） | — |
 *
 * 生效时机：`super_sample` 在建交换链时读，要重新起播（或重启应用）才生效；
 * 其余参数当场生效。
 */
object VrTuning {

    private const val TAG = "B0BEmbyVR"
    private const val FILE_NAME = "vr-tuning.txt"

    /**
     * 视频处理宽度上限（2026-10-09 黑纹攻坚）。
     *
     * 这是**纯 Kotlin 侧参数**（不下发原生），默认 0 = 沿用 1920。
     *
     * 为什么需要它：实测杜比还原那条链每帧吃约 15ms GPU（关掉它立刻从 22ms 掉到 6.9ms
     * 且回到满帧）。换更便宜的算法没用（tone-mapping=clip / 双线性缩放都更差）——
     * 说明开销主要跟"处理了多少像素"成正比。
     *
     * **默认值 1280（2026-10-09 父亲实测定案）**：
     *   1920 时 GPU 每帧 22ms → 远超 72Hz 的 13.9ms 预算 → 每帧迟到 →
     *   运行时用上一帧做变形补偿 → 画面出现多条横向跳动的黑纹（贯穿整个视野）。
     *   压到 1280 后 GPU 落到 10~12ms，满帧、条纹消失，且画质父亲认可。
     * 配合：播放时自动请求 72Hz（`gRefreshHz=0` 的自动档），预算 13.9ms，刚好装下。
     *
     * **默认值 1280（2026-10-09 实测定案）**：
     * 按 IMAX 银幕（82°）的解析力推算，1664 才算像素够；但实测 1664 会让 GPU
     * 每帧涨到 15.2ms、超出 13.9ms 预算 → 黑纹复现。1280 只要 6.2~8.1ms、满帧，
     * 银幕保持 IMAX 不缩，只是画面略软。
     */
    @Volatile
    var videoSurfaceMaxW: Int = 1280

    /** 与原生 `nativeSetTuning` 的 key 一一对应 */
    const val KEY_SUPER_SAMPLE = 1
    const val KEY_SWAP_WAIT_MS = 2
    const val KEY_LAYER_MASK = 3
    const val KEY_SYNC_MODE = 4
    const val KEY_REFRESH_HZ = 5
    /* 黑纹诊断开关（2026-10-09）：与原生 nativeSetTuning 的 case 6..12 一一对应 */
    const val KEY_HIDE_PROJECTION = 6
    const val KEY_TEST_PATTERN = 7
    const val KEY_VIDEO_LAYER_MAX_W = 8
    const val KEY_NO_DOWNSAMPLE = 9
    const val KEY_NO_JITTER = 10
    const val KEY_FORCE_RGBA8 = 11
    const val KEY_NO_POSTFX = 12
    const val KEY_FINISH_BEFORE_TEXUPDATE = 13
    /** 银幕几何（2026-10-09）：按「IMAX 第10排」定，也允许运行时调 */
    const val KEY_SCREEN_WIDTH = 14
    const val KEY_SCREEN_DISTANCE = 15
    /** 光柱粗细倍率（2026-10-09）：1.0 = 银幕 3.2 米时代同样的角粗细 */
    const val KEY_RAY_SCALE = 16
    /** 选座（2026-10-10 17:35）：0 = 近排（第 1 排）· 1 = 中排（第 3 排）· 2 = 远排（第 6 排） */
    const val KEY_SCREEN_PRESET = 17
    /** 防穿越：离银幕最近允许多少米（2026-10-10：可以走近银幕，不许穿过） */
    const val KEY_MIN_SCREEN_GAP = 18
    /** 控制条/菜单/海报墙是否跟随观影位（2026-10-10：走近银幕时控制条留在伸手可及处） */
    const val KEY_FOLLOW_VIEWER = 19
    /** 影厅环境开关（2026-10-10）：0 = 回黑背景 */
    const val KEY_CINEMA_ON = 20
    /** 影厅底光（2026-10-10） */
    const val KEY_CINEMA_AMBIENT = 21
    /** 影院 HDRI 环境光强度（2026-10-10） */
    const val KEY_CINEMA_ENV = 22
    /** 坐姿眼高（米，2026-10-10：坐着看电影） */
    const val KEY_CINEMA_EYE_HEIGHT = 23
    /** 影厅环境亮度条（0~1，2026-10-10：环境光 + 底光一起走，不含画面亮度） */
    const val KEY_CINEMA_BRIGHT = 24
    /** 影厅画在哪一层（1 = 独立底层 · 0 = 主投影层，应急对照用） */
    const val KEY_CINEMA_LAYER = 25
    /** 银幕竖直微调（米，2026-10-10） */
    const val KEY_SCREEN_UP = 26
    /** 座椅高度（米，2026-10-10：正 = 椅子相对人抬高） */
    const val KEY_SEAT_UP = 27
    /** 座椅前后（米，2026-10-10：正 = 人往椅子前部坐） */
    const val KEY_SEAT_FWD = 28

    /** 上次应用的配置原文，只有变化才动手（避免每秒重复设置） */
    private var lastRaw: Map<String, String> = emptyMap()
    private var started = false

    fun path(context: Context): String =
        File(context.getExternalFilesDir(null), FILE_NAME).absolutePath

    /**
     * 启动时钟：每秒读一次配置文件。
     *
     * @param onVideoSize 画布尺寸变化（界面层拿它去设内核画布与画面缓冲）
     * @param onMpvOption `mpv_` 前缀的参数，透传给内核
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        onVideoSize: (Int, Int) -> Unit,
        onMpvOption: (String, String) -> Unit,
        onMpvCommand: (List<String>) -> Unit = {},
    ) {
        if (started) return
        started = true
        Log.i(TAG, "调参文件：${path(context)}（改完自动生效，无需重编）")
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { poll(context, onVideoSize, onMpvOption, onMpvCommand) }
                    .onFailure { Log.w(TAG, "调参轮询异常：${it.message}") }
                delay(1000)
            }
        }
    }

    /**
     * 起播前的同步应用（超采样必须赶在建交换链之前灌进原生层）。
     *
     * 只处理原生侧参数，不碰界面层与内核。
     */
    fun applyStartup(context: Context) {
        val raw = read(context) ?: return
        if (raw.isEmpty()) return
        lastRaw = raw
        applyNative(raw)
    }

    private fun poll(
        context: Context,
        onVideoSize: (Int, Int) -> Unit,
        onMpvOption: (String, String) -> Unit,
        onMpvCommand: (List<String>) -> Unit,
    ) {
        val raw = read(context) ?: return
        if (raw.isEmpty() || raw == lastRaw) return
        /*
         * 上一版（父亲 2026-10-10 实测踩坑）：文件里任何一个键变了，就把**整份**配置重发一遍，
         * 包括 mpv_ 那几个内核属性 —— 同一个属性重设会让播放器内核直接 die（当天崩了两次）。
         * 现在按"值真的变了才发"逐个比对，改一个键只动那一件事。
         */
        val prev = lastRaw
        lastRaw = raw

        val applied = mutableListOf<String>()

        val w = raw["video_w"]?.toIntOrNull()
        val h = raw["video_h"]?.toIntOrNull()
        if (w != null && h != null && w >= 64 && h >= 64) {
            onVideoSize(w, h)
            applied += "画布 ${w}x$h"
        }

        applied += applyNative(raw)

        raw.filterKeys { it.startsWith("mpv_") }.forEach { (k, v) ->
            if (prev[k] == v) return@forEach          // 值没变 → 不重发（重设内核属性会崩）
            val prop = k.removePrefix("mpv_")
            onMpvOption(prop, v)
            applied += "内核 $prop=$v"
        }

        /*
         * 调试通道：直接执行一条内核命令。
         *
         * 写法 `cmd=screenshot-to-file /sdcard/Android/data/com.xxxx.emby_vr/files/shot1.png video`
         * 轮询只在"文件原文有变化"时动手，所以同一行只执行一次；想再截一张就改一下文件名。
         */
        raw["cmd"]?.takeIf { it != prev["cmd"] }?.trim()?.takeIf { it.isNotEmpty() }?.let { line ->
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                onMpvCommand(parts)
                applied += "内核命令 ${parts.joinToString(" ")}"
            }
        }

        if (applied.isEmpty()) {
            Log.i(TAG, "调参 → 文件里有内容但没有可识别的参数（键名见 VrTuning 注释）")
        } else {
            Log.i(TAG, "调参生效 → ${applied.joinToString("，")}")
        }
    }

    /** 原生侧参数：超采样 / 等交换链超时 / 层掩码 */
    private fun applyNative(raw: Map<String, String>): List<String> {
        val applied = mutableListOf<String>()
        raw["super_sample"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SUPER_SAMPLE, it)
            applied += "超采样 $it"
        }
        raw["swap_wait_ms"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SWAP_WAIT_MS, it)
            applied += "等图超时 ${it.toInt()}ms"
        }
        raw["refresh_hz"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_REFRESH_HZ, it)
            applied += if (it <= 0f) "刷新率档 自动" else "刷新率档 ${it.toInt()}Hz"
        }
        raw["sync_mode"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SYNC_MODE, it)
            applied += if (it >= 1f) "同步档 glFinish" else "同步档 glFlush"
        }
        /*
         * 层掩码：写了 `layer_mask` 就整份替换（默认 0x3F 全开）；
         * 只写了单层开关，就在默认值上改那一位。
         * 关掉某位 = 该层**既不提交、也不退回画进眼缓冲**（彻底不画），
         * 这样"减少合成图层数"的阶梯实验才是干净对照（2026-10-09 扩展）。
         */
        val maskText = raw["layer_mask"]
        var wrote = false
        val mask = if (maskText != null) {
            wrote = true
            maskText.toIntOrNull() ?: maskText.removePrefix("0x").toIntOrNull(16)
        } else {
            var m = 0x3F
            listOf(
                "video_layer" to 1, "danmaku_layer" to 2, "logo_layer" to 4,
                "panel_layer" to 8, "osd_layer" to 16, "menu_layer" to 32,
            ).forEach { (n, b) ->
                raw[n]?.toIntOrNull()?.let { v ->
                    m = if (v != 0) m or b else m and b.inv()
                    wrote = true
                }
            }
            m
        }
        /*
         * 黑纹诊断开关（2026-10-09）：每一条都对应 ChatGPT 复盘里的一个判定性实验。
         * 全部改文件即生效，不用重编重装 —— 用户戴着头显就能一档一档试。
         */
        fun flag(name: String, key: Int, label: String) {
            raw[name]?.toFloatOrNull()?.let {
                VrNative.setTuning(key, it)
                applied += "$label ${if (it != 0f) "开" else "关"}"
            }
        }
        flag("hide_projection", KEY_HIDE_PROJECTION, "投影层不提交（只留 quad 层）")
        flag("test_pattern", KEY_TEST_PATTERN, "固定测试图（切断解码输入链）")
        raw["video_layer_max_w"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_VIDEO_LAYER_MAX_W, it)
            applied += "视频层宽度上限 ${it.toInt()}（0=原分辨率）"
        }
        flag("no_downsample", KEY_NO_DOWNSAMPLE, "视频降采样关闭")
        flag("no_jitter", KEY_NO_JITTER, "抖动固定")
        flag("force_rgba8", KEY_FORCE_RGBA8, "视频层强制 GL_RGBA8")
        flag("no_postfx", KEY_NO_POSTFX, "画质增强全关")
        raw["video_surface_w"]?.toIntOrNull()?.let {
            videoSurfaceMaxW = it
            applied += "视频处理宽度上限 $it（0=1920）"
        }
        flag("finish_before_texupdate", KEY_FINISH_BEFORE_TEXUPDATE, "取帧前先 glFinish")
        raw["screen_width"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_WIDTH, it)
            applied += "银幕宽 ${it}m"
        }
        raw["screen_distance"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_DISTANCE, it)
            applied += "银幕距离 ${it}m"
        }
        raw["ray_scale"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_RAY_SCALE, it)
            applied += "光柱粗细倍率 $it"
        }
        /*
         * 选座（父亲 2026-10-10 17:35「我要坐着看电影」）：0 = 近排（第 1 排）·
         * 1 = 中排（第 3 排）· 2 = 远排（第 6 排）。影厅整体跟着挪，银幕距离跟着变。
         */
        raw["seat"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_PRESET, it.toFloat())
            applied += "选座 " + when (it) {
                0 -> "近排（第 1 排）"
                1 -> "中排（第 3 排）"
                2 -> "远排（第 6 排）"
                else -> "$it"
            }
        }
        /* 影厅环境开关（2026-10-10）：0 = 回黑背景 */
        raw["cinema_on"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_ON, it.toFloat())
            applied += "影厅环境 " + if (it != 0) "开" else "关"
        }
        /* 影厅底光（2026-10-10） */
        raw["cinema_ambient"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_AMBIENT, it)
            applied += "影厅底光 ${"%.3f".format(it)}"
        }
        /* 影院 HDRI 环境光强度（2026-10-10，用下载的那套全景图当环境光） */
        raw["cinema_env"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_ENV, it)
            applied += "影院环境光强度 ${"%.2f".format(it)}"
        }
        /* 座椅高度（米，2026-10-10） */
        raw["seat_up"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SEAT_UP, it)
            applied += "座椅高度 ${"%+.3f".format(it)} 米"
        }
        /* 座椅前后（米，2026-10-10） */
        raw["seat_fwd"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SEAT_FWD, it)
            applied += "座椅前后 ${"%+.3f".format(it)} 米"
        }
        /* 银幕竖直微调（米，2026-10-10） */
        raw["screen_up"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_UP, it)
            applied += "银幕竖直微调 ${"%+.2f".format(it)} 米"
        }
        /* 影厅图层模式（2026-10-10）：1 = 独立底层，0 = 画进主投影层（对照） */
        raw["cinema_layer"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_LAYER, it.toFloat())
            applied += "影厅图层 " + if (it != 0) "独立底层" else "主投影层"
        }
        /* 影厅环境亮度条（2026-10-10）：0~1，环境光与底光一起走 */
        raw["cinema_bright"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_BRIGHT, it)
            applied += "影厅环境亮度 ${"%.2f".format(it)}"
        }
        /* 坐姿眼高（2026-10-10）：坐着看电影，地面到眼睛的距离 */
        raw["cinema_eye_height"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_EYE_HEIGHT, it)
            applied += "坐姿眼高 ${"%.2f".format(it)} 米"
        }
        /* 防穿越最近距离（2026-10-10）：走近银幕到这么近时，场景会整体后推，不许穿过 */
        raw["min_screen_gap"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_MIN_SCREEN_GAP, it)
            applied += "最近离银幕 ${"%.1f".format(it)} 米"
        }
        /* 控制条/菜单是否跟随观影位（2026-10-10）：0 = 固定在原地（不跟） */
        raw["follow_viewer"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_FOLLOW_VIEWER, it.toFloat())
            applied += "跟随观影位 " + if (it != 0) "开" else "关"
        }

        if (!wrote) return applied
        if (mask != null) {
            VrNative.setTuning(KEY_LAYER_MASK, mask.toFloat())
            applied += "层掩码 0x${mask.toString(16)}"
        }
        return applied
    }

    /**
     * 取配置里的内核选项（去掉 `mpv_` 前缀）。
     *
     * 内核创建那一刻由 MpvBackend 调用一次：app 启动时内核还没建，
     * 那时下发 `mpv_` 选项会失败，必须在每次建后端时重新应用。
     */
    fun mpvOptions(context: Context): Map<String, String> =
        read(context).orEmpty()
            .filterKeys { it.startsWith("mpv_") }
            .mapKeys { it.key.removePrefix("mpv_") }

    /** 读配置；文件不存在或读失败返回 null */
    private fun read(context: Context): Map<String, String>? {
        val f = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!f.isFile) return null
        return try {
            f.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.contains('=') }
                .associate {
                    it.substringBefore('=').trim() to it.substringAfter('=').trim()
                }
        } catch (t: Throwable) {
            Log.w(TAG, "调参文件读取失败：${t.message}")
            null
        }
    }
}
