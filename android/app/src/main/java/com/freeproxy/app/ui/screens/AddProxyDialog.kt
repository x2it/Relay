package com.freeproxy.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.discover.ProxyUrlParser
import com.freeproxy.app.ui.theme.OnSurface
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.Outline
import com.freeproxy.app.ui.theme.Seed

/**
 * 手动添加代理对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProxyDialog(
    onDismiss: () -> Unit,
    onAdd: (ProxyInfo) -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProxyType.HTTP) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 粘贴串变化时自动解析
    LaunchedEffect(pasted) {
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
                Text("添加并测L1", color = Seed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = OnSurfaceDim)
            }
        },
        title = { Text("手动添加代理", style = MaterialTheme.typography.titleLarge, color = OnSurface) },
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
                    label = { Text("粘贴代理串 (例 socks5://u:p@1.2.3.4:1080)", color = OnSurfaceDim) },
                    singleLine = true,
                    isError = errorMsg != null,
                    colors = outlinedColors(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))

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
                androidx.compose.foundation.layout.Box {
                    FilterChip(
                        selected = true,
                        onClick = { typeExpanded = !typeExpanded },
                        label = { Text("类型：${type.value}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Seed.copy(alpha = 0.12f),
                            selectedLabelColor = Seed,
                        ),
                    )
                    DropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        listOf(ProxyType.HTTP, ProxyType.HTTPS, ProxyType.SOCKS4, ProxyType.SOCKS5).forEach { t ->
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
                        label = { Text("用户 (可空)", color = OnSurfaceDim) },
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
                    Text(it, color = com.freeproxy.app.ui.theme.Bad,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = androidx.compose.ui.graphics.Color.White,
    )
}

@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Seed, unfocusedBorderColor = Outline,
    focusedLabelColor = Seed, unfocusedLabelColor = OnSurfaceDim,
)
