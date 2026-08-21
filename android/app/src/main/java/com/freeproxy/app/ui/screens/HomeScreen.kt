package com.freeproxy.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freeproxy.app.R
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.VpnStatus
import com.freeproxy.app.ui.HomeState
import com.freeproxy.app.ui.LogLine
import com.freeproxy.app.ui.theme.Bad
import com.freeproxy.app.ui.theme.Surface2
import com.freeproxy.app.ui.theme.Good
import com.freeproxy.app.ui.theme.OnSurface
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.Outline
import com.freeproxy.app.ui.theme.Seed
import com.freeproxy.app.ui.theme.SeedDim
import com.freeproxy.app.ui.theme.Warn
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 重设计原则：
 *  - 单屏聚焦：连接按钮是绝对主角，占据视觉中心
 *  - 呼吸感：大段留白，主要元素之间 ≥ 24dp
 *  - 极简：每行只承载一个意思，不堆 chip
 *  - 日志折叠：仅当存在时显示一行入口，点击展开
 *  - 工具外移：自动挑/本机代理等进阶功能移至 Settings，首页保持纯净
 */
@Composable
fun HomeScreen(
    state: HomeState,
    selectedFromRepo: ProxyInfo?,
    onToggle: (selected: ProxyInfo?) -> Unit,
    onPickProxy: () -> Unit,
    autoPickProgress: Int,
    autoPickMessage: String?,
    onExportLogs: () -> Unit = {},
    onClearLogs: () -> Unit = {},
) {
    val status = state.vpnStatus
    val active = state.selectedProxy ?: selectedFromRepo
    var showLogs by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // —— 顶部状态：圆点 + 主副文案 ——
        StatusHeader(status = status, storedCount = state.storedCount)

        Spacer(Modifier.height(56.dp))

        // —— 主连接按钮：视觉锚点 ——
        ConnectButton(status = status, onClick = { onToggle(active) })

        Spacer(Modifier.height(24.dp))

        // —— 自动挑进度（仅 active 时显示）——
        autoPickMessage?.takeIf { it.isNotBlank() }?.let {
            AutoPickProgress(message = it, progress = autoPickProgress)
            Spacer(Modifier.height(20.dp))
        }

        // —— 当前选中代理（极简单行）——
        if (active != null) {
            SelectedProxyRow(p = active, status = status, onClick = onPickProxy)
        } else {
            EmptyProxyRow(onClick = onPickProxy)
        }

        Spacer(Modifier.height(20.dp))

        // —— 流量统计：三栏小字 ——
        StatsRow(
            sessionStart = state.sessionStart.takeIf { status == VpnStatus.CONNECTED },
            bytesIn = state.bytesIn,
            bytesOut = state.bytesOut,
            rateIn = state.rateIn,
            rateOut = state.rateOut,
        )

        Spacer(Modifier.weight(1f))

        // —— 错误提示 ——
        state.message?.takeIf { it.isNotBlank() && status == VpnStatus.ERROR }?.let {
            ErrorLine(it)
            Spacer(Modifier.height(16.dp))
        }

        // —— 日志入口：极简，仅当有日志时显示 ——
        if (state.logs.isNotEmpty()) {
            LogEntry(
                expanded = showLogs,
                logs = state.logs,
                onToggle = { showLogs = !showLogs },
                onExport = onExportLogs,
                onClear = onClearLogs,
            )
            Spacer(Modifier.height(24.dp))
        } else {
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ========================================================================
// 顶部状态：圆点 + 大标题 + 副标题
// ========================================================================

private data class HeaderUi(
    val dotColor: Color, val title: String, val subtitle: String,
    val tagColor: Color, val tagText: String,
)

@Composable
private fun StatusHeader(status: VpnStatus, storedCount: Int) {
    // 三层信息分工：tag=状态色彩胶囊，title=完整状态词，subtitle=仅节点数
    val ui = when (status) {
        VpnStatus.CONNECTED -> HeaderUi(Good, "已连接",
            "共 $storedCount 个节点", Good, "在线")
        VpnStatus.CONNECTING -> HeaderUi(Warn, "建立隧道",
            "共 $storedCount 个节点", Warn, "连接中")
        VpnStatus.DISCONNECTING -> HeaderUi(Warn, "正在断开",
            "共 $storedCount 个节点", Warn, "断开中")
        VpnStatus.ERROR -> HeaderUi(Bad, "连接异常",
            "查看下方日志", Bad, "异常")
        VpnStatus.IDLE -> HeaderUi(OnSurfaceDim, "未连接",
            "共 $storedCount 个节点", OnSurfaceDim, "待命")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 状态栏 TAG：显式标出"隧道在线/未连接"等，用户一眼看见
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ui.tagColor.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ui.dotColor)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                ui.tagText,
                color = ui.tagColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.3.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            ui.title,
            style = MaterialTheme.typography.headlineMedium,
            color = OnSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            ui.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim,
        )
    }
}

// ========================================================================
// 主连接按钮：单一实心圆 + 微阴影（去除嵌套外环）
// ========================================================================

