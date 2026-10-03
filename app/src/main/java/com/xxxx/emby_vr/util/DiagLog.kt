package com.xxxx.emby_vr.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时诊断日志（2026-09-27 排查音乐播放不出去）。
 *
 * 为什么需要它：投影（Vidda）不给 logcat（`adb logcat` 返回 0 行），
 * 而且外置私有目录 `/sdcard/Android/data/<pkg>/` 在该设备上不存在（getExternalFilesDir 返回 null），
 * 所以日志写**内置** filesDir，用**可调试包**（debug 变体，同签名可覆盖安装）配合
 * `adb shell run-as com.xxxx.emby_vr cat files/b0b-diag.log` 读取。
 * 排查结束后整体删除本文件与调用点。
 */
object DiagLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun w(context: Context?, tag: String, msg: String) {
        android.util.Log.i("B0BDiag", "[$tag] $msg")
        if (context == null) return
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        try {
            File(context.filesDir, "b0b-diag.log").appendText(line)
        } catch (_: Throwable) {
        }
    }
}
