package com.freeproxy.app.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeproxy.app.ui.AppEntry
import com.freeproxy.app.ui.SettingsViewModel
import com.freeproxy.app.ui.theme.Bad
import com.freeproxy.app.ui.theme.Good
import com.freeproxy.app.ui.theme.OnSurface
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.OnSurfaceMute
import com.freeproxy.app.ui.theme.Outline
import com.freeproxy.app.ui.theme.Seed
import com.freeproxy.app.ui.theme.Surface2
import com.freeproxy.app.ui.theme.Surface3
import com.freeproxy.app.ui.theme.Warn
import com.freeproxy.app.vpn.RouteMode
import com.freeproxy.app.vpn.SUGGESTED_EXCLUDED_PACKAGES

private const val VERSION = "v1.5.1"

/**
 * CONF 设置页（terminal 极简风格）
 * 功能全保留，界面统一为等宽 terminal 列表：
 *   -- io --  import / export / sources
 *   -- route --  smart/direct/proxy 单行 chips · suggest exclude · app exclude
 *   -- net --  test url · test timeout · conn timeout · fail max · auto reconnect
 *   -- adv --  auto pick · local proxy
 *   -- about -- 版本
 * 分隔全部用线条；开关 [●]/[○]；数值 [-]/[+] 步进
 * 标记规范：开关 [●]/[○] · 选择 [○]/[ ]（全 app 统一，不用 x/×）
 */
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
    onOpenSources: () -> Unit,
) {
    // 对话框所需状态提升到函数顶层（两个 AlertDialog 渲染在 Column 之外）
    val excluded by vm.excludedApps.collectAsState(initial = emptySet())
    var showAppPicker by remember { mutableStateOf(false) }
    val testUrl by vm.testUrl.collectAsState(initial = "https://www.google.com")
    var showUrlEdit by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "> conf",
                    style = MaterialTheme.typography.titleLarge,
                    color = OnSurface,
                    fontFamily = FontFamily.Monospace,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ==================== io ====================
            GroupHeader("io")
            TNav("import", "json / txt", onImport)
            TNav("export json", "全量备份", onExportJson)
            TNav("export txt", "通用 url", onExportTxt)
            TNav("sources", "管理数据源", onOpenSources)

            // ==================== route ====================
            GroupHeader("route")
            val routeMode by vm.routeMode.collectAsState(initial = RouteMode.SMART)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RouteMode.entries.forEach { m ->
                    val sel = routeMode == m
                    val short = if (m == RouteMode.SMART) "smart" else "global"
                    Text(
                        if (sel) "[$short]" else " $short ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) Seed else OnSurfaceDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) Seed.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { vm.setRouteMode(m) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            val routeHint = if (routeMode == RouteMode.SMART) "smart: cn direct" else "global: all proxied"
            Text(
                "> $routeHint · restart vpn",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = OnSurfaceMute,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            )

            val suggestChecked by vm.suggestedExcludedChecked.collectAsState(initial = true)
            TSwitch(
                label = "suggest exclude",
                sub = "${SUGGESTED_EXCLUDED_PACKAGES.size} apps 直连",
                checked = suggestChecked,
                color = Good,
                onChange = vm::toggleSuggestedExcluded,
            )

            TNav(
                "app exclude",
                if (excluded.isEmpty()) "none" else "${excluded.size} apps",
                onClick = { showAppPicker = true },
            )

            // ==================== net ====================
            GroupHeader("net")
            val testTimeout by vm.testTimeout.collectAsState(initial = 8000)
            val connTimeout by vm.connectTimeout.collectAsState(initial = 10000)
            val autoReconnect by vm.autoReconnect.collectAsState(initial = true)
            val maxFail by vm.maxFail.collectAsState(initial = 5)

            TNav(
                "test url",
                if (testUrl.length > 26) testUrl.take(26) + "…" else testUrl,
                onClick = { showUrlEdit = true },
            )
            TStepper(
                label = "test timeout",
                value = testTimeout,
                unit = "ms",
                range = 2000..30000,
                step = 2000,
                color = Seed,
                onChange = vm::setTestTimeout,
            )
            TStepper(
                label = "conn timeout",
                value = connTimeout,
                unit = "ms",
                range = 3000..60000,
                step = 3000,
                color = Warn,
                onChange = vm::setConnectTimeout,
            )
            TStepper(
                label = "fail max",
                value = maxFail,
                unit = "次",
                range = 2..20,
                step = 1,
                color = Bad,
                onChange = vm::setMaxFail,
            )
            TSwitch(
                label = "auto reconnect",
                sub = "断开自动重连",
                checked = autoReconnect,
                color = Good,
                onChange = vm::setAutoReconnect,
            )

            // ==================== adv ====================
            GroupHeader("adv")
            TSwitch(
                label = "auto pick fastest",
                sub = "连接前测速挑最快",
                checked = autoPick,
                color = Seed,
                onChange = onAutoPickToggle,
            )
            TSwitch(
                label = "local proxy",
                sub = if (localProxyRunning) "127.0.0.1:${localProxyPort ?: 8080}" else "off",
                checked = localProxyRunning,
                color = Good,
                onChange = { if (it) onStartLocalProxy() else onStopLocalProxy() },
            )

            // ==================== help ====================
            GroupHeader("help · 新手引导 & 自检清单")
            val helpLines = listOf(
                "■ 1. HOME → 一键流程  =  抓取源 → 解析订阅 → 验证测速 → 启动 VPN",
                "■ 2. 节点数量少 → 去 [抓取源] 切到 B 类 (GitHub) 或 C 类 (HTML)，再抓一次",
                "■ 3. 开了 VPN 仍打不开 Google → 看 LIST 页面的「存活」栏，要 > 0 个节点",
                "■ 4. 单个节点很快但卡顿 → 开 smart 模式，国内直连不走代理",
                "■ 5. 某 App 无法联网 → 在 adv - app exclude 把它加到分流排除名单",
                "■ 6. 自检项（都绿了 = 配置正确）：",
                "     [●] 节点 ≥ 10 且存活 ≥ 1    [●] 验证速度 > 20 KB/s    [●] 已启动 VPN",
            )
            Column(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 12.dp)) {
                helpLines.forEach { ln ->
                    Text(
                        ln,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (ln.startsWith("■")) OnSurfaceDim else OnSurfaceDim,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }

            // ==================== about ====================
            GroupHeader("about")
            Text(
                "Relay $VERSION · local-only proxy client",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = OnSurfaceDim,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 40.dp),
            )
        }
    }

    // ==================== test url 编辑 ====================
    if (showUrlEdit) {
        var draft by remember { mutableStateOf(testUrl) }
        AlertDialog(
            containerColor = Surface2,
            shape = RoundedCornerShape(14.dp),
            onDismissRequest = { showUrlEdit = false },
            title = {
                Text("> test url", color = OnSurface, fontFamily = FontFamily.Monospace)
            },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("https://www.google.com", color = OnSurfaceDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Seed, unfocusedBorderColor = Outline,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.setTestUrl(draft.trim()); showUrlEdit = false }) {
                    Text("[SAVE]", color = Seed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlEdit = false }) {
                    Text("[ESC]", color = OnSurfaceDim, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }

    // ==================== app exclude 选择器（保留原功能，换 terminal 壳） ====================
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
                Text("> exclude apps", color = OnSurface, fontFamily = FontFamily.Monospace)
            },
            text = {
                Column {
                    if (appListLoading) {
                        Box(
                            Modifier.fillMaxWidth().padding(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Seed, strokeWidth = 2.dp, modifier = Modifier.width(28.dp).height(28.dp))
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
                                Text("> need app-list permission",
                                    color = Warn, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Android 11+ 默认不允许读取全部 App。\n点下方按钮跳转系统设置开权限后返回。",
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
                                    Text("> grant", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("search app…", color = OnSurfaceDim, fontFamily = FontFamily.Monospace) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Seed, unfocusedBorderColor = Outline,
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (showSystem) "[●]" else "[○]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (showSystem) Seed else OnSurfaceMute,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showSystem = !showSystem }
                                    .padding(horizontal = 2.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("system", color = OnSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.weight(1f))
                            Text("${draft.size}/${filtered.size}", color = OnSurfaceDim, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.height(320.dp).clip(RoundedCornerShape(10.dp))
                            .background(Surface3)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (filtered.isEmpty()) {
                                    Text("> no match", Modifier.padding(16.dp), color = OnSurfaceDim,
                                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                } else {
                                    filtered.forEach { app: AppEntry ->
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
                                            Text(
                                                if (checked) "[○]" else "[ ]",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 14.sp,
                                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                                                color = if (checked) Seed else OnSurfaceMute,
                                                modifier = Modifier.width(36.dp),
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(app.label, color = OnSurface, fontSize = 13.sp)
                                                Text(app.pkg, color = OnSurfaceDim, fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace)
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
                    }) { Text("[SAVE]", color = Seed, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("[ESC]", color = OnSurfaceDim, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

// ==================== terminal 组件 ====================

/** 分组标题：-- io -- + 分隔线 */
@Composable
private fun GroupHeader(title: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "-- $title --",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceMute,
            modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp),
        )
        Box(
            Modifier.fillMaxWidth().height(0.5.dp).background(Outline.copy(alpha = 0.3f))
        )
    }
}

/** 导航行：`> label` + 右侧 value，整行可点 */
@Composable
private fun TNav(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "> $label",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = OnSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            color = OnSurfaceDim,
            maxLines = 1,
        )
    }
}

/** 开关行：`[●]/[○] label` + sub */
@Composable
private fun TSwitch(
    label: String,
    sub: String,
    checked: Boolean,
    color: Color,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) "[●]" else "[○]",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (checked) color else OnSurfaceMute,
            modifier = Modifier.width(32.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OnSurface,
            )
            Text(
                sub,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = OnSurfaceDim,
            )
        }
    }
}

/** 步进行：`[-] label: valueunit [+]` */
@Composable
private fun TStepper(
    label: String,
    value: Int,
    unit: String,
    range: IntRange,
    step: Int,
    color: Color,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "[-]",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceDim,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = value - step >= range.first) { onChange(value - step) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = OnSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$value$unit",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "[+]",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceDim,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = value + step <= range.last) { onChange(value + step) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
