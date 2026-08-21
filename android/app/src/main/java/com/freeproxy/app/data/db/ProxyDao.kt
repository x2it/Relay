package com.freeproxy.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.freeproxy.app.data.model.AnonymityLevel
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.data.model.SpeedLevel
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyDao {

    @Query("""
        SELECT * FROM proxies
        ORDER BY selected DESC,
                 CASE WHEN latencyMs IS NULL THEN 1 ELSE 0 END,
                 latencyMs ASC,
                 workCount DESC
    """)
    fun observeAll(): Flow<List<ProxyInfo>>

    @Query("""
        SELECT * FROM proxies
        ORDER BY selected DESC,
                 CASE WHEN latencyMs IS NULL THEN 1 ELSE 0 END,
                 latencyMs ASC,
                 workCount DESC
    """)
    suspend fun getAll(): List<ProxyInfo>

    @Query("""
        SELECT * FROM proxies WHERE selected = 1
        ORDER BY CASE WHEN latencyMs IS NULL THEN 1 ELSE 0 END,
                 latencyMs ASC LIMIT 1
    """)
    suspend fun getSelected(): ProxyInfo?

    @Query("SELECT * FROM proxies WHERE id = :id")
    suspend fun getById(id: Long): ProxyInfo?

    @Query("SELECT COUNT(*) FROM proxies")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(proxy: ProxyInfo): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<ProxyInfo>): List<Long>

    @Update
    suspend fun update(proxy: ProxyInfo)

    @Update
    suspend fun updateAll(list: List<ProxyInfo>)

    @Delete
    suspend fun delete(proxy: ProxyInfo)

    @Query("DELETE FROM proxies WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM proxies")
    suspend fun deleteAll()

    @Query("DELETE FROM proxies WHERE failCount >= :maxFail")
    suspend fun deleteBad(maxFail: Int = 5)

    @Query("UPDATE proxies SET selected = (id = :id)")
    suspend fun setSelected(id: Long)

    @Query("UPDATE proxies SET selected = 0")
    suspend fun clearSelected()

    /** 搜索过滤 */
    @Query("""
        SELECT * FROM proxies
        WHERE (:type IS NULL OR type = :type)
          AND (:anon IS NULL OR anonymity = :anon)
          AND (:country IS NULL OR country LIKE '%' || :country || '%')
          AND (:host IS NULL OR host LIKE '%' || :host || '%')
          AND (:speed IS NULL OR speedLevel = :speed)
        ORDER BY selected DESC,
                 CASE WHEN latencyMs IS NULL THEN 1 ELSE 0 END,
                 latencyMs ASC,
                 workCount DESC
    """)
    fun filter(
        type: ProxyType? = null,
        anon: AnonymityLevel? = null,
        country: String? = null,
        host: String? = null,
        speed: SpeedLevel? = null,
    ): Flow<List<ProxyInfo>>

    // ===== L1-L4 验证相关查询 =====

    /** 批量更新四层验证结果字段（按 id） */
    @Query("""
        UPDATE proxies SET
            validGoogle=:g,
            validYoutube=:y,
            validFacebook=:f,
            lastSitesOk=:sites,
            failReason=:reason,
            httpsTunnel=:https
        WHERE id=:id
    """)
    suspend fun updateValidation(
        id: Long,
        g: Int, y: Int, f: Int,
        sites: String,
        reason: String?,
        https: Int,
    )

    /** 按失败次数阈值删除坏代理（与已有 deleteBad 同义，作为显式别名） */
    @Query("DELETE FROM proxies WHERE failCount >= :maxFail")
    suspend fun deleteBadByFail(maxFail: Int)

    /** 返回 L3/L4 验证通过（至少有一个站点 ok）的 TOP n 条，按 workCount 降序 + 延迟升序 */
    @Query("""
        SELECT * FROM proxies
        WHERE (validGoogle=1 OR validYoutube=1 OR validFacebook=1)
        ORDER BY workCount DESC, latencyMs ASC
        LIMIT :n
    """)
    suspend fun topValid(n: Int): List<ProxyInfo>

    /** 返回需要验证的代理：从未验证过，或超过 retestIntervalMs 未验证 */
    @Query("""
        SELECT * FROM proxies
        WHERE lastChecked IS NULL OR :now - lastChecked > :retestIntervalMs
        ORDER BY selected DESC,
                 CASE WHEN latencyMs IS NULL THEN 1 ELSE 0 END,
                 workCount DESC
    """)
    suspend fun getPendingValidation(now: Long, retestIntervalMs: Long): List<ProxyInfo>
}
