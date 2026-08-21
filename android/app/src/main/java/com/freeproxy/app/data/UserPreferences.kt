package com.freeproxy.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.vpn.RouteMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

/**
 * 用户设置（DataStore）
 */
class UserPreferences(private val ctx: Context) {

    companion object {
        private val KEY_TEST_URL = stringPreferencesKey("test_url")
        private val KEY_TEST_TIMEOUT = intPreferencesKey("test_timeout")
        private val KEY_CONNECT_TIMEOUT = intPreferencesKey("connect_timeout")
        private val KEY_AUTO_RECONNECT = stringPreferencesKey("auto_reconnect")
        private val KEY_MAX_FAIL = intPreferencesKey("max_fail")
        private val KEY_ROUTE_MODE = intPreferencesKey("route_mode")
        private val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")
        private val KEY_SUGGESTED_EXCLUDED_CHECKED = booleanPreferencesKey("suggested_excluded_checked")
        private val KEY_AUTO_PICK_BEFORE_CONNECT = booleanPreferencesKey("auto_pick_before_connect")
        private val KEY_KEEP_TOP_N = intPreferencesKey("keep_top_n")
        private val KEY_AUTO_TEST_LEVELS = stringSetPreferencesKey("auto_test_levels")
        private val KEY_AUTO_PICK_TOP_N = intPreferencesKey("auto_pick_top_n")
        private val KEY_RETEST_INTERVAL_MIN = intPreferencesKey("retest_interval_min")
        private val KEY_VALIDATION_CONCURRENCY = intPreferencesKey("validation_concurrency")
        private val KEY_TEST_URL_DEFAULT = "https://www.google.com"
    }

    val testUrl: Flow<String> = ctx.dataStore.data.map {
        it[KEY_TEST_URL] ?: KEY_TEST_URL_DEFAULT
    }

    val testTimeoutMs: Flow<Int> = ctx.dataStore.data.map {
        it[KEY_TEST_TIMEOUT] ?: 8000
    }

    val connectTimeoutMs: Flow<Int> = ctx.dataStore.data.map {
        it[KEY_CONNECT_TIMEOUT] ?: 10000
    }

    val autoReconnect: Flow<Boolean> = ctx.dataStore.data.map {
        it[KEY_AUTO_RECONNECT]?.toBooleanStrictOrNull() ?: true
    }

    val maxFail: Flow<Int> = ctx.dataStore.data.map {
        it[KEY_MAX_FAIL] ?: 5
    }

    val routeMode: Flow<RouteMode> = ctx.dataStore.data.map {
        RouteMode.fromCode(it[KEY_ROUTE_MODE] ?: RouteMode.SMART.code)
    }

    val excludedApps: Flow<Set<String>> = ctx.dataStore.data.map {
        it[KEY_EXCLUDED_APPS] ?: emptySet()
    }

    /** 首次默认 true：「应用建议排除」开关 */
    val suggestedExcludedChecked: Flow<Boolean> = ctx.dataStore.data.map {
        it[KEY_SUGGESTED_EXCLUDED_CHECKED] ?: true
    }

    /** 连接前自动挑最快节点（默认 false） */
    val autoPickBeforeConnect: Flow<Boolean> = ctx.dataStore.data.map {
        it[KEY_AUTO_PICK_BEFORE_CONNECT] ?: false
    }

    suspend fun setTestUrl(url: String) = ctx.dataStore.edit { it[KEY_TEST_URL] = url }
    suspend fun setTestTimeout(ms: Int) = ctx.dataStore.edit { it[KEY_TEST_TIMEOUT] = ms }
    suspend fun setConnectTimeout(ms: Int) = ctx.dataStore.edit { it[KEY_CONNECT_TIMEOUT] = ms }
    suspend fun setAutoReconnect(v: Boolean) = ctx.dataStore.edit { it[KEY_AUTO_RECONNECT] = v.toString() }
    suspend fun setMaxFail(v: Int) = ctx.dataStore.edit { it[KEY_MAX_FAIL] = v }
    suspend fun setRouteMode(m: RouteMode) = ctx.dataStore.edit { it[KEY_ROUTE_MODE] = m.code }
    suspend fun setExcludedApps(apps: Set<String>) = ctx.dataStore.edit { it[KEY_EXCLUDED_APPS] = apps }
    suspend fun setSuggestedExcludedChecked(v: Boolean) =
        ctx.dataStore.edit { it[KEY_SUGGESTED_EXCLUDED_CHECKED] = v }
    suspend fun setAutoPickBeforeConnect(v: Boolean) =
        ctx.dataStore.edit { it[KEY_AUTO_PICK_BEFORE_CONNECT] = v }

