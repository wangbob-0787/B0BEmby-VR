package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 面板层验证界面（2026-10-04）。
 *
 * 目的只有两个，都能用眼睛直接判断：
 *   1. **渲染通**：这一屏能看清字与按钮 —— 说明电视版那套 Compose 界面
 *      可以整体渲染成一张纹理贴进 VR 场景。
 *   2. **输入通**：点按钮数字会涨 —— 说明手柄光标能穿透到复用的界面里。
 *
 * 这是地基验证，不是最终界面。验证通过后，这里换成从电视版 B0BEmby
 * 搬过来的真实界面（首页/片库/搜索/详情/设置）。
 *
 * 配色沿用电视版暗色主题（底 #08080B、强调纯绿 #4CD137），
 * 便于顺带确认颜色在两边的观感一致。
 */
@Composable
fun PanelUi(onClick: () -> Unit) {
    var count by remember { mutableStateOf(0) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4CD137),
            background = Color(0xFF08080B),
            surface = Color(0xFF14141A),
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF08080B))
                .padding(48.dp),
        ) {
            Text(
                "面板层验证 · 电视版界面复用",
                color = Color(0xFF4CD137),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "能看到这一屏 = 渲染通；点下面按钮数字会涨 = 输入通",
                color = Color(0xFFB0B0B8),
                fontSize = 24.sp,
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = {
                    count += 1
                    onClick()
                },
                modifier = Modifier.height(84.dp),
            ) {
                Text("点我  #$count", fontSize = 30.sp)
            }
            Spacer(Modifier.height(36.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(30) { i ->
                    Text(
                        "列表项 $i · 验证复用界面的排版与滚动",
                        color = Color(0xFFD8D8DE),
                        fontSize = 24.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
