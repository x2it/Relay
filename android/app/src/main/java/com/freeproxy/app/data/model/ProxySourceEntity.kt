package com.freeproxy.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.freeproxy.app.discover.ProxySource

/**
 * 数据源实体：持久化所有代理源（内置源首次启动种子化 + 用户自定义源）
 * 统一存库后，内置/自定义均可开关、编辑、删除
 */
@Entity(
    tableName = "proxy_sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class ProxySourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val format: String = "AUTO",        // ProxySource.Format.name
    val schemeHint: String? = null,     // ProxyType.name，null = 自动推断
    val timeoutMs: Int = 15_000,
    val enabled: Boolean = true,
    val mirrors: String = "",           // 逗号分隔镜像
    val category: String = "CUSTOM",    // ProxySource.Category.name
    val lastStatus: String = "N",       // 上次抓取结果：N=未抓取 / OK / ERR
    val lastCount: Int = 0,             // 上次抓取解析条数
    val lastFetchedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toSource(): ProxySource = ProxySource(
        name = name,
        url = url,
        format = runCatching { ProxySource.Format.valueOf(format) }
            .getOrDefault(ProxySource.Format.AUTO),
        schemeHint = schemeHint?.let { h -> runCatching { ProxyType.valueOf(h) }.getOrNull() },
        timeoutMs = timeoutMs,
        enabled = enabled,
        mirrors = mirrors.split(",").map { it.trim() }.filter { it.isNotBlank() },
        category = runCatching { ProxySource.Category.valueOf(category) }
            .getOrDefault(ProxySource.Category.CUSTOM),
    )

    companion object {
        fun fromSource(s: ProxySource, id: Long = 0): ProxySourceEntity = ProxySourceEntity(
            id = id,
            name = s.name,
            url = s.url,
            format = s.format.name,
            schemeHint = s.schemeHint?.name,
            timeoutMs = s.timeoutMs,
            enabled = s.enabled,
            mirrors = s.mirrors.joinToString(","),
            category = s.category.name,
        )
    }
}
