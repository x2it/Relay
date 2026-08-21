package com.freeproxy.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freeproxy.app.R
import com.freeproxy.app.data.model.AnonymityLevel
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.data.model.SpeedLevel
import com.freeproxy.app.ui.ProxyListState
import com.freeproxy.app.ui.QuickChip
import com.freeproxy.app.ui.SortKey
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
import kotlinx.coroutines.launch

private val COMMON_COUNTRIES = listOf("CN", "US", "DE", "JP", "FR", "GB", "IN", "BR")

private val CHIP_LABELS = mapOf(
    QuickChip.ALL to "全部",
    QuickChip.USABLE_L2 to "仅可用",
    QuickChip.GOOGLE to "Google",
    QuickChip.YOUTUBE to "YouTube",
    QuickChip.FACEBOOK to "Facebook",
    QuickChip.ELITE to "高匿",
    QuickChip.HTTPS_TUNNEL to "HTTPS隧道",
    QuickChip.SOCKS5 to "SOCKS5",
)

private val SORT_LABELS = mapOf(
    SortKey.COMPOSITE to "综合",
    SortKey.LATENCY to "延迟",
    SortKey.SPEED to "速度",
    SortKey.SUCCESS to "成功率",
    SortKey.NEWEST to "最新",
)

/** 首屏常驻的 4 个 chip（高频）；其余进「更多」 */
private val PRIMARY_CHIPS = listOf(
    QuickChip.ALL, QuickChip.USABLE_L2, QuickChip.GOOGLE, QuickChip.SOCKS5,
)