@Composable
private fun ConnectButton(status: VpnStatus, onClick: () -> Unit) {
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val connected = status == VpnStatus.CONNECTED

    val bg by animateColorAsState(
        targetValue = when {
            connected -> Good
            busy -> Warn
            status == VpnStatus.ERROR -> Bad
            else -> Seed
        },
        animationSpec = tween(350), label = "btn_bg"
    )

    Box(
        modifier = Modifier
            .size(180.dp)
            .aspectRatio(1f)
            .shadow(
                elevation = if (connected) 6.dp else 10.dp,
                shape = CircleShape,
                spotColor = bg.copy(alpha = 0.20f),
                ambientColor = bg.copy(alpha = 0.06f),
            )
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                fadeIn(animationSpec = tween(180)) togetherWith
                    fadeOut(animationSpec = tween(180))
            }, label = "btn_state"
        ) { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (s) {
                    VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> {
                        CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.5.dp,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (s == VpnStatus.CONNECTING) "连接中" else "断开中",
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    VpnStatus.CONNECTED -> {
                        IconRes(id = R.drawable.ic_link, tint = Color.White, size = 38.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("点击断开", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    VpnStatus.ERROR -> {
                        IconRes(id = R.drawable.ic_power, tint = Color.White, size = 38.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("重试", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    VpnStatus.IDLE -> {
                        IconRes(id = R.drawable.ic_power, tint = Color.White, size = 38.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("点击连接", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ========================================================================
// 自动挑进度
// ========================================================================

@Composable
private fun AutoPickProgress(message: String, progress: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SeedDim.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Seed))
            Spacer(Modifier.width(10.dp))
            Text(message, color = Seed, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        }
        if (progress in 1..99) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Seed,
                trackColor = Seed.copy(alpha = 0.15f),
            )
        }
    }
}

// ========================================================================
// 当前选中代理：极简单行
// ========================================================================

@Composable
private fun SelectedProxyRow(p: ProxyInfo, status: VpnStatus, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 国家小标
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SeedDim),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (p.countryCode ?: p.country?.take(2) ?: "?").uppercase(Locale.ROOT),
                color = Seed, fontWeight = FontWeight.Bold, fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(14.dp))
        // 主信息：host:port + 类型
        Column(Modifier.weight(1f)) {
            Text(
                p.display(),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${p.type.value} · 点按切换节点",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
            )
        }
        // 延迟
        p.latencyMs?.let {
            LatencyLabel(it)
        }
    }
}

@Composable
private fun EmptyProxyRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Outline.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            IconRes(id = R.drawable.ic_plus, tint = OnSurfaceDim, size = 18.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "还未选择代理 · 点按挑选",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            modifier = Modifier.weight(1f),
        )
    }
}

// ========================================================================
// 流量统计：三栏小字
// ========================================================================

@Composable
private fun StatsRow(sessionStart: Long?, bytesIn: Long, bytesOut: Long, rateIn: Long, rateOut: Long) {
    var duration by remember { mutableStateOf("--:--:--") }
    if (sessionStart != null) {
        LaunchedEffect(sessionStart) {
            while (true) {
                val sec = (System.currentTimeMillis() - sessionStart) / 1000
                val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
                duration = "%02d:%02d:%02d".format(h, m, s)
                delay(1000)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatCell(label = "时长", value = duration)
        StatDivider()
        StatCell(label = "↓ " + humanBytes(bytesIn), value = humanBytes(rateIn) + "/s", color = Seed)
        StatDivider()
        StatCell(label = "↑ " + humanBytes(bytesOut), value = humanBytes(rateOut) + "/s", color = Good)
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color = OnSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.size(1.dp, 22.dp).background(Outline))
}

// ========================================================================
// 错误行
// ========================================================================

@Composable
private fun ErrorLine(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Bad.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Bad))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Bad, fontSize = 12.sp)
    }
}

// ========================================================================
// 日志入口：默认折叠为一行，点击展开最近 3 条
// ========================================================================

@Composable
private fun LogEntry(
    expanded: Boolean,
    logs: List<LogLine>,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1D2129))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Good))
            Spacer(Modifier.width(8.dp))
            Text(
                "进程日志",
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开 ${logs.size}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
            if (expanded) {
                Text(
                    "导出",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onExport)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "清空",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
            Spacer(Modifier.height(8.dp))
            logs.takeLast(3).forEach { line ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tc = when (line.level) {
                        LogLine.Level.INFO -> Color.White.copy(alpha = 0.70f)
                        LogLine.Level.OK   -> Good
                        LogLine.Level.WARN -> Warn
                        LogLine.Level.ERR  -> Bad
                    }
                    Text(
                        line.ts,
                        color = Color.White.copy(alpha = 0.40f),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.text, color = tc, fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ========================================================================
// 小工具
// ========================================================================

@Composable
private fun LatencyLabel(ms: Long) {
    val c = when {
        ms < 200 -> Good
        ms < 800 -> Warn
        else -> Bad
    }
    Text(
        "${ms}ms",
        color = c,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.08f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun IconRes(
    @DrawableRes id: Int,
    tint: Color,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Icon(
        painter = painterResource(id = id),
        contentDescription = null, tint = tint, modifier = Modifier.size(size)
    )
}

fun humanBytes(b: Long): String {
    if (b < 1024) return "${b}B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1fKB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.2fMB".format(mb)
    return "%.2fGB".format(mb / 1024.0)
}
