package com.freeproxy.app.discover

import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import java.util.regex.Pattern

/**
 * 公共工具：单条代理 URL / "host:port" 解析器
 * 支持格式：
 *   - scheme://user:pass@host:port
 *   - scheme://host:port
 *   - host:port (fallbackType 默认为 HTTP)
 *   - [ipv6]:port
 */
object ProxyUrlParser {

    private val urlLike = Pattern.compile(
        """^(?:(\w+)://)?(?:([^:@\s]+):([^:@\s]+)@)?([^\s:]+|\[[0-9a-fA-F:]+\]):(\d{1,5})"""
    )

    private val hostPortLine = Pattern.compile(
        """^([^\s,;|\t]+|\[[0-9a-fA-F:]+\]):(\d{1,5})(.*)$"""
    )

    /**
     * 解析单条代理字符串。
     * @param line 原始串
     * @param fallbackType 若未指定 scheme 时的默认类型（默认 HTTP）
     * @param source 可选来源标签
     * @return 解析成功返回 ProxyInfo（id=0 未入库），失败 null
     */
    fun parse(
        line: String,
        fallbackType: ProxyType = ProxyType.HTTP,
        source: String? = null,
    ): ProxyInfo? {
        val raw = line.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } ?: return null

        // 1) URL 格式 scheme://user:pass@host:port
        val m = urlLike.matcher(raw)
        if (m.find()) {
            val scheme = m.group(1)
            val user = m.group(2)
            val pass = m.group(3)
            val host = (m.group(4) ?: "").trim('[', ']')
            val port = m.group(5)?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            if (host.isBlank()) return null
            val type = scheme?.let { ProxyType.from(it) } ?: fallbackType
            return ProxyInfo(
                host = host, port = port, type = type,
                username = user, password = pass, source = source
            )
        }

        // 2) 纯 host:port 主体（可能带后续字段用 ,;/| 分隔，但此方法只提取前两部分）
        val hp = parseHostPort(raw.split(',', ';', '|', '\t').first().trim()) ?: return null
        return ProxyInfo(
            host = hp.first, port = hp.second,
            type = fallbackType, source = source
        )
    }

    /** 仅从 "host:port" 或 "[ipv6]:port" 提取 host 和 port */
    fun parseHostPort(s: String): Pair<String, Int>? {
        val host: String
        val portStr: String
        if (s.startsWith("[")) {
            val end = s.lastIndexOf("]")
            if (end < 0) return null
            host = s.substring(1, end)
            portStr = s.substring(end + 1).trimStart(':')
        } else {
            val idx = s.lastIndexOf(":")
            if (idx < 0) return null
            host = s.substring(0, idx)
            portStr = s.substring(idx + 1)
        }
        val p = portStr.toIntOrNull() ?: return null
        if (p !in 1..65535) return null
        if (host.isBlank()) return null
        return host to p
    }

    /**
     * 从一整行混合字段中提取 host:port + 剩余后缀。
     * 例："1.2.3.4:8080,HTTP,US,elite" → (1.2.3.4, 8080, "HTTP,US,elite")
     */
    internal fun extractHostPortFromLine(line: String): HostPortResult? {
        val m = hostPortLine.matcher(line)
        if (m.find()) {
            val host = (m.group(1) ?: "").trim('[', ']')
            val port = m.group(2)?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val restRaw = m.group(3).orEmpty().trim()
            val rest = restRaw.trimStart(',', ';', '|', '\t', ' ').ifBlank { null }
            return HostPortResult(host, port, rest)
        }
        val hp = parseHostPort(line.split(',', ';', '|', '\t').first().trim()) ?: return null
        val firstSep = line.indexOfAny(charArrayOf(',', ';', '|', '\t'))
        val rest = if (firstSep >= 0) line.substring(firstSep + 1).trim().ifBlank { null } else null
        return HostPortResult(hp.first, hp.second, rest)
    }

    internal data class HostPortResult(val host: String, val port: Int, val rest: String?)
}
