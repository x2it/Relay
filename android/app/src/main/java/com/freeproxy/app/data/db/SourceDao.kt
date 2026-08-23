package com.freeproxy.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.freeproxy.app.data.model.ProxySourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 数据源 DAO：提供数据源增删改查 + 启停
 */
@Dao
interface SourceDao {

    @Query("SELECT * FROM proxy_sources ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProxySourceEntity>>

    @Query("SELECT * FROM proxy_sources ORDER BY createdAt ASC")
    suspend fun getAll(): List<ProxySourceEntity>

    @Query("SELECT * FROM proxy_sources WHERE id = :id")
    suspend fun getById(id: Long): ProxySourceEntity?

    @Query("SELECT * FROM proxy_sources WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): ProxySourceEntity?

    @Query("SELECT COUNT(*) FROM proxy_sources")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProxySourceEntity): Long

    @Update
    suspend fun update(entity: ProxySourceEntity)

    @Delete
    suspend fun delete(entity: ProxySourceEntity)

    @Query("DELETE FROM proxy_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE proxy_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query(
        "UPDATE proxy_sources SET lastStatus = :status, lastCount = :count, " +
            "lastFetchedAt = :fetchedAt WHERE url = :url"
    )
    suspend fun setStatus(url: String, status: String, count: Int, fetchedAt: Long)
}
