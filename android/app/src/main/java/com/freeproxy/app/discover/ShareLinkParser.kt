package com.freeproxy.app.discover

import android.util.Base64
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 加密节点分享链接解析器
 * 支持：vmess:// trojan:// vless:// ss://（含 ssr 兼容截断），以及订阅内容中的单条
 * 解析结果写入 ProxyInfo（type + username/password + configJson），供 sing-box 出站使用。
 */
object ShareLinkParser {

    /**
     * 解析一行（可能是一整条分享链接，也可能夹在订阅多行文本里）。
     * @return 解析成功返回 ProxyInfo，否则 null
     */
    fun parse(line: String, source: String? = null): ProxyInfo? {
        val raw = line.trim().takeIf { it.isNotBlank() } ?: return null
        val lower = raw.lowercase()
        return when {
            lower.startsWith("vmess://") -> parseVmess(raw, source)
            lower.startsWith("trojan://") -> parseTrojan(raw, source)
            lower.startsWith("vless://") -> parseVless(raw, source)
            lower.startsWith("ss://") -> parseSs(raw, source)
            else -> null
        }
    }

    /** 从一段文本中提取所有可识别的分享链接（用于订阅批量导入/扫码多节点） */
    fun parseAll(text: String, source: String? = null): List<ProxyInfo> {
        val out = mutableListOf<ProxyInfo>()
        for (line in text.lineSequence()) {
            // 同一行内可能以空格分隔多条
            for (token in line.trim().split(Regex("""\s+"""))) {
                parse(token, source)?.let { out.add(it) }
            }
        }
        return out
    }

    // =====================================================================
    // vmess://BASE64{json}
    // json: { v, ps, add, port, id, aid, net, type, host, path, tls, sni, alpn, scy, fp, flow }
    // =====================================================================
    private fun parseVmess(raw: String, source: String?): ProxyInfo? {
        val b64 = raw.removePrefix("vmess://").trim()
        val jsonStr = runCatching { b64Decode(b64) }.getOrNull()
            ?: runCatching { URLDecoder.decode(b64, "UTF-8") }.getOrNull() ?: return null
        val o = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return null

        val host = o.optString("add").takeIf { it.isNotBlank() } ?: return null
        val port = o.optInt("port").takeIf { it in 1..65535 } ?: return null
        val uuid = o.optString("id").takeIf { it.isNotBlank() } ?: return null

        val net = o.optString("net").ifBlank { "tcp" }
        val tls = o.optString("tls").ifBlank { "none" }
        val sni = o.optString("sni").takeIf { it.isNotBlank() } ?: host
        val name = o.optString("ps").takeIf { it.isNotBlank() } ?: host

        val cfg = JSONObject()
            .put("type", "vmess")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("alterId", o.optInt("aid", 0))
            .put("security", o.optString("scy").ifBlank { "auto" })
            .put("network", net)
            .put("tls", tls)
            .put("sni", sni)
            .put("host", o.optString("host").takeIf { it.isNotBlank() })
            .put("path", o.optString("path").takeIf { it.isNotBlank() })
            .put("flow", o.optString("flow").takeIf { it.isNotBlank() })
            .put("fp", o.optString("fp").takeIf { it.isNotBlank() })
            .put("alpn", o.optString("alpn").takeIf { it.isNotBlank() })

        return ProxyInfo(
            host = host, port = port, type = ProxyType.VMESS,
            username = uuid, password = null,
            nodeName = name,
            configJson = cfg.toString(),
            source = source ?: "vmess",
        )
    }

    // =====================================================================
    // trojan://password@host:port?security=...&sni=...#name
    // =====================================================================
    private fun parseTrojan(raw: String, source: String?): ProxyInfo? {
        val body = raw.removePrefix("trojan://").trim()
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) body.substring(hashIdx + 1).trim() else ""
        val core = if (hashIdx >= 0) body.substring(0, hashIdx) else body

        val atIdx = core.indexOf('@')
        if (atIdx < 0) return null
        val password = core.substring(0, atIdx).trim().ifBlank { return null }
        val hostPortAndQuery = core.substring(atIdx + 1)
        val hostPort = hostPortAndQuery.substringBefore('?')
        val queryStr = hostPortAndQuery.substringAfter('?', "")

        val (host, port) = splitHostPort(hostPort) ?: return null
        val q = parseQuery(queryStr)
        val sni = q["sni"] ?: q["peer"] ?: host
        val tls = q["security"] ?: "tls"
        val net = q["type"] ?: "tcp"

