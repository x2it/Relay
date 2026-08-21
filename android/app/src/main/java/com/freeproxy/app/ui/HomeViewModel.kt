package com.freeproxy.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeproxy.app.FreeProxyApp
import com.freeproxy.app.data.ProxyRepository
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.VpnStatus
import com.freeproxy.app.net.OkHttpFactory
import com.freeproxy.app.net.ValLevel
import com.freeproxy.app.net.ValidationTester
import com.freeproxy.app.proxy.LocalHttpProxyServer
import com.freeproxy.app.vpn.ProxyVpnService
import com.freeproxy.app.vpn.VpnStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val ts: String, val text: String, val level: Level = Level.INFO) {
    enum class Level { INFO, OK, WARN, ERR }
}

data class HomeState(
    val vpnStatus: VpnStatus = VpnStatus.IDLE,
    val selectedProxy: ProxyInfo? = null,
    val sessionStart: Long? = null,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val rateIn: Long = 0,
    val rateOut: Long = 0,
    val message: String? = null,
    val storedCount: Int = 0,
    val logs: List<LogLine> = emptyList(),
    // ===== T4.2 自动挑最快 =====
    val autoPickBeforeConnect: Boolean = false,
    val autoPickMessage: String? = null,
    val autoPickProgress: Int = 0,
    // ===== T4.4 本机代理服务 =====
    val localProxyPort: Int? = null,
    val localProxyRunning: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = getApplication<FreeProxyApp>()
    private val repo: ProxyRepository = appCtx.repository
    private val validationTester: ValidationTester = appCtx.validationTester

    private val logsFlow = MutableStateFlow<List<LogLine>>(emptyList())
    private var maxLogLines = 80

    // ===== 自动挑最快状态 =====
    private val autoPickMessageFlow = MutableStateFlow<String?>(null)
    private val autoPickProgressFlow = MutableStateFlow(0)

    // ===== 自动挑开关（读写都走 UserPreferences）=====
    private val autoPickFlow = MutableStateFlow(false)

    // ===== 本机代理服务 =====
    private val localProxyPortFlow = MutableStateFlow<Int?>(null)
    private val localProxyRunningFlow = MutableStateFlow(false)
    private var localProxyServer: LocalHttpProxyServer? = null

    // ===== 连接前 auto-pick 正在执行的 Job（避免重入）=====
    @Volatile private var pickingJob: Job? = null

    fun log(text: String, level: LogLine.Level = LogLine.Level.INFO) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = LogLine(ts, text, level)
        logsFlow.value = (logsFlow.value + listOf(line)).takeLast(maxLogLines)
    }

    /** 清空进程日志 */
    fun clearLogs() { logsFlow.value = emptyList() }

    /** 把当前进程日志导出为 TXT（可复用、可留存调试） */
    suspend fun exportLogs(uri: Uri): Int = withContext(Dispatchers.IO) {
        val lines = logsFlow.value
        if (lines.isEmpty()) return@withContext 0
        val dateTag = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val header = "# Relay 连接进程日志  $dateTag" +
            "  共 ${lines.size} 条\n" +
            "# level / time / message\n"
        runCatching {
            appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { w ->
                    w.write(header)
                    lines.forEach { l ->
                        val tag = when (l.level) {
                            LogLine.Level.INFO -> "INFO"
                            LogLine.Level.OK   -> "OK  "
                            LogLine.Level.WARN -> "WARN"
                            LogLine.Level.ERR  -> "ERR "
                        }
                        w.write("[${tag}] ${l.ts}  ${l.text}\n")
                    }
                    w.flush()
                }
            }
        }
        lines.size
    }

    /** 保证首次进入首页时，DB 中选中项会同步到 state */
    fun refreshSelected() {
        viewModelScope.launch {
            val all = repo.observeAll().first()
            if (all.none { it.selected }) {
                val firstUsable = all.firstOrNull { it.isUsable() }
                if (firstUsable != null) repo.setSelected(firstUsable.id)
            }
        }
        // 同步 autoPickBeforeConnect 初始值
        viewModelScope.launch {
            appCtx.userPrefs.autoPickBeforeConnect.collect { v ->
                autoPickFlow.value = v
            }
        }
    }

    fun setAutoPickBeforeConnect(on: Boolean) {
        viewModelScope.launch {
            appCtx.userPrefs.setAutoPickBeforeConnect(on)
            // DataStore flow 会自动回写到 autoPickFlow
        }
    }

    val state: StateFlow<HomeState> = combine(
        VpnStateManager.state,
        repo.observeAll(),
        logsFlow,
        autoPickFlow,
        autoPickMessageFlow,
        autoPickProgressFlow,
        localProxyPortFlow,
        localProxyRunningFlow,
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val vs = args[0] as VpnStateManager.State
        @Suppress("UNCHECKED_CAST")
        val proxies = args[1] as List<ProxyInfo>
        @Suppress("UNCHECKED_CAST")
        val logs = args[2] as List<LogLine>
        val ap = args[3] as Boolean
        val apMsg = args[4] as String?
        val apProg = args[5] as Int
        val lpPort = args[6] as Int?
        val lpRun = args[7] as Boolean
        HomeState(
            vpnStatus = vs.status,
            selectedProxy = vs.activeProxy ?: proxies.firstOrNull { it.selected },
            sessionStart = vs.sessionStart,
            bytesIn = vs.bytesIn,
            bytesOut = vs.bytesOut,
            rateIn = vs.rateIn,
            rateOut = vs.rateOut,
            message = vs.lastError,
            storedCount = proxies.size,
            logs = logs.takeLast(8),
            autoPickBeforeConnect = ap,
            autoPickMessage = apMsg,
            autoPickProgress = apProg,
            localProxyPort = lpPort,
            localProxyRunning = lpRun,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    /**
     * 如果还没给过VPN授权，返回可启动的Intent（需要Activity用registerForActivityResult 发起）；
     * 否则返回 null 表示可直接 startVpn。
     */
    fun prepareVpnIntent(): Intent? = android.net.VpnService.prepare(appCtx)

    fun onPrepareResult(result: ActivityResult) {
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            log("用户取消了 VPN 授权", LogLine.Level.WARN)
            return
        }
        val p = VpnPrepareBridge.pending
            ?: state.value.selectedProxy
            ?: return.also { log("没有可用的代理配置", LogLine.Level.ERR) }
        VpnPrepareBridge.pending = null
        startVpn(p)
    }

    /**
     * 首页大按钮点击入口，返回到底是「已启动连接/断开」还是「需要跳VPN授权」
     */
    sealed class ToggleOutcome {
        data object Switched : ToggleOutcome()
        data object NeedPrepare : ToggleOutcome()
        data class Invalid(val reason: String) : ToggleOutcome()
        data object AutoPicking : ToggleOutcome() // 正在后台挑最快，UI 显示进度
    }

    fun toggle(selected: ProxyInfo?): ToggleOutcome {
        val cur = state.value.vpnStatus
        return when (cur) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> {
                stopVpn()
                ToggleOutcome.Switched
            }
            else -> {
                // 如果开启了自动挑，或者 selected 为空 → 先后台 pickBestFast()，完成后再真正连接
                val shouldAuto = autoPickFlow.value || selected == null
                if (shouldAuto) {
                    if (pickingJob?.isActive == true) return ToggleOutcome.AutoPicking
                    // 异步启动：挑完后自动走 VPN 连接流程（需要授权会走 NeedPrepare）
                    pickingJob = viewModelScope.launch(Dispatchers.IO) {
                        val best = pickBestFast()
                        if (best == null) {
                            autoPickMessageFlow.value = "未找到可用节点，请先发现或导入代理"
                            delay(2500)
                            autoPickMessageFlow.compareAndSet(
                                "未找到可用节点，请先发现或导入代理",
                                null
                            )
                            return@launch
                        }
                        // 把 best 写入 selected，然后发起 VPN（用 post 切换到主线程处理 prepare 检查）
                        withContext(Dispatchers.Main) {
                            val intent = prepareVpnIntent()
                            if (intent != null) {
                                VpnPrepareBridge.pending = best
                                log("等待 VPN 授权…", LogLine.Level.WARN)
                                // 外部 Activity 需要观察 pending 变化，这里我们用 VpnPrepareBridge 桥接
                            } else {
                                startVpn(best)
                            }
                        }
                    }
                    return ToggleOutcome.AutoPicking
                }
                val p = selected
                if (p == null || !p.isUsable()) return ToggleOutcome.Invalid("请先在「代理」页选一个可用代理")
                val intent = prepareVpnIntent()
                if (intent != null) {
                    VpnPrepareBridge.pending = p
                    log("等待 VPN 授权…", LogLine.Level.WARN)
                    ToggleOutcome.NeedPrepare
                } else {
                    startVpn(p)
                    ToggleOutcome.Switched
                }
            }
        }
    }

    // ======================================================================
    // T4.2 自动挑最快：并发测试 n 条，选最快可用
    // ======================================================================

    suspend fun pickBestFast(): ProxyInfo? = withContext(Dispatchers.IO) {
        val n = 10 // autoPickTopNBeforeConnect，缺省用 10
        val testTimeoutMs = runCatching {
            appCtx.userPrefs.testTimeoutMs.first()
        }.getOrDefault(8000)

        // 步骤 1：候选集
        val candidates = runCatching { repo.topValid(n) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                val all = runCatching { repo.getAll() }.getOrElse { emptyList() }
                all.sortedBy { it.latencyMs ?: Long.MAX_VALUE }.take(n)
            }
        if (candidates.isEmpty()) return@withContext null

        autoPickProgressFlow.value = 0
        autoPickMessageFlow.value = "挑最快节点 0/${candidates.size} ..."

        // 步骤 2：并发跑 L3 HTTPS 验证，retries = 0（快速挑）
        val results: MutableMap<Long, Pair<ProxyInfo, com.freeproxy.app.net.ValResult>> =
            java.util.concurrent.ConcurrentHashMap()
        var doneCount = 0

        val deferreds = candidates.map { p ->
            async(Dispatchers.IO) {
                runCatching {
                    val r = validationTester.validate(
                        p,
                        levels = setOf(ValLevel.L3_HTTPS_GOOGLE),
                        timeoutMs = testTimeoutMs,
                        retries = 0,
                    )
                    results[p.id] = p to r
                    // 写回验证结果到 DB
                    if (p.id > 0) {
                        runCatching { repo.updateValidation(p.id, r) }
                    }
                    synchronized(this@HomeViewModel) {
                        doneCount++
                        autoPickProgressFlow.value = (doneCount * 100) / candidates.size
                        autoPickMessageFlow.value =
                            "挑最快节点 $doneCount/${candidates.size} ..."
                    }
                }
            }
        }
        deferreds.forEach { it.await() }

        val list = results.values.toList()

        // 步骤 3：优先级筛选
        // 1) okL3=true 且 latencyMs 最小
        val l3Best = list.filter { it.second.okL3 }
            .minByOrNull { it.second.latencyMs ?: Long.MAX_VALUE }
        if (l3Best != null) {
            autoPickMessageFlow.value =
                "已挑最快(L3) ${l3Best.first.display()} 延迟 ${l3Best.second.latencyMs}ms"
            delay(1500)
            autoPickMessageFlow.value = null
            // 把选中项写入仓库
            runCatching { repo.setSelected(l3Best.first.id) }
            return@withContext l3Best.first
        }
        // 2) L2 ok 最小延迟
        val l2Candidates = list.filter {
            val r = it.second
            r.okL2 || // 如果我们只测了 L3，这里会 okL2=false；降级看 L1+httpsTunnel
                (r.okL1 && r.httpsTunnel) ||
                (it.first.workCount + it.first.failCount > 0 && it.first.successRate() > 0.5)
        }
        val l2Best = l2Candidates
            .minByOrNull { it.second.latencyMs ?: Long.MAX_VALUE }
        if (l2Best != null) {
            autoPickMessageFlow.value =
                "已挑最快(L2回退) ${l2Best.first.display()}"
            delay(1500)
            autoPickMessageFlow.value = null
            runCatching { repo.setSelected(l2Best.first.id) }
            return@withContext l2Best.first
        }
        // 3) workCount+failCount==0（未测过）：随机挑 L1 TCP 通过的
        val l1Best = list.filter { it.second.okL1 }
            .minByOrNull { it.second.latencyMs ?: Long.MAX_VALUE }
        if (l1Best != null) {
            autoPickMessageFlow.value =
                "已挑最快(L1回退) ${l1Best.first.display()}"
            delay(1500)
            autoPickMessageFlow.value = null
            runCatching { repo.setSelected(l1Best.first.id) }
            return@withContext l1Best.first
        }
        // 4) 都不行，null
        autoPickMessageFlow.value = null
        null
    }

    // ======================================================================
    // VPN 启停
    // ======================================================================

    fun startVpn(p: ProxyInfo) {
        if (!p.isUsable()) {
            log("代理不可用 ${p.display()}", LogLine.Level.ERR)
            return
        }
        log("使用 ${p.type.value} ${p.display()} 建立隧道", LogLine.Level.INFO)
        startProxyVpnServiceSvc(p)
        // 10s 内还没连上就提示用户可能失败
        val id = Job()
        viewModelScope.launch(id) {
            delay(12_000)
            val s = VpnStateManager.state.value.status
            if (s == VpnStatus.CONNECTING) {
                log("连接时间较长，可能代理响应慢或已失效", LogLine.Level.WARN)
            }
        }
    }

    fun stopVpn() {
        log("主动断开隧道", LogLine.Level.INFO)
        val svc = Intent(appCtx, ProxyVpnService::class.java)
            .setAction(ProxyVpnService.ACTION_STOP)
        appCtx.startService(svc)
    }

    private fun startProxyVpnServiceSvc(p: ProxyInfo) {
        val svc = Intent(appCtx, ProxyVpnService::class.java)
            .setAction(ProxyVpnService.ACTION_START)
            .putExtra(ProxyVpnService.EXTRA_PROXY, p)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appCtx.startForegroundService(svc)
        } else {
            appCtx.startService(svc)
        }
    }

    // ======================================================================
    // T4.4 本机代理服务启停
    // ======================================================================

    fun startLocalProxy() {
        val cur = localProxyRunningFlow.value
        if (cur) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val selected = state.value.selectedProxy
                val server = LocalHttpProxyServer(
                    baseOkHttp = appCtx.okhttp,
                    selected = selected,
                )
                localProxyServer = server
                server.start(viewModelScope)
                // 等待 start 绑定好端口（最多 1s）
                var waited = 0
                while (server.localPort == 0 && !server.isRunning && waited < 1000) {
                    delay(50); waited += 50
                }
                if (server.isRunning && server.localPort in 1..65535) {
                    localProxyPortFlow.value = server.localPort
                    localProxyRunningFlow.value = true
                    withContext(Dispatchers.Main) {
                        log("本机代理服务已启动 127.0.0.1:${server.localPort}", LogLine.Level.OK)
                    }
                } else {
                    log("本机代理服务启动失败（端口占用？）", LogLine.Level.ERR)
                    runCatching { server.close() }
                    localProxyServer = null
                }
            } catch (t: Throwable) {
                log("本机代理服务异常: ${t.message}", LogLine.Level.ERR)
                VpnStateManager.appendLog(
                    com.freeproxy.app.vpn.LogLevel.ERROR,
                    "LocalHttpProxy start err: ${t.message}"
                )
            }
        }
    }

    fun stopLocalProxy() {
        val server = localProxyServer
        localProxyServer = null
        localProxyRunningFlow.value = false
        localProxyPortFlow.value = null
        runCatching { server?.close() }
        log("本机代理服务已停止", LogLine.Level.INFO)
    }

    // 更新本地代理使用的选中节点（当用户切换选中时调用）
    private fun updateLocalProxySelected(p: ProxyInfo?) {
        localProxyServer?.updateSelected(p)
    }

    /** 让首页观察 VPN 状态变化时追加日志 */
    private var lastStatus: VpnStatus? = null
    fun startStatusLogger() {
        viewModelScope.launch {
            VpnStateManager.state.collect { s ->
                val st = s.status
                // 同步 activeProxy 给本机代理（当 VPN 侧 active 变化时）
                s.activeProxy?.let { updateLocalProxySelected(it) }
                if (st != lastStatus) {
                    when (st) {
                        VpnStatus.CONNECTING -> log("隧道建立中…", LogLine.Level.INFO)
                        VpnStatus.CONNECTED -> {
                            s.activeProxy?.let { p ->
                                log("已连接 ${p.display()}（${p.type.value}）", LogLine.Level.OK)
                            } ?: log("隧道已连接", LogLine.Level.OK)
                        }
                        VpnStatus.DISCONNECTING -> log("隧道关闭中…", LogLine.Level.INFO)
                        VpnStatus.IDLE -> {
                            if (lastStatus == VpnStatus.CONNECTED || lastStatus == VpnStatus.CONNECTING)
                                log("隧道已关闭", LogLine.Level.INFO)
                        }
                        VpnStatus.ERROR -> log(
                            "连接错误：${s.lastError ?: "未知"}",
                            LogLine.Level.ERR
                        )
                    }
                    lastStatus = st
                }
            }
        }
    }

    override fun onCleared() {
        stopLocalProxy()
        super.onCleared()
    }

    init {
        startStatusLogger()
    }
}
