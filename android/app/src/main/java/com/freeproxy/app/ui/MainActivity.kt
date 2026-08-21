package com.freeproxy.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freeproxy.app.FreeProxyApp
import com.freeproxy.app.R
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.VpnStatus
import com.freeproxy.app.ui.screens.HomeScreen
import com.freeproxy.app.ui.screens.ProxyListScreen
import com.freeproxy.app.ui.screens.SettingsScreen
import com.freeproxy.app.ui.theme.FreeProxyTheme
import com.freeproxy.app.ui.theme.OnSurfaceDim
import com.freeproxy.app.ui.theme.Seed
import com.freeproxy.app.ui.theme.Surface as SurfaceColor
import com.freeproxy.app.ui.theme.Surface2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var vpnPrepareLauncher: ActivityResultLauncher<Intent>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportJsonLauncher: ActivityResultLauncher<String>
    private lateinit var exportTxtLauncher: ActivityResultLauncher<String>
    private lateinit var exportLogsLauncher: ActivityResultLauncher<String>
    private lateinit var notifPermissionLauncher: ActivityResultLauncher<String>

    private var pendingImport: ((Uri) -> Unit)? = null
    private var pendingExportJson: ((Uri) -> Unit)? = null
    private var pendingExportTxt: ((Uri) -> Unit)? = null
    private var pendingExportLogs: ((Uri) -> Unit)? = null
    private var mainScopeRef: CoroutineScope? = null
    private var snackRef: SnackbarHostState? = null
    private var homeVmRef: HomeViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnPrepareLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { r ->
            val vm = homeVmRef ?: return@registerForActivityResult
            vm.onPrepareResult(r)
            // 授权成功后 onPrepareResult 会用 pending proxy 启动 VPN
        }

        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? -> if (uri != null) pendingImport?.invoke(uri) }

        exportJsonLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? -> if (uri != null) pendingExportJson?.invoke(uri) }

        exportTxtLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri: Uri? -> if (uri != null) pendingExportTxt?.invoke(uri) }

        exportLogsLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri: Uri? -> if (uri != null) pendingExportLogs?.invoke(uri) }

        notifPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 不管授予与否，VPN 都能启动；不授予只是看不到通知 */ }

        // Android 13+ 需要运行时申请通知权限，否则 VPN 前台通知不显示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FreeProxyTheme {
                Surface(color = SurfaceColor, modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        val nav = rememberNavController()
        val snack = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        snackRef = snack
        mainScopeRef = scope

        val homeVm: HomeViewModel = viewModel()
        val listVm: ProxyListViewModel = viewModel()
        val settingsVm: SettingsViewModel = viewModel()
        homeVmRef = homeVm

        val homeState by homeVm.state.collectAsStateWithLifecycle()
        val listState by listVm.state.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) { homeVm.refreshSelected() }

        // VPN 连接成功提示：状态从非 CONNECTED 切到 CONNECTED 时弹一次 Snackbar
        // （对齐同类产品的"已创建 VPN 连接"反馈环节）
        val lastStatus = remember { androidx.compose.runtime.mutableStateOf<VpnStatus?>(null) }
        LaunchedEffect(homeState.vpnStatus) {
            val prev = lastStatus.value
            val cur = homeState.vpnStatus
            lastStatus.value = cur
            if (prev != VpnStatus.CONNECTED && cur == VpnStatus.CONNECTED) {
                snack.showSnackbar(
                    "VPN 连接已创建",
                    duration = SnackbarDuration.Short,
                )
            }
        }

        val repo = (application as FreeProxyApp).repository
        val selectedFlow = remember(repo) {
            kotlinx.coroutines.flow.flow {
                repo.observeAll().collect { list -> emit(list.firstOrNull { it.selected }) }
            }
        }
        val selectedProxy by selectedFlow.collectAsStateWithLifecycle(initialValue = null)

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snack) },
            bottomBar = { BottomNav(nav) },
            containerColor = Color.Transparent,
        ) { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .background(SurfaceColor)
            ) {
                val curRouteForAnim = nav.currentBackStackEntryAsState().value?.destination?.route ?: "home"
                AnimatedContent(
                    targetState = curRouteForAnim,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith
                            fadeOut(animationSpec = tween(220))
                    },
                    label = "tab_switch",
                ) { _ ->
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                state = homeState,
                                selectedFromRepo = selectedProxy ?: homeState.selectedProxy,
                                onToggle = { sel -> toggleConnect(homeVm, sel, snack, scope) },
                                onPickProxy = { nav.navigate("proxies") },
                                autoPickProgress = homeState.autoPickProgress,
                                autoPickMessage = homeState.autoPickMessage,
                                onExportLogs = {
                                    pendingExportLogs = { uri ->
                                        scope.launch {
                                            val n = homeVm.exportLogs(uri)
                                            snack.showSnackbar(
                                                if (n > 0) "已保存 $n 条进程日志"
                                                else "日志为空，未写入",
                                                duration = SnackbarDuration.Short)
                                        }
                                    }
                                    runCatching {
                                        exportLogsLauncher.launch("relay-log-${dateStamp()}.txt")
                                    }
                                },
                                onClearLogs = {
                                    homeVm.clearLogs()
                                    scope.launch {
                                        snack.showSnackbar(
                                            "已清空进程日志",
                                            duration = SnackbarDuration.Short)
                                    }
                                },
                            )
                        }
                        composable("proxies") {
                            ProxyListScreen(
                                state = listState,
                                onDiscover = { listVm.discover() },
                                onTestAll = { listVm.testAll() },
                                onTestOne = { p -> listVm.testOne(p) },
                                onSelect = { p ->
                                    listVm.selectProxy(p)
                                    scope.launch {
                                        snack.showSnackbar(
                                            "已选中 ${p.display()}",
                                            duration = SnackbarDuration.Short)
                                    }
                                    // 选中后自动返回首页，直接点连接即可
                                    nav.navigate("home") {
                                        popUpTo(nav.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onDelete = { p -> listVm.delete(p) },
                                onClearBad = { listVm.clearBad() },
                                onClearAll = { listVm.clearAll() },
                                onImport = launch@{
                                    pendingImport = { uri -> settingsVm.doImport(uri) }
                                    runCatching { importLauncher.launch(arrayOf("*/*")) }
                                },
                                onExportJson = {
                                    pendingExportJson = { uri -> settingsVm.doExport(uri, json = true) }
                                    exportJsonLauncher.launch("relay-${dateStamp()}.json")
                                },
                                onExportTxt = {
                                    pendingExportTxt = { uri -> settingsVm.doExport(uri, json = false) }
                                    exportTxtLauncher.launch("relay-${dateStamp()}.txt")
                                },
                                onQueryChange = listVm::setQuery,
                                onType = listVm::setType,
                                onAnon = listVm::setAnon,
                                onSpeed = listVm::setSpeed,
                                // ===== 新增回调 =====
                                onChip = { c, on -> listVm.setChip(c, on) },
                                onSort = { sk -> listVm.setSort(sk) },
                                onToggleCountry = { code -> listVm.toggleCountry(code) },
                                onSetCountries = { codes -> listVm.setCountriesFrom(codes) },
                                onResetFilters = { listVm.resetFilters() },
                                onSmartScan = { listVm.smartScan() },
                                onTestCurrent = { listVm.testCurrentFiltered() },
                                onClearBad8 = { listVm.clearBadByThreshold(8) },
                                onDeleteDupes = { listVm.deleteDupes() },
                                onOpenAdd = { /* Screen 内部控制 FAB */ },
                                onAddProxy = { info -> listVm.addProxyAndTestL1(info) },
                                onAddBack = { p -> listVm.addBack(p) },
                                onTestL1 = { p -> listVm.testL1(p) },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                vm = settingsVm,
                                autoPick = homeState.autoPickBeforeConnect,
                                onAutoPickToggle = { on -> homeVm.setAutoPickBeforeConnect(on) },
                                localProxyRunning = homeState.localProxyRunning,
                                localProxyPort = homeState.localProxyPort,
                                onStartLocalProxy = homeVm::startLocalProxy,
                                onStopLocalProxy = homeVm::stopLocalProxy,
                                onImport = {
                                    pendingImport = { uri -> settingsVm.doImport(uri) }
                                    runCatching { importLauncher.launch(arrayOf("*/*")) }
                                },
                                onExportJson = {
                                    pendingExportJson = { uri -> settingsVm.doExport(uri, json = true) }
                                    exportJsonLauncher.launch("relay-${dateStamp()}.json")
                                },
                                onExportTxt = {
                                    pendingExportTxt = { uri -> settingsVm.doExport(uri, json = false) }
                                    exportTxtLauncher.launch("relay-${dateStamp()}.txt")
                                },
                            )
                        }
                    }
                }

                // 设置 toast
                val toast by settingsVm.toast.collectAsStateWithLifecycle()
                LaunchedEffect(toast) {
                    val m = toast ?: return@LaunchedEffect
                    snack.showSnackbar(m)
                    settingsVm.consumeToast()
                }
            }
        }
    }

    private fun toggleConnect(
        vm: HomeViewModel,
        selected: ProxyInfo?,
        snack: SnackbarHostState,
        scope: CoroutineScope,
    ) {
        val cur = vm.state.value.vpnStatus
        val needStart = cur != VpnStatus.CONNECTED && cur != VpnStatus.CONNECTING
        if (needStart) {
            // 奥卡姆剃刀：用户已选中代理即视为意图明确，不再用历史失败次数拦截；
            // 仅当确实没有选中代理时，才提示去选择。
            if (selected == null) {
                scope.launch { snack.showSnackbar(getString(R.string.msg_invalid_proxy)) }
                return
            }
            val prepare = android.net.VpnService.prepare(this)
            if (prepare != null) {
                // 先把 selected 塞进 VM 的 pending（VM 内部会记录 pending proxy）
                // 然后把 selected 附带在 intent extra 里（没用上，但保留用于调试）
                val intent = Intent(prepare).putExtra("proxy", selected)
                vpnPrepareLauncher.launch(intent)
                // 让 VM 持有准备好启动的 proxy，授权结果回来后用它
                VpnPrepareBridge.pending = selected
                return
            }
            vm.startVpn(selected)
        } else {
            vm.stopVpn()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BottomNav(nav: androidx.navigation.NavController) {
        val items = listOf(
            BottomItem("home", R.string.tab_home, R.drawable.ic_tab_home),
            BottomItem("proxies", R.string.tab_proxies, R.drawable.ic_tab_proxies),
            BottomItem("settings", R.string.tab_settings, R.drawable.ic_tab_settings),
        )
        val backStackEntry by nav.currentBackStackEntryAsState()
        val curRoute = backStackEntry?.destination?.route
        NavigationBar(
            containerColor = Surface2,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            items.forEach { item ->
                val selected = curRoute == item.route ||
                    backStackEntry?.destination?.hierarchy?.any { it.route == item.route } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        nav.navigate(item.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(id = item.icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (selected) Seed else OnSurfaceDim,
                        )
                    },
                    label = {
                        Text(
                            getString(item.labelRes),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Seed,
                        selectedTextColor = Seed,
                        indicatorColor = Seed.copy(alpha = 0.10f),
                        unselectedIconColor = OnSurfaceDim,
                        unselectedTextColor = OnSurfaceDim,
                    ),
                )
            }
        }
    }

    private data class BottomItem(
        val route: String,
        val labelRes: Int,
        @DrawableRes val icon: Int,
    )

    private fun dateStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

/**
 * VPN 授权结果回来时，Pending 代理从这里取。
 * 这样 HomeViewModel.onPrepareResult 不需要依赖 Activity 上下文。
 */
object VpnPrepareBridge {
    @Volatile var pending: ProxyInfo? = null
}
