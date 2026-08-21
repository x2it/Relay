package com.freeproxy.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freeproxy.app.R
import com.freeproxy.app.ui.AppEntry
import com.freeproxy.app.ui.SettingsViewModel
import com.freeproxy.app.ui.theme.Bad
import com.freeproxy.app.ui.theme.Good
import com.freeproxy.app.ui.theme.OnSurface
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.Outline
import com.freeproxy.app.ui.theme.Seed
import com.freeproxy.app.ui.theme.Surface2
import com.freeproxy.app.ui.theme.Surface3
import com.freeproxy.app.ui.theme.Warn
import com.freeproxy.app.vpn.RouteMode
import com.freeproxy.app.vpn.SUGGESTED_EXCLUDED_PACKAGES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    autoPick: Boolean,
    onAutoPickToggle: (Boolean) -> Unit,
    localProxyRunning: Boolean,
    localProxyPort: Int?,
    onStartLocalProxy: () -> Unit,
    onStopLocalProxy: () -> Unit,
    onImport: () -> Unit,
    onExportJson: () -> Unit,
    onExportTxt: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    color = OnSurface,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .verticalScroll(scroll),
        ) {
            SectionHeader("数据导入导出", "JSON 保留全字段；TXT 为通用代理 URL 格式")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(Modifier.weight(1f),
                    icon = R.drawable.ic_import, color = Seed,
                    title = "导入", subtitle = "JSON / TXT", onClick = onImport)
                ActionTile(Modifier.weight(1f),
                    icon = R.drawable.ic_file_json, color = Good,
                    title = "导出 JSON", subtitle = "全量备份", onClick = onExportJson)
                ActionTile(Modifier.weight(1f),
                    icon = R.drawable.ic_export, color = Warn,
                    title = "导出 TXT", subtitle = "通用 URL", onClick = onExportTxt)
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("路由分流", "默认「智能」，国内直连 / 海外代理最均衡")
            Spacer(Modifier.height(10.dp))
            val routeMode by vm.routeMode.collectAsState(initial = RouteMode.SMART)
            val excluded by vm.excludedApps.collectAsState(initial = emptySet())
            var showAppPicker by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface2),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    TileHeader(
                        icon = R.drawable.ic_globe, color = Seed,
                        title = "分流模式",
                        subtitle = "重启一次 VPN 连接后生效"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RouteMode.entries.forEach { m ->
                            val selected = routeMode == m
                            FilterChip(
                                selected = selected,
                                onClick = { vm.setRouteMode(m) },
                                label = {
                                    Column {
                                        Text(m.label, fontWeight = FontWeight.SemiBold,
                                            color = if (selected) Seed else OnSurface, fontSize = 13.sp)
                                        Text(m.desc, fontSize = 10.5.sp,
                                            color = if (selected) Seed.copy(alpha = 0.75f) else OnSurfaceDim,
                                            lineHeight = 13.sp
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Seed.copy(alpha = 0.08f),
                                    containerColor = Surface3,
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (selected) Seed else Outline.copy(alpha = 0.40f)),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    val suggestChecked by vm.suggestedExcludedChecked.collectAsState(initial = true)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface3)
                            .clickable { vm.toggleSuggestedExcluded(!suggestChecked) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                            .background(Good.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_plus),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Good,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("应用建议排除",
                                color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("微信/支付宝/淘宝/抖音等 ${SUGGESTED_EXCLUDED_PACKAGES.size} 个常用 App 直连不走代理",
                                color = OnSurfaceDim, fontSize = 12.sp, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = suggestChecked,
                            onCheckedChange = { vm.toggleSuggestedExcluded(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Good,
                                checkedTrackColor = Good.copy(alpha = 0.45f)),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    val tileSubtitle = if (excluded.isEmpty())
                        "还没有 App 被排除，默认全部走路由策略"
                    else "已排除 ${excluded.size} 个 App"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface3)
                            .clickable { showAppPicker = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                            .background(Seed.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_plus),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Seed,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("按 App 排除",
                                color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(tileSubtitle,
                                color = OnSurfaceDim, fontSize = 12.sp)
                        }
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = OnSurfaceDim,
                        )
                    }
                }
            }

            if (showAppPicker) {
                val ctx = LocalContext.current
                LaunchedEffect(Unit) { vm.loadApps() }
                val allApps by vm.appList.collectAsStateWithLifecycle()
                val appListLoading by vm.appListLoading.collectAsStateWithLifecycle()
                var showSystem by remember { mutableStateOf(false) }
                var query by remember { mutableStateOf("") }
                val draft = remember(excluded) { mutableSetOf<String>().apply { addAll(excluded) } }
                val filtered = allApps.filter { a ->
                    (showSystem || !a.isSystem) &&
                        (query.isBlank() ||
                            a.label.contains(query, ignoreCase = true) ||
                            a.pkg.contains(query, ignoreCase = true))
                }
                val needPermission = !appListLoading && allApps.isEmpty()
                AlertDialog(
                    containerColor = Surface2,
                    shape = RoundedCornerShape(14.dp),
                    onDismissRequest = { showAppPicker = false },
                    title = {
                        Text("按 App 排除",
                            color = OnSurface,
                            style = MaterialTheme.typography.titleMedium)
                    },
                    text = {
                        Column {
                            if (appListLoading) {
                                Box(
                                    Modifier.fillMaxWidth().padding(28.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = Seed, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                                }
                            } else if (needPermission) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Warn.copy(alpha = 0.08f))
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Text("需要「读取已安装应用」权限",
                                            color = Warn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Android 11 起默认不允许读取全部 App。\n点下方按钮跳转到系统设置，给 Relay 打开权限后返回即可看到列表。",
                                            color = OnSurfaceDim, fontSize = 12.sp, lineHeight = 16.sp,
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Warn)
                                                .clickable {
                                                    runCatching {
                                                        val intent = android.content.Intent(
                                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            android.net.Uri.fromParts("package", ctx.packageName, null)
                                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        ctx.startActivity(intent)
                                                    }
                                                }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("打开系统设置给权限",
                                                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = { Text("搜索 App 名称或包名", color = OnSurfaceDim) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Seed, unfocusedBorderColor = Outline,
                                    )
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = showSystem,
                                        onCheckedChange = { showSystem = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Seed,
                                            checkedTrackColor = Seed.copy(alpha = 0.45f)),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("显示系统 App", color = OnSurface, fontSize = 12.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("${draft.size} / ${filtered.size}",
                                        color = OnSurfaceDim, fontSize = 11.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.height(320.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Surface3)) {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        if (filtered.isEmpty()) {
                                            Text("没有匹配的 App",
                                                Modifier.padding(16.dp), color = OnSurfaceDim)
                                        } else {
                                            filtered.forEach { app ->
                                                val checked = draft.contains(app.pkg)
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (checked) draft.remove(app.pkg)
                                                            else draft.add(app.pkg)
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Checkbox(
                                                        checked = checked,
                                                        onCheckedChange = null,
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = Seed, checkmarkColor = Color.White),
                                                    )
                                                    Spacer(Modifier.width(2.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(app.label, color = OnSurface, fontSize = 13.sp)
                                                        Text(app.pkg, color = OnSurfaceDim, fontSize = 10.5.sp)
                                                    }
                                                    if (app.isSystem) {
                                                        Box(Modifier.clip(RoundedCornerShape(6.dp))
                                                            .background(Outline.copy(alpha = 0.18f))
                                                            .padding(horizontal = 6.dp, vertical = 1.dp)) {
                                                            Text("系统", fontSize = 10.sp, color = OnSurfaceDim)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        if (!needPermission) {
                            TextButton(onClick = {
                                vm.setExcludedApps(draft.toSet())
                                showAppPicker = false
                            }) { Text("保存", color = Seed, fontWeight = FontWeight.SemiBold) }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAppPicker = false }) {
                            Text(if (needPermission) "关闭" else "取消", color = OnSurfaceDim)
                        }
                    }
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("测速与连接", "影响代理检测和 VPN 隧道行为")
            Spacer(Modifier.height(10.dp))

            val testUrl by vm.testUrl.collectAsState(initial = "https://www.google.com")
            val testTimeout by vm.testTimeout.collectAsState(initial = 8000)
            val connTimeout by vm.connectTimeout.collectAsState(initial = 10000)
            val autoReconnect by vm.autoReconnect.collectAsState(initial = true)
            val maxFail by vm.maxFail.collectAsState(initial = 5)
            val busy by vm.busy.collectAsState()

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface2),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    TileHeader(
                        icon = R.drawable.ic_link, color = Seed,
                        title = "测速目标 URL",
                        subtitle = "检测连通性与下载速度时请求的页面"
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = vm::setTestUrl,
                        Modifier.fillMaxWidth(),
                        placeholder = { Text("https://www.google.com", color = OnSurfaceDim) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Seed, unfocusedBorderColor = Outline,
                        ),
                    )

                    Spacer(Modifier.height(14.dp))
                    IntSliderTile(
                        icon = R.drawable.ic_speed,
                        title = "测速超时",
                        subtitle = "代理测速最长等待时间",
                        color = Seed,
                        value = testTimeout,
                        range = 2000..30000, step = 2000, unit = "ms",
                        onChange = vm::setTestTimeout,
                    )

                    Spacer(Modifier.height(14.dp))
                    IntSliderTile(
                        icon = R.drawable.ic_timer,
                        title = "连接超时",
                        subtitle = "与代理服务器握手超时",
                        color = Warn,
                        value = connTimeout,
                        range = 3000..60000, step = 3000, unit = "ms",
                        onChange = vm::setConnectTimeout,
                    )

                    Spacer(Modifier.height(14.dp))
                    IntSliderTile(
                        icon = R.drawable.ic_block,
                        title = "失败阈值",
                        subtitle = "代理连续失败超过此次数视为无效",
                        color = Bad,
                        value = maxFail,
                        range = 2..20, step = 1, unit = "次",
                        onChange = vm::setMaxFail,
                    )

                    Spacer(Modifier.height(14.dp))
                    SwitchTile(
                        icon = R.drawable.ic_refresh,
                        title = "自动重连",
                        subtitle = "隧道意外中断时自动重连",
                        color = Good,
                        checked = autoReconnect,
                        onChange = vm::setAutoReconnect,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("高级工具", "首页外移的进阶选项，让首页保持简洁")
            Spacer(Modifier.height(10.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface2),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    SwitchTile(
                        icon = R.drawable.ic_speed,
                        title = "连接前自动挑最快",
                        subtitle = "点连接时并发测速 10 条，选最快节点",
                        color = Seed,
                        checked = autoPick,
                        onChange = onAutoPickToggle,
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Outline.copy(alpha = 0.4f)))
                    Spacer(Modifier.height(14.dp))
                    LocalProxyTile(
                        running = localProxyRunning,
                        port = localProxyPort,
                        onStart = onStartLocalProxy,
                        onStop = onStopLocalProxy,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("关于 Relay", "轻量化免费代理客户端")
            Spacer(Modifier.height(10.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface2),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Seed),
                            contentAlignment = Alignment.Center) {
                            Text("R", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp,
                                letterSpacing = (-0.5).sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Relay v1.2.0",
                                style = MaterialTheme.typography.titleMedium, color = OnSurface)
                            Text(
                                "发现 → 测速 → 连接。完全本地运行，不收集任何数据。",
                                style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim,
                            )
                        }
                    }
                    if (busy) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Seed))
                            Spacer(Modifier.width(8.dp))
                            Text("处理中…", color = OnSurfaceDim, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

// ================== helpers ==================

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = OnSurface, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        }
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier,
    @DrawableRes icon: Int, color: Color,
    title: String, subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            IconTile(icon, color)
            Spacer(Modifier.height(8.dp))
            Text(title, color = OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = OnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IconTile(@DrawableRes icon: Int, color: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
        .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center) {
        Icon(painter = painterResource(id = icon), null,
            tint = color, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TileHeader(
    @DrawableRes icon: Int, color: Color,
    title: String, subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, color)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        }
    }
}

@Composable
private fun IntSliderTile(
    @DrawableRes icon: Int, title: String, subtitle: String,
    color: Color, value: Int,
    range: IntRange, step: Int, unit: String, onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            Box(Modifier.clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("$value$unit", color = color,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = color, activeTrackColor = color,
                inactiveTrackColor = color.copy(alpha = 0.15f),
            ),
        )
    }
}

@Composable
private fun SwitchTile(
    @DrawableRes icon: Int, title: String, subtitle: String,
    color: Color, checked: Boolean, onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        IconTile(icon, color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = color,
                checkedTrackColor = color.copy(alpha = 0.5f),
            ),
        )
    }
}

/**
 * 本机代理 tile：显示状态 + 端口 + 启停按钮
 */
@Composable
private fun LocalProxyTile(
    running: Boolean,
    port: Int?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (running) Good.copy(alpha = 0.12f) else Surface3.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (running) Good else OnSurfaceDim.copy(alpha = 0.4f))
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (running) "本机代理运行中" else "本机代理",
                style = MaterialTheme.typography.titleMedium, color = OnSurface,
            )
            Text(
                if (running) "127.0.0.1:${port ?: 8080} · 在本地开一个 HTTP 代理端口"
                else "在手机本地开一个 HTTP 代理端口，供浏览器/Wi-Fi 手动配置走代理",
                style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim,
            )
            Text(
                "与 VPN 不同：VPN 是全局走代理，本机代理只服务手动指向它的 App",
                style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim.copy(alpha = 0.7f),
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (running) Bad.copy(alpha = 0.10f) else Seed.copy(alpha = 0.10f))
                .clickable { if (running) onStop() else onStart() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                if (running) "停止" else "启动",
                color = if (running) Bad else Seed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}
