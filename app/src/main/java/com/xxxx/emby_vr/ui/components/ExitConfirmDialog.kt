package com.xxxx.emby_vr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_vr.R
import kotlinx.coroutines.delay

/**
 * 退出确认（父亲 2026-10-02：连按返回键会直接把应用退掉，需要确认）。
 *
 * 用页内浮层而不是系统对话框：电视端 D-pad 焦点进系统 Dialog 窗口偶发抢不到，
 * 页内浮层焦点可控（默认落在「取消」上，误按返回只会关掉这一层）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExitConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(380.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.exit_confirm_title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogButton(
                    text = stringResource(R.string.exit_confirm_cancel),
                    onClick = onCancel,
                    focusRequester = cancelFocus
                )
                DialogButton(
                    text = stringResource(R.string.exit_confirm_ok),
                    onClick = onConfirm
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.graphics.Color.Transparent))),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF333333),
            contentColor = Color.White,
),
        modifier = Modifier.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        )
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 11.dp)
        )
    }
}
