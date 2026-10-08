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
 * | `layer_mask` | 层掩码：bit0 视频独立层 / bit1 弹幕层 / bit2 片名 logo | 7 |
 * | `video_layer` `danmaku_layer` `logo_layer` | 单层开关 0/1（写哪个改哪个位，其余保持默认） | — |
 * | `mpv_<属性>` | 透传给内核的同名属性，例如 `mpv_tone-mapping=spline` | — |
 *
 * 生效时机：`super_sample` 在建交换链时读，要重新起播（或重启应用）才生效；
 * 其余参数当场生效。
 */
object VrTuning {

    private const val TAG = "B0BEmbyVR"
    private const val FILE_NAME = "vr-tuning.txt"

    /** 与原生 `nativeSetTuning` 的 key 一一对应 */
    const val KEY_SUPER_SAMPLE = 1
    const val KEY_SWAP_WAIT_MS = 2
    const val KEY_LAYER_MASK = 3
    const val KEY_SYNC_MODE = 4

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
    ) {
        if (started) return
        started = true
        Log.i(TAG, "调参文件：${path(context)}（改完自动生效，无需重编）")
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { poll(context, onVideoSize, onMpvOption) }
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
    ) {
        val raw = read(context) ?: return
        if (raw.isEmpty() || raw == lastRaw) return
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
            val prop = k.removePrefix("mpv_")
            onMpvOption(prop, v)
            applied += "内核 $prop=$v"
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
        raw["sync_mode"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SYNC_MODE, it)
            applied += if (it >= 1f) "同步档 glFinish" else "同步档 glFlush"
        }
        /*
         * 层掩码：写了 `layer_mask` 就整份替换（默认 7）；
         * 只写了单层开关，就在默认值上改那一位。
         */
        val maskText = raw["layer_mask"]
        var wrote = false
        val mask = if (maskText != null) {
            wrote = true
            maskText.toIntOrNull() ?: maskText.removePrefix("0x").toIntOrNull(16)
        } else {
            var m = 7
            listOf("video_layer" to 1, "danmaku_layer" to 2, "logo_layer" to 4).forEach { (n, b) ->
                raw[n]?.toIntOrNull()?.let { v ->
                    m = if (v != 0) m or b else m and b.inv()
                    wrote = true
                }
            }
            m
        }
        if (!wrote) return applied
        if (mask != null) {
            VrNative.setTuning(KEY_LAYER_MASK, mask.toFloat())
            applied += "层掩码 0x${mask.toString(16)}"
        }
        return applied
    }

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
