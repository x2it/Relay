package com.freeproxy.app.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 代理类型
 */
enum class ProxyType(val value: String) {
    HTTP("HTTP"),
    HTTPS("HTTPS"),
    SOCKS4("SOCKS4"),
    SOCKS5("SOCKS5"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun from(s: String?): ProxyType =
            values().firstOrNull { it.value.equals(s, true) }
                ?: if (s?.contains("socks5", true) == true) SOCKS5
                else if (s?.contains("socks4", true) == true) SOCKS4
                else if (s?.contains("https", true) == true) HTTPS
                else if (s?.contains("http", true) == true) HTTP
                else UNKNOWN
    }
}

/**
 * 匿名级别
 */
enum class AnonymityLevel(val value: String) {
    TRANSPARENT("透明"),   // 目标服务器知道你真实IP
    ANONYMOUS("匿名"),     // 不知道真实IP但知道用了代理
    ELITE("高匿"),         // 完全察觉不到代理
    UNKNOWN("未知");

    companion object {
        fun from(s: String?): AnonymityLevel =
            values().firstOrNull { it.value == s }
                ?: when {
                    s?.contains("elite", true) == true ||
                        s?.contains("high", true) == true -> ELITE
                    s?.contains("anonymous", true) == true -> ANONYMOUS
                    s?.contains("transparent", true) == true -> TRANSPARENT
                    else -> UNKNOWN
                }
    }
}

/**
 * 代理速度级别
 */
enum class SpeedLevel(val label: String) {
    FAST("快"),
    MEDIUM("中"),
    SLOW("慢"),
    UNKNOWN("—");

    companion object {
        fun fromLatency(ms: Long?): SpeedLevel = when {
            ms == null || ms <= 0 -> UNKNOWN
            ms < 200 -> FAST
            ms < 800 -> MEDIUM
            else -> SLOW
        }
    }
}

/**
 * 代理实体
 */
@Parcelize
@Entity(
    tableName = "proxies",
    indices = [Index(value = ["host", "port"], unique = true)]
)
data class ProxyInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val port: Int,
    val type: ProxyType = ProxyType.HTTP,
    val username: String? = null,
    val password: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val anonymity: AnonymityLevel = AnonymityLevel.UNKNOWN,
    val https: Boolean = false,
    val latencyMs: Long? = null,      // 上次测得延迟
    val downloadKbps: Long? = null,   // 上次测得下载速度
    val speedLevel: SpeedLevel = SpeedLevel.UNKNOWN,
    val source: String? = null,       // 来自哪个代理源
    val lastChecked: Long = 0L,       // 最后检查时间戳
    val workCount: Int = 0,           // 成功次数
    val failCount: Int = 0,           // 失败次数
    val selected: Boolean = false,    // 用户是否选中
    val createdAt: Long = System.currentTimeMillis(),
    // ===== L1-L4 四层验证新增字段 =====
    val validGoogle: Boolean = false,    // L3 HTTPS Google 通过
    val validYoutube: Boolean = false,   // L4 m.youtube.com 通过
    val validFacebook: Boolean = false,  // L4 www.facebook.com 通过
    val lastSitesOk: String = "",        // 逗号分隔，例如 "google,yt,fb"
    val failReason: String? = null,      // 规范化失败原因
    val httpsTunnel: Boolean = true,     // true=支持 HTTPS CONNECT；false=L2通L3败=仅HTTP明文
) : Parcelable {
    fun display(): String = "$host:$port"
    fun successRate(): Double =
        if (workCount + failCount == 0) 0.0
        else workCount.toDouble() / (workCount + failCount)

    /**
     * 是否可尝试连接。
     * 奥卡姆剃刀：用户已选中的代理视为可用——选择即意图，不再用历史失败次数拦截；
     * 未选中的代理保持 failCount<5 的弱过滤，仅用于列表筛选展示。
     */
    fun isUsable(): Boolean =
        host.isNotBlank() && port in 1..65535 && (failCount < 5 || selected)
}

/**
 * 连接状态（给VpnService/VPN UI用）
 */
enum class VpnStatus {
    IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}
