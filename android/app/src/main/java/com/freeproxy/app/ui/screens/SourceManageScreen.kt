package com.freeproxy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeproxy.app.R
import com.freeproxy.app.data.model.ProxySourceEntity
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.discover.ProxySource
import com.freeproxy.app.ui.SourceItem
import com.freeproxy.app.ui.SourceViewModel
import com.freeproxy.app.ui.theme.Bad
import com.freeproxy.app.ui.theme.Good
import com.freeproxy.app.ui.theme.OnSurface
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.OnSurfaceMute
import com.freeproxy.app.ui.theme.Outline
import com.freeproxy.app.ui.theme.Seed
import com.freeproxy.app.ui.theme.Surface2

// terminal 简写映射
private val FMT_SHORT = mapOf(
    ProxySource.Format.AUTO to "auto",
    ProxySource.Format.JSON to "json",
    ProxySource.Format.PLAIN to "txt",
    ProxySource.Format.CSV to "csv",
    ProxySource.Format.HTML to "html",
)

private val TYPE_SHORT: List<Pair<ProxyType?, String>> = listOf(
    null to "auto",
    ProxyType.HTTP to "http",
    ProxyType.HTTPS to "https",
    ProxyType.SOCKS4 to "s4",
    ProxyType.SOCKS5 to "s5",
)