/**
 * 重设计原则：
 *  - 顶栏极简：只有标题 + 单一溢出菜单
 *  - 单行搜索 + 筛选入口（带计数）
 *  - 筛选面板默认收起，展开后分两区：常用 chip / 二级筛选项
 *  - 行极简：国家色块 + 主信息单行 + 副信息单行 + 测速按钮
 *  - 去左滑删除：长按行打开操作菜单（删除/测速/选中）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyListScreen(
    state: ProxyListState,
    onDiscover: () -> Unit,
    onTestAll: () -> Unit,
    onTestOne: (ProxyInfo) -> Unit,
    onSelect: (ProxyInfo) -> Unit,
    onDelete: (ProxyInfo) -> Unit,
    onClearBad: () -> Unit,
    onClearAll: () -> Unit,
    onImport: () -> Unit,
    onExportJson: () -> Unit,
    onExportTxt: () -> Unit,
    onQueryChange: (String) -> Unit,
    onType: (ProxyType?) -> Unit,
    onAnon: (AnonymityLevel?) -> Unit,
    onSpeed: (SpeedLevel?) -> Unit,
    onChip: (QuickChip, Boolean) -> Unit,
    onSort: (SortKey) -> Unit,
    onToggleCountry: (String) -> Unit,
    onSetCountries: (List<String>) -> Unit,
    onResetFilters: () -> Unit,
    onSmartScan: () -> Unit,
    onTestCurrent: () -> Unit,
    onClearBad8: () -> Unit,
    onDeleteDupes: () -> Unit,
    onOpenAdd: () -> Unit,
    onAddProxy: (ProxyInfo) -> Unit,
    onAddBack: (ProxyInfo) -> Unit,
    onTestL1: (ProxyInfo) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showCountryDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<ProxyInfo?>(null) }

    val activeFilterCount = remember(
        state.activeChips, state.type, state.anon, state.speed, state.countries
    ) {
        var n = state.activeChips.size
        if (state.type != null) n++
        if (state.anon != null) n++
        if (state.speed != null) n++
        if (state.countries.isNotEmpty()) n++
        n
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Surface2,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = OnSurface,
                    contentColor = Color.White,
                    actionColor = Seed,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Seed,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(4.dp),
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_plus),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { Text("添加", fontWeight = FontWeight.SemiBold) },
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "代理列表",
                            style = MaterialTheme.typography.titleLarge,
                            color = OnSurface,
                        )
                        val usable = state.items.count { it.workCount >= 1 }
                        Text(
                            "${state.items.size} 条 · 可用 $usable",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            fontSize = 11.sp,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_more_vert),
                            contentDescription = null,
                            tint = OnSurfaceDim,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("发现代理") },
                            onClick = { menuOpen = false; onDiscover() },
                            leadingIcon = { IconRes(id = R.drawable.ic_discover, tint = Seed, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("测速全部代理") },
                            onClick = { menuOpen = false; onTestAll() },
                            leadingIcon = { IconRes(id = R.drawable.ic_speed, tint = Seed, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("智能筛选") },
                            onClick = { menuOpen = false; onSmartScan() },
                            leadingIcon = { IconRes(id = R.drawable.ic_check, tint = Good, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("验证当前列表") },
                            onClick = { menuOpen = false; onTestCurrent() },
                            leadingIcon = { IconRes(id = R.drawable.ic_speed, tint = Seed, size = 18.dp) },
                        )
                        MenuDivider()
                        DropdownMenuItem(
                            text = { Text("清理失效代理") },
                            onClick = { menuOpen = false; onClearBad8() },
                            leadingIcon = { IconRes(id = R.drawable.ic_trash, tint = Bad, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("合并重复代理") },
                            onClick = { menuOpen = false; onDeleteDupes() },
                            leadingIcon = { IconRes(id = R.drawable.ic_refresh, tint = Warn, size = 18.dp) },
                        )
                        MenuDivider()
                        DropdownMenuItem(
                            text = { Text("导入代理") },
                            onClick = { menuOpen = false; onImport() },
                            leadingIcon = { IconRes(id = R.drawable.ic_import, tint = OnSurface, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("导出为 JSON") },
                            onClick = { menuOpen = false; onExportJson() },
                            leadingIcon = { IconRes(id = R.drawable.ic_file_json, tint = Good, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("导出为 TXT") },
                            onClick = { menuOpen = false; onExportTxt() },
                            leadingIcon = { IconRes(id = R.drawable.ic_export, tint = Warn, size = 18.dp) },
                        )
                        MenuDivider()
                        DropdownMenuItem(
                            text = { Text("清理失败代理") },
                            onClick = { menuOpen = false; onClearBad() },
                            leadingIcon = { IconRes(id = R.drawable.ic_trash, tint = Bad, size = 18.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("清空全部代理") },
                            onClick = { menuOpen = false; onClearAll() },
                            leadingIcon = { IconRes(id = R.drawable.ic_trash, tint = Bad, size = 18.dp) },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 顶部进度条
            AnimatedVisibility(visible = state.busy) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { state.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Seed, trackColor = Seed.copy(alpha = 0.15f),
                    )
                    state.progressText?.let {
                        Text(
                            it,
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                        )
                    }
                }
            }

            if (state.items.isEmpty() && state.query.isBlank() && state.activeChips.isEmpty()) {
                EmptyHint(onDiscover)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 搜索 + 筛选切换
                    item {
                        Spacer(Modifier.height(4.dp))
                        SearchAndFilterBar(
                            query = state.query,
                            onQueryChange = onQueryChange,
                            showFilters = showFilters,
                            activeFilterCount = activeFilterCount,
                            onToggleFilters = { showFilters = !showFilters },
                            sortKey = state.sortKey,
                            onSort = onSort,
                        )
                    }

                    // 展开式筛选面板
                    item {
                        AnimatedVisibility(
                            visible = showFilters,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            FilterPanel(
                                state = state,
                                onChip = onChip,
                                onSort = onSort,
                                onOpenCountries = { showCountryDialog = true },
                                onType = onType,
                                onAnon = onAnon,
                                onSpeed = onSpeed,
                                onReset = {
                                    onResetFilters()
                                    showFilters = false
                                },
                            )
                        }
                    }

                    item { Spacer(Modifier.height(2.dp)) }

                    // 列表
                    items(state.items, key = { it.id }) { p ->
                        ProxyRow(
                            p = p,
                            testing = state.testing.contains(p.id),
                            onTest = { onTestOne(p) },
                            onSelect = { onSelect(p) },
                            onLongPress = { actionTarget = p },
                        )
                    }

                    // 底部统计 + FAB 间距
                    item {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            val usable = state.items.count { it.workCount >= 1 }
                            Text(
                                "共 ${state.items.size} 个代理 · $usable 个可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                            Spacer(Modifier.height(96.dp))
                        }
                    }
                }
            }
        }
    }

    // 长按操作菜单
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            confirmButton = {},
            title = { Text("代理操作", color = OnSurface, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    target.latencyMs?.let {
                        Text("延迟：${it}ms", color = OnSurfaceDim, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("代理：${target.display()}", color = OnSurfaceDim, fontSize = 12.sp)
                    if (target.workCount + target.failCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("成功率：${target.successRate() * 100}%（成功 ${target.workCount} / 失败 ${target.failCount}）",
                            color = OnSurfaceDim, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { actionTarget = null }) {
                        Text("取消", color = OnSurfaceDim)
                    }
                    TextButton(onClick = {
                        onTestOne(target); actionTarget = null
                    }) { Text("测速", color = Seed) }
                    TextButton(onClick = {
                        onSelect(target)
                        actionTarget = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "已选中 ${target.display()}",
                                withDismissAction = true,
                            )
                        }
                    }) { Text("选中", color = Seed) }
                    TextButton(onClick = {
                        actionTarget = null
                        onDelete(target)
                        scope.launch {
                            val r = snackbarHostState.showSnackbar(
                                message = "已删除 1 条",
                                actionLabel = "撤销",
                                withDismissAction = true,
                            )
                            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                onAddBack(target)
                            }
                        }
                    }) { Text("删除", color = Bad) }
                }
            },
        )
    }

    if (showCountryDialog) {
        CountryPickerDialog(
            selected = state.countries,
            onDismiss = { showCountryDialog = false },
            onConfirm = { codes -> onSetCountries(codes); showCountryDialog = false },
            onToggle = onToggleCountry,
        )
    }

    if (showAddDialog) {
        AddProxyDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { info ->
                onAddProxy(info)
                showAddDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "已添加，正在后台测 L1 TCP",
                        withDismissAction = true,
                    )
                }
            },
        )
    }
}

// ========================================================================
// 搜索 + 筛选切换 + 排序：单行
// ========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    showFilters: Boolean,
    activeFilterCount: Int,
    onToggleFilters: () -> Unit,
    sortKey: SortKey,
    onSort: (SortKey) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface2),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 搜索框 + 筛选切换按钮
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索代理…", color = OnSurfaceDim) },
                singleLine = true,
                leadingIcon = {
                    IconRes(id = R.drawable.ic_search, tint = OnSurfaceDim, size = 18.dp)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Seed, unfocusedBorderColor = Outline,
                    focusedLeadingIconColor = Seed, unfocusedLeadingIconColor = OnSurfaceDim,
                ),
                shape = RoundedCornerShape(14.dp),
            )
            // 筛选按钮：用 ic_filter 而非 ic_more_vert
            FilterToggleChip(
                active = showFilters,
                count = activeFilterCount,
                onClick = onToggleFilters,
            )
        }
        // 排序入口
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { sortExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "排序：${SORT_LABELS[sortKey] ?: sortKey.name}",
                        color = Seed, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(4.dp))
                    IconRes(id = R.drawable.ic_chevron_right, tint = Seed, size = 12.dp)
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    SortKey.values().forEach { sk ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(SORT_LABELS[sk] ?: sk.name)
                                    if (sortKey == sk) {
                                        Spacer(Modifier.width(8.dp))
                                        IconRes(id = R.drawable.ic_check, tint = Seed, size = 14.dp)
                                    }
                                }
                            },
                            onClick = { onSort(sk); sortExpanded = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterToggleChip(active: Boolean, count: Int, onClick: () -> Unit) {
    val bg = if (active || count > 0) Seed.copy(alpha = 0.12f) else Surface3
    val fg = if (active || count > 0) Seed else OnSurfaceDim
    Row(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconRes(id = R.drawable.ic_filter, tint = fg, size = 18.dp)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(Seed),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$count",
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ========================================================================
// 展开式筛选面板：常用 chip 一行 + 二级筛选一行
// ========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterPanel(
    state: ProxyListState,
    onChip: (QuickChip, Boolean) -> Unit,
    onSort: (SortKey) -> Unit,
    onOpenCountries: () -> Unit,
    onType: (ProxyType?) -> Unit,
    onAnon: (AnonymityLevel?) -> Unit,
    onSpeed: (SpeedLevel?) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface3)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 全部快速 chip：横向滚动
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(QuickChip.values().toList(), key = { it.name }) { chip ->
                val selected = chip in state.activeChips ||
                    (chip == QuickChip.ALL && state.activeChips.isEmpty())
                MiniChip(
                    text = CHIP_LABELS[chip] ?: chip.name,
                    selected = selected,
                    onClick = { onChip(chip, !selected) },
                )
            }
        }

        // 二级筛选行
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeChip(state.type, onType)
            AnonChip(state.anon, onAnon)
            SpeedChip(state.speed, onSpeed)
            val cText = if (state.countries.isEmpty()) "国家"
            else "国家(${state.countries.size})"
            MiniChip(
                text = cText,
                selected = state.countries.isNotEmpty(),
                onClick = onOpenCountries,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "重置",
                color = OnSurfaceDim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MiniChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Seed.copy(alpha = 0.12f) else Surface2
    val fg = if (selected) Seed else OnSurfaceDim
    val borderColor = if (selected) Seed.copy(alpha = 0.35f) else Outline.copy(alpha = 0.45f)
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(0.7.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeChip(type: ProxyType?, onType: (ProxyType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MiniChip(
            text = type?.value ?: "类型",
            selected = type != null,
            onClick = { expanded = !expanded },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { expanded = false; onType(null) })
            ProxyType.values().filter { it != ProxyType.UNKNOWN }.forEach { t ->
                DropdownMenuItem(text = { Text(t.value) }, onClick = { expanded = false; onType(t) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnonChip(anon: AnonymityLevel?, onAnon: (AnonymityLevel?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MiniChip(
            text = anon?.value ?: "匿名度",
            selected = anon != null,
            onClick = { expanded = !expanded },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { expanded = false; onAnon(null) })
            AnonymityLevel.values().forEach { a ->
                DropdownMenuItem(text = { Text(a.value) }, onClick = { expanded = false; onAnon(a) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedChip(speed: SpeedLevel?, onSpeed: (SpeedLevel?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MiniChip(
            text = if (speed == null) "速度" else speed.label,
            selected = speed != null,
            onClick = { expanded = !expanded },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { expanded = false; onSpeed(null) })
            SpeedLevel.values().forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { expanded = false; onSpeed(s) })
            }
        }
    }
}

// ========================================================================
// 国家多选 Dialog
// ========================================================================
@Composable
private fun CountryPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onToggle: (String) -> Unit,
) {
    val tempSel = remember(selected) { mutableStateOf(selected.toMutableSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(tempSel.value.toList()) }) {
                Text("确定", color = Seed)
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(emptyList()) }) {
                Text("清空", color = Bad)
            }
        },
        title = { Text("选择国家") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                COMMON_COUNTRIES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { code ->
                            val sel = code in tempSel.value
                            MiniChip(
                                text = code,
                                selected = sel,
                                onClick = {
                                    val n = tempSel.value
                                    if (sel) n.remove(code) else n.add(code)
                                    tempSel.value = n
                                    onToggle(code)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

// ========================================================================
// 代理行：极简双行 + 长按菜单（去除左滑删除）
// ========================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProxyRow(
    p: ProxyInfo,
    testing: Boolean,
    onTest: () -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    val selected = p.selected
    val bg = if (selected) Seed.copy(alpha = 0.05f) else Surface2
    val borderColor = if (selected) Seed else Outline.copy(alpha = 0.4f)
    val borderWidth = if (selected) 1.2.dp else 0.5.dp

    val speedColor = when (p.speedLevel) {
        SpeedLevel.FAST -> Good
        SpeedLevel.MEDIUM -> Warn
        SpeedLevel.SLOW -> Bad
        SpeedLevel.UNKNOWN -> OnSurfaceMute
    }

    val latColor = when {
        p.latencyMs == null -> OnSurfaceMute
        p.latencyMs < 200 -> Good
        p.latencyMs < 800 -> Warn
        else -> Bad
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左：国家 + 速度色块（统一）
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(speedColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (p.countryCode ?: p.country?.take(2) ?: "?").uppercase(),
                    color = speedColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(12.dp))

            // 中：主信息（双行）
            Column(Modifier.weight(1f)) {
                // 行 1：host:port + 类型徽章
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.display(),
                        style = MaterialTheme.typography.titleSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        p.type.value,
                        color = Seed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Seed.copy(alpha = 0.10f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 行 2：延迟 + 速度等级（精简到两个）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.latencyMs?.let { "${it}ms" } ?: "未测",
                        color = latColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (p.speedLevel != SpeedLevel.UNKNOWN) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(4.dp).clip(CircleShape).background(speedColor)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            p.speedLevel.label,
                            color = speedColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    // 已验证 Google/YT/Fb 至少通过一个：用一个小绿点表示
                    if (p.validGoogle || p.validYoutube || p.validFacebook) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(4.dp).clip(CircleShape).background(Good)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("G/Y/F", color = Good, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            // 右：测速按钮
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (testing) Surface3 else Seed.copy(alpha = 0.10f))
                    .clickable(enabled = !testing, onClick = onTest),
                contentAlignment = Alignment.Center,
            ) {
                if (testing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.6.dp,
                        color = Seed,
                    )
                } else {
                    IconRes(id = R.drawable.ic_speed, tint = Seed, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(horizontal = 12.dp)
            .background(Outline.copy(alpha = 0.3f))
    )
}

// ========================================================================
// 空态：与首页呼吸感一致
// ========================================================================
@Composable
private fun EmptyHint(onDiscover: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                .background(Seed.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            IconRes(id = R.drawable.ic_discover, tint = Seed, size = 32.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "暂无代理",
            style = MaterialTheme.typography.titleLarge,
            color = OnSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "点击下方按钮，从公开代理源抓取可用节点",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Seed)
                .clickable(onClick = onDiscover)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconRes(id = R.drawable.ic_discover, tint = Color.White, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("发现代理", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ========================================================================
// 小工具
// ========================================================================
@Composable
private fun IconRes(
    @DrawableRes id: Int,
    tint: Color,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Icon(
        painter = painterResource(id = id),
        contentDescription = null, tint = tint,
        modifier = Modifier.size(size),
    )
}
