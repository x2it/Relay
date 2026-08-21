package com.freeproxy.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeproxy.app.FreeProxyApp
import com.freeproxy.app.data.ProxyRepository
import com.freeproxy.app.data.UserPreferences
import com.freeproxy.app.data.model.AnonymityLevel
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.data.model.SpeedLevel
import com.freeproxy.app.discover.ProxyDiscoverer
import com.freeproxy.app.net.SpeedTester
import com.freeproxy.app.net.ValLevel
import com.freeproxy.app.net.ValResult
import com.freeproxy.app.net.ValidationTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2
import kotlin.math.max

// ========================================================================
// 新增筛选/排序枚举
// ========================================================================
enum class SortKey { COMPOSITE, LATENCY, SPEED, SUCCESS, NEWEST }

enum class QuickChip { ALL, USABLE_L2, GOOGLE, YOUTUBE, FACEBOOK, ELITE, HTTPS_TUNNEL, SOCKS5 }

// ========================================================================
// ProxyListState 追加字段
// ========================================================================
data class ProxyListState(
    val items: List<ProxyInfo> = emptyList(),
    val busy: Boolean = false,
    val progressText: String? = null,
    val progressPct: Int = 0,
    val query: String = "",
    val type: ProxyType? = null,
    val anon: AnonymityLevel? = null,
    val speed: SpeedLevel? = null,
    val testing: Set<Long> = emptySet(),
    val sortKey: SortKey = SortKey.COMPOSITE,
    val activeChips: Set<QuickChip> = emptySet(),
    val countries: Set<String> = emptySet(),
)

class ProxyListViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = getApplication<FreeProxyApp>()
    private val repo: ProxyRepository = appCtx.repository
    private val discoverer: ProxyDiscoverer = appCtx.discoverer
    private val tester: SpeedTester = appCtx.speedTester
    private val validationTester: ValidationTester = appCtx.validationTester
    private val prefs: UserPreferences = appCtx.userPrefs

    // 原 9 个 flow
    private val busy = MutableStateFlow(false)
    private val progressText = MutableStateFlow<String?>(null)
    private val progressPct = MutableStateFlow(0)
    private val query = MutableStateFlow("")
    private val fType = MutableStateFlow<ProxyType?>(null)
    private val fAnon = MutableStateFlow<AnonymityLevel?>(null)
    private val fSpeed = MutableStateFlow<SpeedLevel?>(null)
    private val testing = MutableStateFlow<Set<Long>>(emptySet())

    // 新状态（也做成 MutableStateFlow，直接进入 combine 数组可变参数）
    private val sortKey = MutableStateFlow(SortKey.COMPOSITE)
    private val activeChips = MutableStateFlow<Set<QuickChip>>(emptySet())
    private val countries = MutableStateFlow<Set<String>>(emptySet())

    // combine: 保持原 args 0..8 解包风格不变，追加 3 个 (9..11)
    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<ProxyListState> =
        combine(
            busy, progressText, progressPct, query, fType, fAnon, fSpeed, testing,
            repo.observeAll(),
            sortKey, activeChips, countries,
        ) { args ->
            val bs = args[0] as Boolean
            val pt = args[1] as String?
            val pp = args[2] as Int
            val q = args[3] as String
            val ft = args[4] as ProxyType?
            val fa = args[5] as AnonymityLevel?
            val fs = args[6] as SpeedLevel?
            val te = args[7] as Set<Long>
            val all = args[8] as List<ProxyInfo>
            val sk = args[9] as SortKey
            val chips = args[10] as Set<QuickChip>
            val cntrs = args[11] as Set<String>

            val filtered = all.asSequence()
                .filter { ft == null || it.type == ft }
                .filter { fa == null || it.anonymity == fa }
                .filter { fs == null || it.speedLevel == fs }
                .filter { p ->
                    q.isBlank() ||
                        p.host.contains(q, true) ||
                        (p.country ?: "").contains(q, true) ||
                        (p.source ?: "").contains(q, true)
                }
                .applyChips(chips)
                .filter { p ->
                    cntrs.isEmpty() ||
                        (p.countryCode?.uppercase() ?: "") in cntrs.map { it.uppercase() }.toSet()
                }
                .sortedWith(comparatorFor(sk))
                .toList()
            ProxyListState(
                items = filtered, busy = bs, progressText = pt, progressPct = pp,
                query = q, type = ft, anon = fa, speed = fs, testing = te,
                sortKey = sk, activeChips = chips, countries = cntrs,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ProxyListState())

    // ------------------ Chips 过滤 ------------------
    private fun Sequence<ProxyInfo>.applyChips(chips: Set<QuickChip>): Sequence<ProxyInfo> {
        if (chips.isEmpty() || QuickChip.ALL in chips) return this
        var s = this
        if (QuickChip.USABLE_L2 in chips) s = s.filter { it.workCount >= 1 || it.latencyMs != null }
        if (QuickChip.GOOGLE in chips) s = s.filter { it.validGoogle }
        if (QuickChip.YOUTUBE in chips) s = s.filter { it.validYoutube }
        if (QuickChip.FACEBOOK in chips) s = s.filter { it.validFacebook }
        if (QuickChip.ELITE in chips) s = s.filter { it.anonymity == AnonymityLevel.ELITE }
        if (QuickChip.HTTPS_TUNNEL in chips) s = s.filter { it.httpsTunnel }
        if (QuickChip.SOCKS5 in chips) s = s.filter { it.type == ProxyType.SOCKS5 }
        return s
    }

    // ------------------ 排序 Comparator ------------------
    private fun comparatorFor(sk: SortKey): Comparator<ProxyInfo> = when (sk) {
        SortKey.COMPOSITE ->
            compareByDescending<ProxyInfo> { (it.workCount + it.failCount) > 0 }
                .thenByDescending { compositeScore(it) }
        SortKey.LATENCY ->
            compareBy<ProxyInfo> { it.latencyMs == null }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        SortKey.SPEED ->
            compareBy<ProxyInfo> { it.downloadKbps == null }
                .thenByDescending { it.downloadKbps ?: 0L }
        SortKey.SUCCESS ->
            compareByDescending<ProxyInfo> { it.workCount }
                .thenBy { it.failCount }
        SortKey.NEWEST ->
            compareByDescending { it.createdAt }
    }

    private fun compositeScore(p: ProxyInfo): Double {
        val rate = p.successRate()
        val lat = p.latencyMs?.let {
            ((1000.0 - it) / 1000.0).coerceIn(0.0, 1.0)
        } ?: 0.0
        val kbps = p.downloadKbps ?: 0L
        val sp = (log2(max(kbps, 1L).toDouble()) / 12.0).coerceIn(0.0, 1.0)
        return rate * 0.5 + lat * 0.3 + sp * 0.2
    }

    // ========================================================================
    // Actions
    // ========================================================================

    fun setQuery(q: String) { query.value = q }
    fun setType(t: ProxyType?) { fType.value = t }
    fun setAnon(a: AnonymityLevel?) { fAnon.value = a }
    fun setSpeed(s: SpeedLevel?) { fSpeed.value = s }

    fun setChip(c: QuickChip, on: Boolean) {
        val next = activeChips.value.toMutableSet()
        if (on) {
            if (c == QuickChip.ALL) {
                next.clear()
                next.add(QuickChip.ALL)
            } else {
                next.remove(QuickChip.ALL)
                next.add(c)
            }
        } else {
            next.remove(c)
        }
        activeChips.value = next
    }

    fun toggleSort(key: SortKey) { sortKey.value = key }
    fun setSort(key: SortKey) { sortKey.value = key }

    fun toggleCountry(code: String) {
        val up = code.uppercase()
        val next = countries.value.toMutableSet()
        if (up in next) next.remove(up) else next.add(up)
        countries.value = next
    }

    fun setCountriesFrom(list: List<String>) {
        countries.value = list.map { it.uppercase() }.toSet()
    }

    fun clearChips() { activeChips.value = emptySet() }

    fun resetFilters() {
        query.value = ""
        fType.value = null
        fAnon.value = null
        fSpeed.value = null
        activeChips.value = emptySet()
        countries.value = emptySet()
        sortKey.value = SortKey.COMPOSITE
    }

    // ========================================================================
    // 代理发现（保留）
    // ========================================================================
    private var jobDiscover: Job? = null
    fun discover() {
        if (jobDiscover?.isActive == true) return
        jobDiscover = viewModelScope.launch {
            busy.value = true
            progressPct.value = 0
            progressText.value = getStr(com.freeproxy.app.R.string.msg_fetching)
            discoverer.discover { p ->
                progressPct.value = (p.fetched * 100f / p.total.coerceAtLeast(1)).toInt()
                progressText.value = p.message
            }
            progressText.value = getStr(com.freeproxy.app.R.string.msg_fetch_done, repo.count())
            busy.value = false
        }
    }

    // ========================================================================
    // 选中 / 删除（保留）
    // ========================================================================
    fun selectProxy(p: ProxyInfo) = viewModelScope.launch { repo.setSelected(p.id) }
    fun clearSelected() = viewModelScope.launch { repo.clearSelected() }
    fun delete(p: ProxyInfo) = viewModelScope.launch { repo.delete(p) }
    fun addBack(p: ProxyInfo) = viewModelScope.launch { repo.addOrUpdate(p) }
    fun clearAll() = viewModelScope.launch { repo.deleteAll() }

    fun clearBad() = viewModelScope.launch {
        val maxFail = readOnce(prefs.maxFail, 5)
        repo.deleteBad(maxFail)
    }

    fun clearBadByThreshold(n: Int = 8) = viewModelScope.launch {
        repo.deleteBadByFail(n)
    }

    // ========================================================================
    // 一键智能筛选 smartScan
    // ========================================================================
    private var jobSmartScan: Job? = null
    fun smartScan() {
        if (jobSmartScan?.isActive == true) return
        jobSmartScan = viewModelScope.launch {
            busy.value = true
            progressPct.value = 0
            progressText.value = "正在准备…"

            val levelNames = readOnce(prefs.autoTestLevels, setOf("L1_TCP", "L2_HTTP", "L3_HTTPS_GOOGLE"))
            val levels = levelNames.mapNotNull {
                runCatching { ValLevel.valueOf(it) }.getOrNull()
            }.toSet()
            val timeout = readOnce(prefs.testTimeoutMs, 8000)
            val retestMin = readOnce(prefs.retestIntervalMin, 30)
            val concurrency = readOnce(prefs.validationConcurrency, 8)
            val retestIntervalMs = retestMin * 60_000L

            // 只验证需要验证的：从未验证过，或超过 retestMin 分钟未验证
            val list = repo.getPendingValidation(retestIntervalMs)
            val totalAll = repo.getAll().size
            if (list.isEmpty()) {
                progressPct.value = 100
                progressText.value = "全部已验证，$retestMin 分钟内无需重测（共 $totalAll 个）"
                activeChips.value = setOf(QuickChip.USABLE_L2)
                busy.value = false
                return@launch
            }
            progressText.value = "正在验证 0/${list.size}"

            var completed = 0
            val progressStep = (list.size * 0.05).toInt().coerceAtLeast(1)

            list.chunked(concurrency).forEach { chunk ->
                val defs = chunk.map { p ->
                    async(Dispatchers.IO) {
                        runCatching {
                            testing.value = testing.value + p.id
                            val r = validationTester.validate(p, levels, timeout)
                            repo.updateValidation(p.id, r)
                            testing.value = testing.value - p.id
                            p to r
                        }.getOrNull()
                    }
                }
                defs.forEach { d ->
                    d.await()
                    completed++
                    if (completed % progressStep == 0 || completed == list.size) {
                        progressPct.value = (completed * 100 / list.size.coerceAtLeast(1)).coerceAtMost(100)
                        progressText.value = "正在验证 $completed/${list.size}"
                    }
                }
            }

            // 清理失效代理
            progressText.value = "正在清理失效代理…"
            repo.deleteBadByFail(8)

            activeChips.value = setOf(QuickChip.USABLE_L2)

            progressPct.value = 100
            progressText.value = "筛选完成，已优先展示可用代理"
            busy.value = false
        }
    }

    // ========================================================================
    // testCurrentFiltered：只测 state.items（当前筛选子集）
    // ========================================================================
    private var jobFiltered: Job? = null
    fun testCurrentFiltered() {
        if (jobFiltered?.isActive == true) return
        jobFiltered = viewModelScope.launch {
            val list = state.value.items
            if (list.isEmpty()) {
                progressText.value = "当前列表为空"
                return@launch
            }
            val timeout = readOnce(prefs.testTimeoutMs, 8000)
            val levels = setOf(ValLevel.L1_TCP, ValLevel.L2_HTTP, ValLevel.L3_HTTPS_GOOGLE)
            val concurrency = readOnce(prefs.validationConcurrency, 8)

            busy.value = true
            progressPct.value = 0
            progressText.value = "正在验证当前列表 0/${list.size}"

            var completed = 0
            val progressStep = (list.size * 0.05).toInt().coerceAtLeast(1)
            list.chunked(concurrency).forEach { chunk ->
                val defs = chunk.map { p ->
                    async(Dispatchers.IO) {
                        runCatching {
                            testing.value = testing.value + p.id
                            val r = validationTester.validate(p, levels, timeout)
                            repo.updateValidation(p.id, r)
                            testing.value = testing.value - p.id
                        }
                    }
                }
                defs.forEach { d ->
                    d.await()
                    completed++
                    if (completed % progressStep == 0 || completed == list.size) {
                        progressPct.value = (completed * 100 / list.size.coerceAtLeast(1)).coerceAtMost(100)
                        progressText.value = "正在验证当前列表 $completed/${list.size}"
                    }
                }
            }
            progressText.value = getStr(com.freeproxy.app.R.string.msg_test_done)
            busy.value = false
        }
    }

    // ========================================================================
    // 删除重复：按 host:port:type 去重，保留 workCount 大 / latency 小
    // ========================================================================
    fun deleteDupes() = viewModelScope.launch(Dispatchers.IO) {
        val all = repo.getAll()
        val groups = all.groupBy { "${it.host.lowercase()}:${it.port}:${it.type.name}" }
        val toDelete = mutableListOf<ProxyInfo>()
        for ((_, list) in groups) {
            if (list.size <= 1) continue
            val sorted = list.sortedWith(
                compareByDescending<ProxyInfo> { it.workCount }
                    .thenBy { it.failCount }
                    .thenBy<ProxyInfo> { it.latencyMs == null }
                    .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.id }
            )
            // 保留第一个，删除其余
            toDelete.addAll(sorted.drop(1))
        }
        toDelete.forEach { repo.delete(it) }
        progressText.value = "已合并 ${toDelete.size} 个重复代理"
    }

    // ========================================================================
    // testAll（旧） / testOne（旧）保留
    // ========================================================================
    fun testAll() {
        viewModelScope.launch {
            val list = repo.getAll()
            val timeout = readOnce(prefs.testTimeoutMs, 8000)
            val testUrl = readOnce(prefs.testUrl, "https://www.google.com")
            val concurrency = readOnce(prefs.validationConcurrency, 8)
            busy.value = true
            progressPct.value = 0
            progressText.value = "正在测速 0/${list.size}"
            list.chunked(concurrency).forEachIndexed { chunkIdx, chunk ->
                chunk.map { p ->
                    launch(Dispatchers.IO) {
                        testing.value = testing.value + p.id
                        val r = tester.testHttp(p, testUrl, timeout)
                        repo.updateResult(p.id, r.ok, r.latencyMs, r.downloadKbps)
                        testing.value = testing.value - p.id
                    }
                }.forEach { it.join() }
                val done = (chunkIdx + 1) * concurrency
                progressPct.value = (done * 100f / list.size.coerceAtLeast(1)).toInt().coerceAtMost(100)
                progressText.value = "正在测速 ${done.coerceAtMost(list.size)}/${list.size}"
            }
            busy.value = false
            progressText.value = getStr(com.freeproxy.app.R.string.msg_test_done)
        }
    }

    fun testOne(p: ProxyInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            testing.value = testing.value + p.id
            val timeout = readOnce(prefs.testTimeoutMs, 8000)
            val testUrl = readOnce(prefs.testUrl, "https://www.google.com")
            val r = tester.testHttp(p, testUrl, timeout)
            repo.updateResult(p.id, r.ok, r.latencyMs, r.downloadKbps)
            testing.value = testing.value - p.id
        }
    }

    /** 测试单条 L1（AddProxyDialog 添加后立即测） */
    fun testL1(p: ProxyInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val timeout = readOnce(prefs.testTimeoutMs, 8000)
            testing.value = testing.value + p.id
            val r: ValResult = validationTester.testL1(p, timeout)
            if (p.id > 0) repo.updateResult(p.id, r.ok, r.latencyMs, null)
            testing.value = testing.value - p.id
        }
    }

    /** 添加并入库（AddProxyDialog -> onAdd） */
    fun addProxyAndTestL1(p: ProxyInfo) {
        viewModelScope.launch {
            val id = repo.addOrUpdate(p)
            val inserted = repo.getById(id) ?: return@launch
            testL1(inserted)
        }
    }

    // ========================================================================
    // 工具
    // ========================================================================
    private suspend fun <T> readOnce(flow: Flow<T>, def: T): T {
        var out: T = def
        val job = viewModelScope.launch {
            flow.collect { out = it; throw kotlinx.coroutines.CancellationException("once") }
        }
        runCatching { job.join() }
        return out
    }

    private fun getStr(id: Int, vararg fmt: Any): String {
        return if (fmt.isEmpty()) appCtx.getString(id)
        else appCtx.getString(id, *fmt)
    }
}
