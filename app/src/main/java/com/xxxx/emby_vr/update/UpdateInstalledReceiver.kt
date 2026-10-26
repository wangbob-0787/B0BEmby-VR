package com.xxxx.emby_vr.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 新版本装好之后把界面拉起来。
 *
 * 装包那一刻系统会结束老进程，用户眼前一黑回到系统界面；这条广播由新版本自己收到
 * （`MY_PACKAGE_REPLACED` 只发给被替换的包），顺势打开主界面，省得用户再去桌面翻。
 *
 * 只在这种"刚更新完"的时机自动打开：平时开机、切换都不会走到这里。
 */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            // 后台启动 Activity 被系统挡住就什么都不做，用户从桌面点开即可
        }
    }
}
