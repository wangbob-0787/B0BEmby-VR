package com.xxxx.emby_vr.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_vr.panel.vrClickTarget

/** 未选中的底：与详情页季胶囊、控制条按钮同一档 */
private val kKeyIdle = Color(0xFF3A3A3A)

/**
 * 内置屏幕键盘（父亲 2026-10-10 定的方案 B）。
 *
 * 为什么要自己画：系统输入法弹不到我们这块自建虚拟屏上（实测：会话绑到了 display 0，
 * 面板那块屏上 `sessionRequested=false`）。自己画的键盘不依赖系统输入法，
 * 打出来的字符直接进我们自己的搜索框。
 *
 * 中文靠 [com.xxxx.emby_vr.data.PinyinIndex]：打 `qyn` 出候选「庆余年」（见搜索浮层里的候选条）。
 *
 * 风格与全站一致（父亲要求）：键底 #3A3A3A、**聚焦/按下才是主题绿**、
 * 8dp 圆角、16sp 粗体；每个键都登记进点击表，光点扣扳机就能敲。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VrKeyboard(
    group: Any?,
    onChar: (Char) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 第二、三行左侧留半格，看起来像真正的键盘
                if (rowIndex == 2) Spacer(modifier = Modifier.weight(0.5f))
                row.forEach { c ->
                    Key(
                        label = c.toString(),
                        group = group,
                        vrKey = "kb:$c",
                        modifier = Modifier.weight(1f),
                        onClick = { onChar(c) },
                    )
                }
                if (rowIndex == 2) Spacer(modifier = Modifier.weight(0.5f))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Key("空格", group, "kb:space", Modifier.weight(2.2f), onSpace)
            Key("退格", group, "kb:back", Modifier.weight(1.3f), onBackspace)
            Key("清空", group, "kb:clear", Modifier.weight(1.3f), onClear)
            // 搜索键=主按钮：常亮绿底
            Key(
                label = "搜索",
                group = group,
                vrKey = "kb:search",
                modifier = Modifier.weight(1.6f),
                onClick = onSearch,
                alwaysAccent = true,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Key(
    label: String,
    group: Any?,
    vrKey: String,
    modifier: Modifier,
    onClick: () -> Unit,
    alwaysAccent: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val accent = MaterialTheme.colorScheme.secondary
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (alwaysAccent) accent else kKeyIdle,
            contentColor = Color.White,
            focusedContainerColor = accent,
            focusedContentColor = Color.White,
        ),
        modifier = modifier
            .height(42.dp)
            .focusRequester(focusRequester)
            .vrClickTarget(
                key = vrKey,
                focusRequester = focusRequester,
                group = group,
                onActivate = onClick,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = if (label.length > 1) 15.sp else 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
