package com.xxxx.emby_vr.data

import androidx.compose.runtime.mutableIntStateOf

/**
 * 「播放结束 → 界面重新拉数据」的通知点（VR 版新增，2026-10-05）。
 *
 * 为什么需要它：VR 版的播放屏是原生播放器，面板只是被盖住了、并没有销毁，
 * 所以播完回来时首页/详情页还是**播放前**的那份数据（继续观看、播到第几集、
 * 进度条都不会变）。父亲报「播放历史没同步」时，除了服务端有没有收到上报，
 * 界面本身不刷新也是一半原因。
 *
 * 用法：MainActivity 在退出播放/播完/播放出错时调 [bump]；
 * 首页与详情页读 [version]，变化时重新拉数据。
 */
object PlaybackSync {

    /** 每结束一次播放 +1；0 表示本次启动还没播过（首个组合不要触发刷新） */
    val version = mutableIntStateOf(0)

    fun bump() {
        version.intValue += 1
    }
}
