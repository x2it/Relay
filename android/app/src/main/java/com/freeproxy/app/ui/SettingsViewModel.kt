package com.freeproxy.app.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeproxy.app.FreeProxyApp
import com.freeproxy.app.data.UserPreferences
import com.freeproxy.app.io.ProxyIo
import com.freeproxy.app.vpn.RouteMode
import com.freeproxy.app.vpn.SUGGESTED_EXCLUDED_PACKAGES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val testUrl: String = "",
    val testTimeoutMs: Int = 8000,
    val connectTimeoutMs: Int = 10000,
    val autoReconnect: Boolean = true,
    val maxFail: Int = 5,
    val busy: Boolean = false,
    val toast: String? = null,
)

class AppEntry(val pkg: String, val label: String, val isSystem: Boolean)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = getApplication<FreeProxyApp>()
    private val prefs: UserPreferences = appCtx.userPrefs
    private val io: ProxyIo = ProxyIo(appCtx, appCtx.repository)

    val testUrl: Flow<String> = prefs.testUrl
    val testTimeout: Flow<Int> = prefs.testTimeoutMs
    val connectTimeout: Flow<Int> = prefs.connectTimeoutMs
    val autoReconnect: Flow<Boolean> = prefs.autoReconnect
    val maxFail: Flow<Int> = prefs.maxFail
    val routeMode: Flow<RouteMode> = prefs.routeMode
    val excludedApps: Flow<Set<String>> = prefs.excludedApps
    val suggestedExcludedChecked: Flow<Boolean> = prefs.suggestedExcludedChecked

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _appList = MutableStateFlow<List<AppEntry>>(emptyList())
    val appList: StateFlow<List<AppEntry>> = _appList.asStateFlow()

    private val _appListLoading = MutableStateFlow(false)
    val appListLoading: StateFlow<Boolean> = _appListLoading.asStateFlow()

    fun consumeToast() { _toast.value = null }

    fun setTestUrl(v: String) = viewModelScope.launch { prefs.setTestUrl(v) }
    fun setTestTimeout(v: Int) = viewModelScope.launch { prefs.setTestTimeout(v) }
    fun setConnectTimeout(v: Int) = viewModelScope.launch { prefs.setConnectTimeout(v) }
    fun setAutoReconnect(v: Boolean) = viewModelScope.launch { prefs.setAutoReconnect(v) }
    fun setMaxFail(v: Int) = viewModelScope.launch { prefs.setMaxFail(v) }
    fun setRouteMode(m: RouteMode) = viewModelScope.launch { prefs.setRouteMode(m) }
    fun setExcludedApps(apps: Set<String>) = viewModelScope.launch { prefs.setExcludedApps(apps) }

    /**
     * 切换「应用建议排除」开关：
     *   - 打开：把 SUGGESTED_EXCLUDED_PACKAGES 并入 excludedApps
     *   - 关闭：从 excludedApps 中移除所有 SUGGESTED_EXCLUDED_PACKAGES（自定义排除保留）
     * 同时写入 suggestedExcludedChecked 字段。
     */
    fun toggleSuggestedExcluded(checked: Boolean) = viewModelScope.launch {
        val now: Set<String> = try {
            prefs.excludedApps.first()
        } catch (_: Throwable) {
            emptySet()
        }
        val updated = if (checked) {
            now + SUGGESTED_EXCLUDED_PACKAGES
        } else {
            now - SUGGESTED_EXCLUDED_PACKAGES.toSet()
        }
        prefs.setExcludedApps(updated)
        prefs.setSuggestedExcludedChecked(checked)
    }

    /** 异步加载已安装应用列表 */
    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _appListLoading.value = true
            try {
                _appList.value = listApps()
            } finally {
                _appListLoading.value = false
            }
        }
    }

    /** 列出手机上已安装的应用 */
    fun listApps(): List<AppEntry> {
        val pm = appCtx.packageManager
        return pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { ai ->
                runCatching {
                    AppEntry(
                        pkg = ai.packageName,
                        label = pm.getApplicationLabel(ai).toString(),
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }.getOrNull()
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()
    }

    fun doImport(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val n = io.importFromUri(uri)
            _busy.value = false
            _toast.value = appCtx.getString(com.freeproxy.app.R.string.msg_import_done, n)
        }
    }

    fun doExport(uri: Uri, json: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            val n = io.exportToUri(uri, if (json) ProxyIo.Format.JSON else ProxyIo.Format.TEXT,
                onlyUsable = true)
            _busy.value = false
            _toast.value = appCtx.getString(com.freeproxy.app.R.string.msg_export_done) +
                " ($n 个)"
        }
    }
}
