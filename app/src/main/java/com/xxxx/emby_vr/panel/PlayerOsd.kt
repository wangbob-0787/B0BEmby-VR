package com.xxxx.emby_vr.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放控制条（VR 原生播放屏的 OSD，2026-10-05 起）。
 *
 * 形态：一块架在视频屏**下方**的矮条（1920×270 像素的虚拟显示器，与主面板同一套
 * VirtualDisplay + SurfaceTexture 机制），由原生渲染线程贴到视频屏正下方。
 * 不做「叠加在画面上」的半透明浮层 —— 那需要面板带透明通道，先把可用的控制条做出来。
 *
 * 按钮沿用电视版 B0BEmby 播放页的语义（父亲 2026-10-05 定：继承电视版全部按钮，
 * 另加「选片」「退出」）。第一批先上能立刻生效的六颗，其余（上一集/下一集/选集/
 * 字幕/音轨/信息/弹幕）随后补。
 */
enum class OsdButton(val label: String, val icon: ImageVector) {
    PLAY_PAUSE("播放/暂停", Icons.Filled.PlayArrow),
    SEEK_BACK("快退10秒", Icons.Filled.FastRewind),
    SEEK_FWD("快进10秒", Icons.Filled.FastForward),
    SPEED("倍速", Icons.Filled.Speed),
    PICK("选片", Icons.Filled.GridView),
    EXIT("退出", Icons.Filled.Close),
}

/**
 * 控制条状态：主线程（Activity）写，面板界面读。
 *
 * 用 Compose 的 mutableStateOf，界面自动跟着播放进度/播放态刷新。
 */
class OsdState {
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var speed by mutableStateOf(1f)

    /** 播放/暂停按钮显示的图标：跟真实播放态走 */
    val playIcon: ImageVector get() = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow

    /** 每颗按钮点了之后干什么（Agent 侧接 ExoPlayer） */
    var onButton: ((OsdButton) -> Unit)? = null
}

/** 毫秒 → mm:ss（超过一小时给 h:mm:ss） */
fun osdTimeText(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 控制条界面本体（跑在控制条那台虚拟显示器上） */
@Composable
fun PlayerOsdBar(state: OsdState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0141618))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // ① 进度行：当前时间 + 进度条 + 总时长
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = osdTimeText(state.positionMs),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 40.dp)
                    .height(8.dp)
                    .background(Color(0x40FFFFFF), RoundedCornerShape(4.dp)),
            ) {
                val frac = if (state.durationMs > 0L) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(8.dp)
                        .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = osdTimeText(state.durationMs),
                color = Color(0xFFBDBDBD),
                fontSize = 24.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ② 按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OsdButton.entries.forEach { button ->
                OsdButtonView(button, state)
            }
        }
    }
}

@Composable
private fun OsdButtonView(button: OsdButton, state: OsdState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { state.onButton?.invoke(button) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (button == OsdButton.PLAY_PAUSE) state.playIcon else button.icon,
            contentDescription = button.label,
            tint = Color.White,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (button == OsdButton.SPEED) "倍速 ${"%.1f".format(state.speed)}x" else button.label,
            color = Color(0xFFDDDDDD),
            fontSize = 18.sp,
        )
    }
}
