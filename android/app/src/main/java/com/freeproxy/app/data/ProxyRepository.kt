package com.freeproxy.app.data

import android.content.Context
import com.freeproxy.app.data.db.AppDatabase
import com.freeproxy.app.data.db.ProxyDao
import com.freeproxy.app.data.db.SourceDao
import com.freeproxy.app.data.model.AnonymityLevel
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxySourceEntity
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.data.model.SpeedLevel
import com.freeproxy.app.discover.BuiltinSources
import com.freeproxy.app.discover.ProxySource
import com.freeproxy.app.net.ValResult
import kotlinx.coroutines.flow.Flow

/**
 * 代理仓库：统一操作代理列表
 */
class ProxyRepository(ctx: Context) {
    private val dao: ProxyDao = AppDatabase.get(ctx).proxyDao()
    private val sourceDao: SourceDao = AppDatabase.get(ctx).sourceDao()

    fun observeAll(): Flow<List<ProxyInfo>> = dao.observeAll()

    suspend fun getAll(): List<ProxyInfo> = dao.getAll()

    suspend fun getSelected(): ProxyInfo? = dao.getSelected()

    suspend fun getById(id: Long): ProxyInfo? = dao.getById(id)

    suspend fun count(): Int = dao.count()

    suspend fun addOrUpdate(p: ProxyInfo): Long = dao.insert(p)

    /**
     * 批量新增（忽略冲突），返回新增数量
     */
    suspend fun addAllIfAbsent(list: List<ProxyInfo>): Int {
        val unique = list.filter { it.host.isNotBlank() && it.port in 1..65535 }
            .distinctBy {
                "${it.host.lowercase()}:${it.port}:${it.type.name}:" +
                    "${it.username ?: ""}:${it.password ?: ""}"
            }
        val rows = dao.insertAll(unique)
        return rows.count { it != -1L }
    }

    suspend fun updateResult(id: Long, ok: Boolean, latencyMs: Long? = null, kbps: Long? = null) {
        val p = dao.getById(id) ?: return
        val level = if (latencyMs != null) SpeedLevel.fromLatency(latencyMs) else p.speedLevel
        dao.update(
            p.copy(
                latencyMs = latencyMs ?: p.latencyMs,
                downloadKbps = kbps ?: p.downloadKbps,
                speedLevel = level,
                lastChecked = System.currentTimeMillis(),
                workCount = p.workCount + (if (ok) 1 else 0),
                failCount = p.failCount + (if (ok) 0 else 1),
            )
        )
    }

    /**
     * 把 ValidationTester.ValResult 写回数据库：
     *   - L3/L4 站点标记 → validGoogle/Youtube/Facebook + lastSitesOk
     *   - 失败原因 → failReason（仅本次失败才覆盖，成功保留原值）
     *   - HTTPS 隧道能力 → httpsTunnel
     *   顺带复用 updateResult 更新延迟/下载速度/统计
     */
    suspend fun updateValidation(id: Long, r: ValResult) {
        val p = dao.getById(id) ?: return
        dao.updateValidation(
            id = id,
            g = if (r.okL3) 1 else 0,
            y = if (r.okYt) 1 else 0,
            f = if (r.okFb) 1 else 0,
            sites = buildString {
                if (r.okL3) append("google,")
                if (r.okYt) append("yt,")
                if (r.okFb) append("fb,")
            }.trimEnd(','),
            reason = if (r.ok) p.failReason else (r.failReason ?: p.failReason),
            https = if (r.httpsTunnel) 1 else 0,
        )
        // 顺便把延迟/下载速度/统计也更新
        updateResult(
            id = id,
            ok = r.ok,
            latencyMs = r.latencyMs,
            kbps = r.downloadKbps,
        )
    }

    suspend fun setSelected(id: Long) = dao.setSelected(id)
    suspend fun clearSelected() = dao.clearSelected()

    suspend fun delete(p: ProxyInfo) = dao.delete(p)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun deleteBad(maxFail: Int = 5) = dao.deleteBad(maxFail)

    /** 按失败次数阈值删除坏代理（DAO 显式别名） */
    suspend fun deleteBadByFail(maxFail: Int) = dao.deleteBadByFail(maxFail)

    /** 返回 L3/L4 至少一个站点验证通过的 TOP n 条（按 workCount 降、延迟升） */
    suspend fun topValid(n: Int): List<ProxyInfo> = dao.topValid(n)

    /** 返回需要验证的代理：从未验证过，或超过 retestIntervalMs 未验证 */
    suspend fun getPendingValidation(retestIntervalMs: Long): List<ProxyInfo> =
        dao.getPendingValidation(System.currentTimeMillis(), retestIntervalMs)

    fun filter(
        type: ProxyType? = null,
        anon: AnonymityLevel? = null,
        country: String? = null,
        host: String? = null,
        speed: SpeedLevel? = null,
    ): Flow<List<ProxyInfo>> = dao.filter(type, anon, country, host, speed)

    // ========================================================================
    // 数据源：内置 + 自定义统一入库（proxy_sources 表）
    // ========================================================================

    fun observeCustomSources(): Flow<List<ProxySourceEntity>> = sourceDao.observeAll()

    suspend fun customSources(): List<ProxySourceEntity> = sourceDao.getAll()

    /** 内置 + 自定义合并（自定义优先，同 URL 覆盖内置） */
    suspend fun allSources(): List<ProxySource> {
        val custom = sourceDao.getAll().map { it.toSource() }
        val customUrls = custom.map { it.url }.toHashSet()
        return custom + BuiltinSources.default.filterNot { it.url in customUrls }
    }

    suspend fun addSourceEntity(e: ProxySourceEntity): Long = sourceDao.insert(e)
    suspend fun updateSourceEntity(e: ProxySourceEntity) = sourceDao.update(e)
    suspend fun deleteSourceEntity(e: ProxySourceEntity) = sourceDao.delete(e)
    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = sourceDao.setEnabled(id, enabled)

    /** 抓取后回写源状态（OK/ERR + 条数），数据源页可视化 */
    suspend fun setSourceStatus(url: String, ok: Boolean, count: Int) {
        sourceDao.setStatus(
            url = url,
            status = if (ok) "OK" else "ERR",
            count = count,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** 补齐缺失的内置源（保留用户已改/已停用项，仅恢复被删的） */
    suspend fun restoreBuiltinSources(): Int {
        var added = 0
        for (src in com.freeproxy.app.discover.BuiltinSources.default) {
            if (sourceDao.getByUrl(src.url) == null) {
                sourceDao.insert(ProxySourceEntity.fromSource(src))
                added++
            }
        }
        return added
    }

    /**
     * 首次启动种子化：把内置源写入库（url 冲突忽略，保留用户已改/已删后的现状）。
     * 返回本次新增条数。
     */
    suspend fun seedBuiltinSourcesIfEmpty(): Int {
        if (sourceDao.count() > 0) return 0
        var added = 0
        for (s in BuiltinSources.default) {
            val id = sourceDao.insert(ProxySourceEntity.fromSource(s))
            if (id > 0) added++
        }
        return added
    }
}
