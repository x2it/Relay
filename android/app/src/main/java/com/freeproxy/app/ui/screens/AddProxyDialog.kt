package com.freeproxy.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.discover.ProxyUrlParser
import com.freeproxy.app.discover.ShareLinkParser
import com.freeproxy.app.ui.theme.Bad
import com.freeproxy.app.ui.theme.OnSurface
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.Outline
import com.freeproxy.app.ui.theme.Seed

/**
 * 手动添加代理对话框
 * 支持：明文代理（http/https/socks）粘贴解析、vmess/trojan/vless/ss 加密分享链接、扫码自动填入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProxyDialog(
    onDismiss: () -> Unit,
    onAdd: (ProxyInfo) -> Unit,
    initialPaste: String? = null,
    onScan: (() -> Unit)? = null,
) {
    var pasted by remember { mutableStateOf(initialPaste ?: "") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProxyType.HTTP) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // 加密分享链接原文（配置保留，提交时原样入库）
    var shareConfig by remember { mutableStateOf<ProxyInfo?>(null) }

    // 粘贴串变化时自动解析
    LaunchedEffect(pasted) {
        shareConfig = null
        // 1) 加密分享链接优先
        val share = ShareLinkParser.parse(pasted)
        if (share != null) {
            host = share.host
            port = share.port.toString()
            type = share.type
            user = share.username ?: ""
            pass = share.password ?: ""
            shareConfig = share
            return@LaunchedEffect
        }
        // 2) 明文 URL/host:port
        val p = ProxyUrlParser.parse(pasted) ?: return@LaunchedEffect
        host = p.host
        port = p.port.toString()
        type = p.type
        user = p.username ?: ""
        pass = p.password ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && port.toIntOrNull() in 1..65535,
                onClick = {
                    // 加密分享链接：原样提交（含 configJson）
                    shareConfig?.let { sc ->
                        onAdd(sc)
                        return@TextButton
                    }
                    val portInt = port.toIntOrNull()
                    if (portInt == null || portInt !in 1..65535) {
                        errorMsg = "端口必须在 1..65535"
                        return@TextButton
                    }
                    if (host.isBlank()) {
                        errorMsg = "主机不能为空"
                        return@TextButton
                    }
                    val info = ProxyInfo(
                        host = host.trim(),
                        port = portInt,
                        type = type,
                        username = user.ifBlank { null },
                        password = pass.ifBlank { null },
                        source = "manual",
                    )
                    onAdd(info)
                }
            ) {
                Text("[ 添加并测L1 ]", color = Seed, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("[ 取消 ]", color = OnSurfaceDim, fontFamily = FontFamily.Monospace)
            }
        },
        title = {
            Text(
                "> add node",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                fontFamily = FontFamily.Monospace,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it; errorMsg = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "paste 明文 / vmess / trojan / vless / ss",
                            color = OnSurfaceDim,
                        )
                    },
                    singleLine = true,
                    isError = errorMsg != null,
                    colors = outlinedColors(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))

                // 扫码入口：打开内置扫码器，结果自动填入上面的粘贴框
                onScan?.let { scan ->
                    TextButton(onClick = scan) {
                        Text(
                            "[ 扫码添加 ]",
                            color = Seed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; errorMsg = null },
                        modifier = Modifier.weight(1f),
                        label = { Text("Host", color = OnSurfaceDim) },
                        singleLine = true,
                        colors = outlinedColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }; errorMsg = null },
                        modifier = Modifier.width(100.dp),
                        label = { Text("Port", color = OnSurfaceDim) },
                        singleLine = true,
                        colors = outlinedColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Type 下拉
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { typeExpanded = !typeExpanded },
                        label = { Text("type: ${type.value.lowercase()}", fontFamily = FontFamily.Monospace) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Seed.copy(alpha = 0.12f),
                            selectedLabelColor = Seed,
                        ),
                    )
                    DropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        val plainTypes = listOf(
                            ProxyType.HTTP, ProxyType.HTTPS, ProxyType.SOCKS4, ProxyType.SOCKS5,
                        )
                        val nodeTypes = listOf(
                            ProxyType.VMESS, ProxyType.TROJAN, ProxyType.VLESS, ProxyType.SHADOWSOCKS,
                        )
                        Text(
                            "-- plain --",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        plainTypes.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.value) },
                                onClick = { type = t; typeExpanded = false },
                            )
                        }
                        Text(
                            "-- encrypted --",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        nodeTypes.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.value) },
                                onClick = { type = t; typeExpanded = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("用户/ID (可空)", color = OnSurfaceDim) },
                        singleLine = true,
                        colors = outlinedColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("密码 (可空)", color = OnSurfaceDim) },
                        singleLine = true,
                        colors = outlinedColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                errorMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Bad, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = Color.White,
    )
}

@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Seed, unfocusedBorderColor = Outline,
    focusedLabelColor = Seed, unfocusedLabelColor = OnSurfaceDim,
)
