package com.xxxx.emby_vr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.content.Context
import com.xxxx.emby_vr.ui.theme.ThemeColor
import com.xxxx.emby_vr.ui.theme.ThemeColorManager

@Composable
fun BuildGradientBackground(
    context: Context,
    themeColor: ThemeColor = ThemeColorManager.getThemeColorById(context, "dark"), // 默认暗色调
    content: @Composable () -> Unit
) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 实验（2026-09-27 电视端卡顿排查）：整屏线性渐变每帧都要重绘 1920x1080 的渐变着色器，
            // 低端电视 GPU 单帧 20ms+；先换纯色验证收益，再决定渐变的替代方案（缓存成位图 / 只局部用）。
            .background(themeColor.primaryDark)
    ) {
        content()
    }
}