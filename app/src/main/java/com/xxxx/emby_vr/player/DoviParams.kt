package com.xxxx.emby_vr.player

import android.util.Log

/**
 * 杜比逐帧还原参数的接收端（内核 → 渲染层）。
 *
 * 背景：现在颜色还原在播放内核里做，处理量跟片源分辨率绑在一起，4K 片源时
 * 上不去更高的上屏分辨率。要照 4XVR 那样「先出小图、再按屏幕像素还原」，
 * 得让渲染层拿到每帧的还原参数。
 *
 * 内核补丁在解析出 RPU 后调用 [update]，参数放在扁平 float 数组里；
 * 渲染层每帧读 [snapshot]。
 */
object DoviParams {

    private const val TAG = "DoviParams"

    /** 最近一帧的还原参数；空数组表示还没收到。 */
    @Volatile
    private var latest: FloatArray = FloatArray(0)

    @Volatile
    var updateCount: Long = 0
        private set

    /** 由内核（native）回调。数组按约定布局，见 ffmpeg 补丁里的打包函数。 */
    @JvmStatic
    fun update(flat: FloatArray?) {
        if (flat == null || flat.isEmpty()) return
        latest = flat
        updateCount++
        if (updateCount == 1L || updateCount % 120L == 0L) {
            Log.i(TAG, "收到杜比参数 #$updateCount，长度 ${flat.size}")
        }
    }

    fun snapshot(): FloatArray = latest

    fun reset() {
        latest = FloatArray(0)
        updateCount = 0
    }
}