    /** 智能筛选保留 Top N（默认 50，范围 10..300） */
    val keepTopN: Flow<Int> = ctx.dataStore.data.map {
        (it[KEY_KEEP_TOP_N] ?: 50).coerceIn(10, 300)
    }
    suspend fun setKeepTopN(v: Int) =
        ctx.dataStore.edit { it[KEY_KEEP_TOP_N] = v.coerceIn(10, 300) }

    /** 重测间隔（分钟）：上次验证未超此间隔的代理跳过，默认 30 分钟，范围 5..1440 */
    val retestIntervalMin: Flow<Int> = ctx.dataStore.data.map {
        (it[KEY_RETEST_INTERVAL_MIN] ?: 30).coerceIn(5, 1440)
    }
    suspend fun setRetestIntervalMin(v: Int) =
        ctx.dataStore.edit { it[KEY_RETEST_INTERVAL_MIN] = v.coerceIn(5, 1440) }

    /** 验证并发数：默认 8，范围 2..24（移动端不建议过高） */
    val validationConcurrency: Flow<Int> = ctx.dataStore.data.map {
        (it[KEY_VALIDATION_CONCURRENCY] ?: 8).coerceIn(2, 24)
    }
    suspend fun setValidationConcurrency(v: Int) =
        ctx.dataStore.edit { it[KEY_VALIDATION_CONCURRENCY] = v.coerceIn(2, 24) }

    /** 自动测试等级（默认 L1+L2+L3） */
    val autoTestLevels: Flow<Set<String>> = ctx.dataStore.data.map {
        it[KEY_AUTO_TEST_LEVELS] ?: setOf("L1_TCP", "L2_HTTP", "L3_HTTPS_GOOGLE")
    }
    suspend fun setAutoTestLevels(v: Set<String>) =
        ctx.dataStore.edit { it[KEY_AUTO_TEST_LEVELS] = v }

    /** 自动挑最快前 N 条（默认 10，范围 3..20） */
    val autoPickTopN: Flow<Int> = ctx.dataStore.data.map {
        (it[KEY_AUTO_PICK_TOP_N] ?: 10).coerceIn(3, 20)
    }
    suspend fun setAutoPickTopN(v: Int) =
        ctx.dataStore.edit { it[KEY_AUTO_PICK_TOP_N] = v.coerceIn(3, 20) }

    /**
     * 代理URL快捷格式：scheme://user:pass@host:port
     */
    fun parseProxyUrl(url: String): com.freeproxy.app.data.model.ProxyInfo? {
        return runCatching {
            val u = url.trim()
            if (u.isBlank()) return@runCatching null
            val (schemePart, rest1) = if ("://" in u) {
                val a = u.split("://", limit = 2)
                a[0] to a[1]
            } else ("" to u)
            val type = if (schemePart.isNotBlank()) ProxyType.from(schemePart) else ProxyType.HTTP

            val authAndRest = rest1.split("@", limit = 2)
            val (userPass, hostPort) = if (authAndRest.size == 2) {
                authAndRest[0] to authAndRest[1]
            } else ("" to authAndRest[0])

            val (user, pass) = if (userPass.isBlank()) null to null else {
                val parts = userPass.split(":", limit = 2)
                parts.getOrNull(0) to parts.getOrNull(1)
            }

            val hp = hostPort.split(":", limit = 2)
            val host = hp[0].removePrefix("[").substringBeforeLast("]")
            val port = hp.getOrNull(1)?.toIntOrNull() ?: 80

            com.freeproxy.app.data.model.ProxyInfo(
                host = host, port = port, type = type,
                username = user, password = pass
            )
        }.getOrNull()
    }
}