/**
 * 数据源管理（terminal 极简风格）
 * - 统计行：src/on/ok/err/custom 单行等宽
 * - 行：[●] 启停开关 + 名称 + 抓取状态（ok n:35 / err / new）+ url + [ed][rm]
 * - 内置源未入库也可编辑（保存后转为自定义覆盖）与启停（入库持久化）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    vm: SourceViewModel,
    onBack: () -> Unit,
) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    var showEditor by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<SourceItem?>(null) }
    var deletingItem by remember { mutableStateOf<SourceItem?>(null) }

    LaunchedEffect(toast) {
        val m = toast ?: return@LaunchedEffect
        snack.showSnackbar(m, duration = SnackbarDuration.Short)
        vm.consumeToast()
    }

    val on = sources.count { it.source.enabled }
    val ok = sources.count { it.entity?.lastStatus == "OK" }
    val err = sources.count { it.entity?.lastStatus == "ERR" }
    val custom = sources.count { it.entity != null }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(hostState = snack) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "> sources",
                        style = MaterialTheme.typography.titleLarge,
                        color = OnSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                navigationIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = OnSurfaceDim,
                        modifier = Modifier
                            .rotate(180f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onBack)
                            .padding(10.dp),
                    )
                },
                actions = {
                    // 补齐缺失的内置源（误删恢复）
                    Text(
                        "[reset]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Seed,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.restoreBuiltin() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Seed)
                    .clickable {
                        editingItem = null
                        showEditor = true
                    }
                    .padding(horizontal = 18.dp, vertical = 13.dp),
            ) {
                Text(
                    "[+ src]",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // terminal 统计行
            Text(
                "src:${sources.size}  on:$on  ok:$ok  err:$err  custom:$custom",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = OnSurfaceDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            if (sources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "> loading…",
                        color = OnSurfaceDim,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 2.dp, bottom = 96.dp
                    ),
                ) {
                    items(sources, key = { it.source.url }) { item ->
                        SourceRow(
                            item = item,
                            onToggle = { onOff -> vm.toggle(item, onOff) },
                            onEdit = {
                                editingItem = item
                                showEditor = true
                            },
                            onDelete = { deletingItem = item },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        SourceEditorDialog(
            item = editingItem,
            onDismiss = { showEditor = false },
            onSave = { entity ->
                if (editingItem?.entity == null) vm.addSource(entity)
                else vm.updateSource(entity)
                showEditor = false
            },
        )
    }

    deletingItem?.let { item ->
        AlertDialog(
            containerColor = Surface2,
            shape = RoundedCornerShape(14.dp),
            onDismissRequest = { deletingItem = null },
            title = {
                Text(
                    "> del src",
                    color = OnSurface,
                    fontFamily = FontFamily.Monospace,
                )
            },
            text = {
                Text(
                    "rm ${item.source.name} ?\n${item.source.url}",
                    color = OnSurfaceDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    item.entity?.let { vm.deleteSource(it) }
                    deletingItem = null
                }) {
                    Text(
                        "[DEL]",
                        color = Bad,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) {
                    Text(
                        "[ESC]",
                        color = OnSurfaceDim,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
        )
    }
}

@Composable
private fun SourceRow(
    item: SourceItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val src = item.source
    val ent = item.entity
    val enabled = src.enabled
    val statusText: String
    val statusColor: Color
    when (ent?.lastStatus) {
        "OK" -> {
            statusText = "ok n:${ent.lastCount}"
            statusColor = Good
        }
        "ERR" -> {
            statusText = "err"
            statusColor = Bad
        }
        else -> {
            statusText = "new"
            statusColor = OnSurfaceMute
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // terminal 启停开关 [●] / [○]
            Text(
                if (enabled) "[●]" else "[○]",
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Seed else OnSurfaceMute,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggle(!enabled) }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        src.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) OnSurface else OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    src.url,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            // [ed] 编辑：内置未入库也可编辑（保存后覆盖为自定义）
            Text(
                "[ed]",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Good,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onEdit)
                    .padding(6.dp),
            )
            // [rm] 删除：仅入库源可删（内置条目置灰）
            Text(
                "[rm]",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (ent != null) Bad else OnSurfaceMute.copy(alpha = 0.35f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = ent != null, onClick = onDelete)
                    .padding(6.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Outline.copy(alpha = 0.25f))
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceEditorDialog(
    item: SourceItem?,
    onDismiss: () -> Unit,
    onSave: (ProxySourceEntity) -> Unit,
) {
    val editing = item?.source
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var url by remember { mutableStateOf(editing?.url ?: "") }
    var format by remember { mutableStateOf(editing?.format ?: ProxySource.Format.AUTO) }
    var schemeHint by remember { mutableStateOf(editing?.schemeHint) }
    var enabled by remember { mutableStateOf(editing?.enabled ?: true) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        containerColor = Surface2,
        shape = RoundedCornerShape(14.dp),
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (item?.entity == null) "> new src" else "> edit src",
                color = OnSurface,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        err = null
                    },
                    label = {
                        Text(
                            "name",
                            color = OnSurfaceDim,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    placeholder = { Text("my source", color = OnSurfaceDim) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Seed,
                        unfocusedBorderColor = Outline,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        err = null
                    },
                    label = {
                        Text(
                            "url",
                            color = OnSurfaceDim,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    placeholder = { Text("https://…/list.txt", color = OnSurfaceDim) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Seed,
                        unfocusedBorderColor = Outline,
                    ),
                )
                err?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        color = Bad,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "fmt",
                    color = OnSurfaceDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ProxySource.Format.entries.forEach { f ->
                        TChip(FMT_SHORT[f] ?: f.name.lowercase(), format == f) { format = f }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "type",
                    color = OnSurfaceDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TYPE_SHORT.forEach { (t, label) ->
                        TChip(label, schemeHint == t) { schemeHint = t }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (enabled) "[●] enabled" else "[○] enabled",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (enabled) Seed else OnSurfaceDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { enabled = !enabled }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                val u = url.trim()
                when {
                    n.isEmpty() -> err = "! name required"
                    u.isEmpty() -> err = "! url required"
                    else -> onSave(
                        ProxySourceEntity(
                            id = item?.entity?.id ?: 0,
                            name = n,
                            url = u,
                            format = format.name,
                            schemeHint = schemeHint?.name,
                            timeoutMs = item?.entity?.timeoutMs ?: 15_000,
                            enabled = enabled,
                            mirrors = item?.entity?.mirrors ?: "",
                            category = item?.entity?.category ?: "CUSTOM",
                        )
                    )
                }
            }) {
                Text(
                    "[SAVE]",
                    color = Seed,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "[ESC]",
                    color = OnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
    )
}

/** terminal 风格选择 chip：选中 [text] 括号高亮 */
@Composable
private fun TChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        if (selected) "[$text]" else " $text ",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) Seed else OnSurfaceDim,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Seed.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}