        val cfg = JSONObject()
            .put("type", "trojan")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)
            .put("tls", if (tls == "none") "none" else "tls")
            .put("sni", sni)
            .put("network", net)
            .put("host", q["host"])
            .put("path", q["path"])
            .put("allowInsecure", q["allowInsecure"] == "1" || q["allowInsecure"] == "true")

        return ProxyInfo(
            host = host, port = port, type = ProxyType.TROJAN,
            username = password, password = null,
            nodeName = name.ifBlank { host },
            configJson = cfg.toString(),
            source = source ?: "trojan",
        )
    }

    // =====================================================================
    // vless://uuid@host:port?encryption=none&security=tls&type=ws&host=...&path=...#name
    // =====================================================================
    private fun parseVless(raw: String, source: String?): ProxyInfo? {
        val body = raw.removePrefix("vless://").trim()
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) body.substring(hashIdx + 1).trim() else ""
        val core = if (hashIdx >= 0) body.substring(0, hashIdx) else body

        val atIdx = core.indexOf('@')
        if (atIdx < 0) return null
        val uuid = core.substring(0, atIdx).trim().ifBlank { return null }
        val hostPortAndQuery = core.substring(atIdx + 1)
        val hostPort = hostPortAndQuery.substringBefore('?')
        val queryStr = hostPortAndQuery.substringAfter('?', "")

        val (host, port) = splitHostPort(hostPort) ?: return null
        val q = parseQuery(queryStr)
        val tls = q["security"] ?: "none"
        val sni = q["sni"] ?: q["peer"] ?: host
        val net = q["type"] ?: "tcp"

        val cfg = JSONObject()
            .put("type", "vless")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("tls", if (tls == "none") "none" else "tls")
            .put("sni", sni)
            .put("network", net)
            .put("host", q["host"])
            .put("path", q["path"])
            .put("flow", q["flow"])
            .put("encryption", q["encryption"] ?: "none")
            .put("fp", q["fp"])
            .put("allowInsecure", q["allowInsecure"] == "1" || q["allowInsecure"] == "true")

        return ProxyInfo(
            host = host, port = port, type = ProxyType.VLESS,
            username = uuid, password = null,
            nodeName = name.ifBlank { host },
            configJson = cfg.toString(),
            source = source ?: "vless",
        )
    }

    // =====================================================================
    // ss:// 兼容两种形态：
    //   1) ss://BASE64(method:password)@host:port#name
    //   2) ss://BASE64(method:password@host:port)#name（老式，无 @ 分隔）
    // =====================================================================
    private fun parseSs(raw: String, source: String?): ProxyInfo? {
        val body = raw.removePrefix("ss://").trim()
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) body.substring(hashIdx + 1).trim() else ""
        val core = if (hashIdx >= 0) body.substring(0, hashIdx) else body

        // 去掉 plugin 参数（保留到 ? 之前）
        val main = core.substringBefore('?')

        // 形态 1：@ 分隔
        val atIdx = main.indexOf('@')
        if (atIdx >= 0) {
            val userInfo = main.substring(0, atIdx)
            val (host, port) = splitHostPort(main.substring(atIdx + 1)) ?: return null
            val (method, password) = decodeSsUserInfo(userInfo) ?: return null
            return buildSs(host, port, method, password, name, source)
        }

        // 形态 2：整段 base64
        val decoded = runCatching { b64Decode(main) }.getOrNull() ?: return null
        val atIdx2 = decoded.indexOf('@')
        if (atIdx2 >= 0) {
            val userInfo = decoded.substring(0, atIdx2)
            val (host, port) = splitHostPort(decoded.substring(atIdx2 + 1)) ?: return null
            val (method, password) = decodeSsUserInfo(userInfo) ?: return null
            return buildSs(host, port, method, password, name, source)
        }
        return null
    }

    private fun buildSs(
        host: String, port: Int, method: String, password: String,
        name: String, source: String?,
    ): ProxyInfo {
        val cfg = JSONObject()
            .put("type", "shadowsocks")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)
        return ProxyInfo(
            host = host, port = port, type = ProxyType.SHADOWSOCKS,
            username = method, password = password,
            nodeName = name.ifBlank { host },
            configJson = cfg.toString(),
            source = source ?: "shadowsocks",
        )
    }

    /** userInfo: base64(method:password) 或明文 method:password */
    private fun decodeSsUserInfo(userInfo: String): Pair<String, String>? {
        val decoded = runCatching { b64Decode(userInfo) }.getOrNull() ?: userInfo
        val idx = decoded.indexOf(':')
        if (idx < 0) return null
        val method = decoded.substring(0, idx).trim()
        val password = decoded.substring(idx + 1).trim()
        if (method.isBlank() || password.isBlank()) return null
        return method to password
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val host: String
        val portStr: String
        if (s.startsWith("[")) {
            val end = s.lastIndexOf(']')
            if (end < 0) return null
            host = s.substring(1, end)
            portStr = s.substring(end + 1).trimStart(':')
        } else {
            val idx = s.lastIndexOf(':')
            if (idx < 0) return null
            host = s.substring(0, idx)
            portStr = s.substring(idx + 1)
        }
        val p = portStr.toIntOrNull() ?: return null
        if (p !in 1..65535) return null
        if (host.isBlank()) return null
        return host to p
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in q.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val k = pair.substring(0, eq)
            val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            map[k] = v
        }
        return map
    }

    private fun b64Decode(s: String): String {
        var clean = s.trim()
        // URL-safe base64（- 与 _）
        clean = clean.replace('-', '+').replace('_', '/')
        // 补齐 padding
        val rem = clean.length % 4
        if (rem != 0) clean = clean.padEnd(clean.length + (4 - rem), '=')
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
