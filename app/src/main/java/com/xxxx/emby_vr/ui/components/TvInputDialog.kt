package com.xxxx.emby_vr.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.panel.ClickTargets
import com.xxxx.emby_vr.panel.vrClickBlocker
import com.xxxx.emby_vr.panel.vrClickTarget

/** 未选中的底：与全站一致 */
private val kDialogIdle = Color(0xFF3A3A3A)

/**
 * 输入对话框（2026-10-10 重做）。
 *
 * 两处改动，都是为了 VR 面板：
 *   1. **不用系统输入法**：系统键盘弹不到我们这块自建虚拟屏上（实测：输入法会话绑到了
 *      display 0，面板那块屏上 `sessionRequested=false`）。改成**我们自己的键盘**
 *      （[VrKeyboard]），打出来的字直接进输入框。
 *   2. **不用弹窗窗口**：原来是 `AlertDialog`（Compose Dialog = 新建窗口），
 *      这种窗口落不到 VR 面板上（首页菜单当年就是这个毛病）。改成**面板内的浮层**，
 *      跟搜索浮层同一套做法：全屏压暗 + 卡片 + 模态点击分组 + 全屏兜底。
 *
 * 登录页、代理设置页的 8 处输入都走这个组件，所以改一处全都好。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvInputDialog(
    title: String,
    initialValue: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isNumber: Boolean = false,
) {
    var inputText by remember { mutableStateOf(initialValue) }
    val group = remember { Any() }
    val fieldFocus = remember { FocusRequester() }

    DisposableEffect(group) {
        ClickTargets.setModalGroup(group)
        onDispose { ClickTargets.clearModalGroup(group) }
    }

    BackHandler(enabled = true) { onDismiss() }

    LaunchedEffect(Unit) {
        fieldFocus.requestFocusRetry()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .vrClickBlocker(key = "input:bg", group = group)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(22.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 输入框（只读：文字由我们自己的键盘写入）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .focusRequester(fieldFocus)
                    .vrClickTarget(
                        key = "input:field",
                        focusRequester = fieldFocus,
                        group = group,
                        onActivate = { runCatching { fieldFocus.requestFocus() } },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = stringResource(R.string.input_label),
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 16.sp,
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { },
                    readOnly = true,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            VrKeyboard(
                group = group,
                onChar = { c ->
                    if (!isNumber || c.isDigit()) inputText = (inputText + c).take(120)
                },
                onSpace = { if (!isNumber) inputText = (inputText + " ").take(120) },
                onBackspace = { if (inputText.isNotEmpty()) inputText = inputText.dropLast(1) },
                onClear = { inputText = "" },
                onSearch = {
                    onConfirm(inputText)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                DialogButton(
                    text = stringResource(R.string.cancel),
                    group = group,
                    vrKey = "input:cancel",
                    onClick = onDismiss,
                )
                DialogButton(
                    text = stringResource(R.string.confirm),
                    group = group,
                    vrKey = "input:confirm",
                    onClick = {
                        onConfirm(inputText)
                        onDismiss()
                    },
                    accent = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogButton(
    text: String,
    group: Any?,
    vrKey: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val accentColor = MaterialTheme.colorScheme.secondary
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (accent) accentColor else kDialogIdle,
            contentColor = Color.White,
            focusedContainerColor = VrHoverBg,
            focusedContentColor = Color.White,
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .vrClickTarget(
                key = vrKey,
                focusRequester = focusRequester,
                group = group,
                onActivate = onClick,
            ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
