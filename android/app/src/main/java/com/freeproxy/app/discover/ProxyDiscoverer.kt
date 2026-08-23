package com.freeproxy.app.discover

import android.util.Base64
import com.freeproxy.app.data.model.AnonymityLevel
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.discover.ProxyUrlParser.extractHostPortFromLine
import com.freeproxy.app.discover.ProxyUrlParser.parseHostPort
import com.freeproxy.app.discover.ProxyUrlParser.parse as parseProxyLine
import com.freeproxy.app.net.OkHttpFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Bogon IP 过滤：检查某个 host 是否属于本地/保留/多播等不可路由的地址
 * IPv6 直接全过滤（当前纯 IPv4 TUN）
 */
internal object IpFilter {
    /**
     * 判断一个 host 是否是 bogon。
     * IPv6（以 "[" 开头）直接 true。
     * IPv4：把 a.b.c.d 转成无符号 32 位 Int 后按 CIDR 范围比较。
     */
    fun isBogon(host: String): Boolean {
        if (host.isBlank()) return true
        // IPv6 直接拒绝
        if (host.startsWith("[")) return true
        val ipInt = hostToInt(host) ?: return true // 解析失败也视为 bogon
        val ip = ipInt.toLong() and 0xFFFFFFFFL // 转成 Long 方便无符号比较

        // 0.0.0.0/8      : 0.0.0.0   - 0.255.255.255
        if (ip in range(0 shl 24, 8)) return true
        // 10.0.0.0/8     : 10.0.0.0  - 10.255.255.255
        if (ip in range(10 shl 24, 8)) return true
        // 100.64.0.0/10  : 100.64.0.0 - 100.127.255.255
        if (ip in range((100 shl 24) or (64 shl 16), 10)) return true
        // 127.0.0.0/8    : 127.0.0.0 - 127.255.255.255
        if (ip in range(127 shl 24, 8)) return true
        // 169.254.0.0/16 : 169.254.0.0 - 169.254.255.255
        if (ip in range((169 shl 24) or (254 shl 16), 16)) return true
        // 172.16.0.0/12  : 172.16.0.0 - 172.31.255.255
        if (ip in range((172 shl 24) or (16 shl 16), 12)) return true
        // 192.168.0.0/16 : 192.168.0.0 - 192.168.255.255
        if (ip in range((192 shl 24) or (168 shl 16), 16)) return true
        // 198.18.0.0/15  : 198.18.0.0 - 198.19.255.255
        if (ip in range((198 shl 24) or (18 shl 16), 15)) return true
        // 224.0.0.0/4    : 224.0.0.0 - 239.255.255.255
        if (ip in range(224 shl 24, 4)) return true
        // 240.0.0.0/4    : 240.0.0.0 - 255.255.255.254
        if (ip in range(240 shl 24, 4)) return true
        // 255.255.255.255/32
        if (ip == 0xFFFFFFFFL) return true
        return false
    }

    /**
     * host a.b.c.d → 无符号 32 位 Int（a 在高位）；失败返回 null
     */
    private fun hostToInt(host: String): Int? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            result = (result shl 8) or n
        }
        return result
    }

    /**
     * 根据 network(int, host 字节序高位为 a) 和 prefixLen 返回闭区间 [网络地址, 广播地址]
     * 结果是 Long（无符号 32-bit 语义）以便比较
     */
    private fun range(networkInt: Int, prefixLen: Int): LongRange {
        val net = networkInt.toLong() and 0xFFFFFFFFL
        val mask = if (prefixLen == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL
        val start = net and mask
        val end = start or (mask.inv() and 0xFFFFFFFFL)
        return start..end
    }
}

/**
 * 代理发现：并发抓取代理源，解析入库
 */
class ProxyDiscoverer(
    private val ctx: android.content.Context,
    private val client: OkHttpClient,
    private val repo: com.freeproxy.app.data.ProxyRepository,
) {

    data class Progress(val fetched: Int, val total: Int, val message: String)

    /**
     * 并发抓取所有源，返回(抓取列表, 新增入库数量)
     * 对每个源按 (url + mirrors) 逐个尝试，第一个非空即停止；主源失败走 mirror 时输出 "fallback" 进度。
     * 超时使用 src.timeoutMs（通过 OkHttpFactory.perCall 设置单 call 超时）
     * sources 为空时自动合并「内置源 + 用户自定义源」（从数据库读取）
     */
    suspend fun discover(
        sources: List<ProxySource> = emptyList(),
        extraHeaders: Map<String, String> = mapOf("User-Agent" to "Mozilla/5.0 Relay/1.0"),
        onProgress: suspend (Progress) -> Unit = {},
    ): Pair<List<ProxyInfo>, Int> = withContext(Dispatchers.IO) {
        val effective = if (sources.isEmpty()) repo.allSources() else sources
        val enabledSources = effective.filter { it.enabled }
        val results = enabledSources.mapIndexed { idx, src ->
            async {
                runCatching {
                    onProgress(Progress(idx, enabledSources.size, "抓取: ${src.name}"))

                    val urls = listOf(src.url) + src.mirrors
                    var parsed: List<ProxyInfo> = emptyList()
                    for ((i, url) in urls.withIndex()) {
                        val text = fetch(url, extraHeaders, src.timeoutMs)
                        if (!text.isNullOrBlank()) {
                            parsed = parse(text, src)
                            if (parsed.isNotEmpty()) {
                                if (i > 0) {
                                    onProgress(
                                        Progress(
                                            idx,
                                            enabledSources.size,
                                            "fallback: ${src.name} 使用镜像 #$i 成功，解析 ${parsed.size} 个"
                                        )
                                    )
                                }
                                break
                            }
                        }
                    }
                    // 回写抓取状态（数据源页可视化 ok/err + 条数）
                    runCatching {
                        if (parsed.isEmpty()) repo.setSourceStatus(src.url, false, 0)
                        else repo.setSourceStatus(src.url, true, parsed.size)
                    }
                    parsed
                }.getOrDefault(emptyList())
            }
        }.awaitAll()

        val all = results.flatten().distinctBy { it.display() }
        onProgress(Progress(enabledSources.size, enabledSources.size, "解析完成，共 ${all.size} 个，入库中…"))
        val added = repo.addAllIfAbsent(all)
        onProgress(Progress(enabledSources.size, enabledSources.size, "新增入库 $added 个代理"))
        all to added
    }

    private fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int): String? {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        val perCallClient = OkHttpFactory.perCall(client, timeoutMs)
        return runCatching {
            perCallClient.newCall(req).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        }.getOrNull()
    }

    fun parse(text: String, src: ProxySource): List<ProxyInfo> {
        // 0) 订阅整体 Base64：无明文 "://" 时先尝试整体解码再走常规解析
        var body = text
        if (!body.contains("://")) decodeBase64Body(body)?.let { body = it }
        val format = if (src.format == ProxySource.Format.AUTO) detectFormat(body) else src.format
        val fallbackType = src.schemeHint ?: inferTypeFromName(src.name)
        val raw = when (format) {
            ProxySource.Format.JSON -> parseJson(body, src.name, fallbackType)
            ProxySource.Format.CSV -> parseCsv(body, src.name, fallbackType)
            ProxySource.Format.PLAIN -> parsePlain(body, src.name, fallbackType)
            ProxySource.Format.HTML -> parseHtml(body, src.name, fallbackType)
            ProxySource.Format.AUTO -> parsePlain(body, src.name, fallbackType)
        }
        // 加密节点 host 常为域名，跳过 bogon(IP) 过滤；明文代理仍过滤内网/保留地址
        return raw.filter { it.type.isEncryptedNode || !IpFilter.isBogon(it.host) }
    }

    /**
     * 整体 Base64 订阅解码：
     * 内容仅含 Base64 字符集（兼容 urlsafe）且解码后出现分享链接或 ip:port 才认可，
     * 防止把普通文本误当 Base64。
     */
    private fun decodeBase64Body(text: String): String? {
        val compact = text.filter { !it.isWhitespace() }
        if (compact.length < 24) return null
        if (compact.any { !(it.isLetterOrDigit() || it in "+/=-_") }) return null
        return runCatching {
            val normalized = compact.replace('-', '+').replace('_', '/')
            val decoded = String(Base64.decode(normalized, Base64.NO_WRAP), StandardCharsets.UTF_8)
            if (decoded.contains("://") ||
                Regex("\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}").containsMatchIn(decoded)
            ) decoded else null
        }.getOrNull()
    }

    private fun inferTypeFromName(name: String): ProxyType = when {
        name.contains("socks5", true) -> ProxyType.SOCKS5
        name.contains("socks4", true) -> ProxyType.SOCKS4
        name.contains("https", true) -> ProxyType.HTTPS
        else -> ProxyType.HTTP
    }

    private fun detectFormat(t: String): ProxySource.Format {
        val trimmed = t.trimStart()
        return when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> ProxySource.Format.JSON
            trimmed.startsWith("<") -> ProxySource.Format.HTML
            t.lines().any { it.contains(",") && it.contains(":") } &&
                t.lines().take(5).all { !it.trim().matches(IP_PORT_REGEX) } -> ProxySource.Format.CSV
            else -> ProxySource.Format.PLAIN
        }
    }

    // ================================================================
    // Plain: ip:port 每行一个，支持 scheme://user:pass@host:port
    // 以及多分隔符行：ip:port,type,country,anon / ; / | / \t
    // ================================================================
    private fun parsePlain(text: String, source: String, fallbackType: ProxyType): List<ProxyInfo> {
        val out = mutableListOf<ProxyInfo>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue

            // 0) 加密节点分享链接（vmess/trojan/vless/ss）——订阅源主力形态
            val shareNode = ShareLinkParser.parse(line, source)
            if (shareNode != null) {
                out.add(shareNode)
                continue
            }

            // 1) URL 格式 scheme://user:pass@host:port （复用 ProxyUrlParser）
            val proxy = parseProxyLine(line, fallbackType, null)
            if (proxy != null && !line.contains(',') && !line.contains(';') &&
                !line.contains('|') && !line.contains('\t')
            ) {
                // 纯 URL 无多余字段 → 直接入库（附 source）
                out.add(proxy.copy(source = source))
                continue
            }

            // 2) 切出 host:port 主体，剩下的内容用多分隔符再分字段（复用 ProxyUrlParser）
            val hostPortResult = extractHostPortFromLine(line) ?: continue
            val host = hostPortResult.host
            val port = hostPortResult.port
            val rest = hostPortResult.rest

            var type = fallbackType
            var country: String? = null
            var countryCode: String? = null
            var anonymity: AnonymityLevel = AnonymityLevel.UNKNOWN
            var https = false

            if (rest != null) {
                val parts = rest.split(',', ';', '|', '\t').map { it.trim() }.filter { it.isNotBlank() }
                for (part in parts) {
                    val lower = part.lowercase()
                    if (lower == "yes" || lower == "true" || lower == "1" || lower == "no" || lower == "false" || lower == "0") {
                        if (lower == "yes" || lower == "true" || lower == "1") https = true
                        continue
                    }
                    val t = ProxyType.from(part)
                    if (t != ProxyType.UNKNOWN) {
                        type = t
                        continue
                    }
                    val a = AnonymityLevel.from(part)
                    if (a != AnonymityLevel.UNKNOWN) {
                        anonymity = a
                        continue
                    }
                    if (part.length == 2 && part.all { it.isLetter() }) {
                        countryCode = part.uppercase()
                        if (country == null) country = countryCode
                    } else {
                        if (country == null) country = part
                    }
                }
            }

            out.add(
                ProxyInfo(
                    host = host, port = port, type = type,
                    country = country, countryCode = countryCode,
                    anonymity = anonymity, https = https,
                    source = source
                )
            )
        }
        return out
    }

    // ================================================================
    // JSON 解析（保留原 GeoNode / PubProxy / 通用逻辑，补 fallbackType 支持）
    // ================================================================
    private fun parseJson(text: String, source: String, fallbackType: ProxyType): List<ProxyInfo> {
        val out = mutableListOf<ProxyInfo>()
        val root = runCatching {
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        }.getOrNull() ?: return out

        // GeoNode: { data:[{ip,port,protocols[0],country,anonymityLevel,...}] }
        if (root is JSONObject && root.has("data")) {
            val arr = root.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val host = o.optString("ip").takeIf { it.isNotBlank() } ?: continue
                    val port = o.optInt("port").takeIf { it in 1..65535 } ?: continue
                    val protos = o.optJSONArray("protocols")
                    val type = if (protos != null && protos.length() > 0)
                        ProxyType.from(protos.optString(0))
                    else fallbackType
                    out.add(
                        ProxyInfo(
                            host = host, port = port, type = type,
                            country = o.optString("country").nullIfBlank(),
                            countryCode = o.optString("country").nullIfBlank(),
                            anonymity = AnonymityLevel.from(o.optString("anonymityLevel")),
                            https = o.optString("ssl").equals("yes", true) ||
                                o.optBoolean("https"),
                            source = source
                        )
                    )
                }
                return out
            }
        }

        // pubproxy: {data:[{ip,port,...}]}
        if (root is JSONObject && root.has("data")) {
            val arr = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("ip").nullIfBlank() ?: continue
                val port = o.optString("port").toIntOrNull() ?: continue
                val t = ProxyType.from(o.optString("type"))
                out.add(
                    ProxyInfo(
                        host = host, port = port,
                        type = if (t == ProxyType.UNKNOWN) fallbackType else t,
                        country = o.optString("country").nullIfBlank(),
                        anonymity = AnonymityLevel.from(o.optString("anonymity")),
                        https = o.optString("support_https").equals("1"),
                        source = source
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }

        // 通用：数组包含 {ip/host,port,type?...} 或 {proxy:"host:port"}
        fun fromObj(o: JSONObject): ProxyInfo? {
            val proxyStr = o.optString("proxy").nullIfBlank()
                ?: o.optString("hostPort").nullIfBlank()
            if (proxyStr != null) {
                val (h, p) = parseHostPort(proxyStr) ?: return null
                val t = ProxyType.from(o.optString("type"))
                return ProxyInfo(
                    host = h, port = p,
                    type = if (t == ProxyType.UNKNOWN) fallbackType else t,
                    country = o.optString("country").nullIfBlank(),
                    anonymity = AnonymityLevel.from(o.optString("anon")),
                    source = source
                )
            }
            val host = o.optString("ip").nullIfBlank() ?: o.optString("host").nullIfBlank() ?: return null
            val port = o.optInt("port").takeIf { it in 1..65535 }
                ?: o.optString("port").toIntOrNull() ?: return null
            val t = ProxyType.from(o.optString("protocol") ?: o.optString("type"))
            return ProxyInfo(
                host = host, port = port,
                type = if (t == ProxyType.UNKNOWN) fallbackType else t,
                country = o.optString("country").nullIfBlank()
                    ?: o.optString("country_name").nullIfBlank(),
                countryCode = o.optString("countryCode").nullIfBlank()
                    ?: o.optString("country_code").nullIfBlank(),
                anonymity = AnonymityLevel.from(o.optString("anonymity")),
                https = o.optBoolean("https") || o.optString("ssl").equals("yes", true),
                source = source
            )
        }

        val arr: JSONArray? = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("proxies") ?: root.optJSONArray("list")
                ?: root.optJSONArray("items") ?: root.optJSONArray("data")
            else -> null
        }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val any = arr.opt(i)
                when (any) {
                    is JSONObject -> fromObj(any)?.let { out.add(it) }
                    is String -> parseHostPort(any)?.let { (h, p) ->
                        out.add(ProxyInfo(host = h, port = p, type = fallbackType, source = source))
                    }
                }
            }
        }
        return out
    }

    // ================================================================
    // CSV：列顺序模糊匹配（按列名包含关键字）
    // 识别列：ip/host, port, type/protocol, country, code(country_code), anon/anonymity, https/ssl/support_https
    // ================================================================
    private fun parseCsv(text: String, source: String, fallbackType: ProxyType): List<ProxyInfo> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        // 判断分隔符：tab / 分号 / 逗号
        val sep = when {
            lines.first().contains("\t") -> "\t"
            lines.first().contains(";") -> ";"
            else -> ","
        }
        // header: 小写 + trim
        val header = lines.first().split(sep).map { it.trim().lowercase() }

        // 按关键字找列索引（-1 表示没找到）
        fun findCol(vararg keywords: String): Int {
            for (kw in keywords) {
                val i = header.indexOfFirst { it.contains(kw) }
                if (i >= 0) return i
            }
            return -1
        }

        // 关键字按需求匹配：
        // ip/host/proxy: 只要包含 ip 或 host 或 proxy(proxy 可能是 proxy=host:port 整体列，后面单独处理)
        val ipCol = findCol("ip", "host") // 注意 proxy 列可能是 "ip:port" 整体，单独判断
        val proxyCol = header.indexOfFirst { it == "proxy" || it == "hostport" || it == "proxy_address" }
        val portCol = findCol("port")
        val typeCol = findCol("type", "protocol")
        // country: 含 "country" 且不含 "code"
        val countryCol = header.indexOfFirst { it.contains("country") && !it.contains("code") }
        val codeCol = header.indexOfFirst {
            (it.contains("country") && it.contains("code")) || it == "code" || it == "iso"
        }
        val anonCol = findCol("anon")
        val httpsCol = header.indexOfFirst {
            it.contains("https") || it.contains("ssl") || it.contains("support_https")
        }

        val startIdx = if (ipCol >= 0 || portCol >= 0 || proxyCol >= 0 || typeCol >= 0 || countryCol >= 0) 1 else 0
        val out = mutableListOf<ProxyInfo>()
        for (line in lines.subList(startIdx, lines.size)) {
            val cols = line.split(sep).map { it.trim() }
            fun get(col: Int): String? = cols.getOrNull(col)?.takeIf { it.isNotBlank() }

            // 优先 proxy 整体列（proxy=host:port）
            val proxyStr = if (proxyCol >= 0) get(proxyCol) else null
            var host: String? = null
            var port: Int? = null
            if (proxyStr != null) {
                val hp = parseHostPort(proxyStr)
                if (hp != null) {
                    host = hp.first
                    port = hp.second
                }
            }
            if (host == null || port == null) {
                host = if (ipCol >= 0) get(ipCol) else null
                port = if (portCol >= 0) get(portCol)?.toIntOrNull() else null
            }
            if (host == null || port == null || port !in 1..65535) continue

            val typeStr = if (typeCol >= 0) get(typeCol) else null
            val type = ProxyType.from(typeStr).let { if (it == ProxyType.UNKNOWN) fallbackType else it }
            val country = if (countryCol >= 0) get(countryCol) else null
            val codeVal = if (codeCol >= 0) get(codeCol) else null
            val anon = if (anonCol >= 0) AnonymityLevel.from(get(anonCol)) else AnonymityLevel.UNKNOWN
            val httpsStr = if (httpsCol >= 0) get(httpsCol)?.lowercase() else null
            val https = httpsStr != null && (httpsStr == "yes" || httpsStr == "true" || httpsStr == "1")

            out.add(
                ProxyInfo(
                    host = host, port = port, type = type,
                    country = country ?: codeVal, countryCode = codeVal,
                    anonymity = anon, https = https,
                    source = source
                )
            )
        }
        return out
    }

    // ================================================================
    // HTML：真正解析 <table>，逐行读取 <th>/<td>
    // 兼容 free-proxy-list.net / sslproxies.org ：
    //   IP Address | Port | Code | Country | Anonymity | Google | Https | Last Checked
    // ================================================================
    private fun parseHtml(text: String, source: String, fallbackType: ProxyType): List<ProxyInfo> {
        val out = mutableListOf<ProxyInfo>()

        // 1. 抓所有 <table ...> ... </table> 块（不区分大小写，允许跨行、允许属性）
        val tablePattern = Pattern.compile("""<table[\s\S]*?</table>""", Pattern.CASE_INSENSITIVE)
        val tableMatcher = tablePattern.matcher(text)
        while (tableMatcher.find()) {
            val tableHtml = tableMatcher.group() ?: continue
            parseSingleHtmlTable(tableHtml, fallbackType, source)?.let { out.addAll(it) }
        }

        // 2. 若完全没解析到（或 table 没找到），回退 ip:port 正则兜底
        if (out.isEmpty()) {
            val m = Pattern.compile("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})""").matcher(text)
            while (m.find()) {
                val host = m.group(1) ?: continue
                val port = m.group(2)?.toIntOrNull() ?: continue
                if (port in 1..65535) out.add(
                    ProxyInfo(host = host, port = port, type = fallbackType, source = source)
                )
            }
        }
        return out
    }

    /**
     * 解析单个 HTML table：
     * - 先看 <thead> 的 <th>，没有就看首行 <tr> 里的 <td>/<th> 作为 header
     * - 每行按索引读取字段
     */
    private fun parseSingleHtmlTable(
        tableHtml: String,
        fallbackType: ProxyType,
        source: String
    ): List<ProxyInfo>? {
        // 把所有 <tr ...>...</tr> 按顺序抓出来（包括 thead/tbody 里的）
        val trPattern = Pattern.compile("""<tr[\s\S]*?</tr>""", Pattern.CASE_INSENSITIVE)
        val trMatcher = trPattern.matcher(tableHtml)
        val rows = mutableListOf<List<String>>()
        while (trMatcher.find()) {
            val trHtml = trMatcher.group() ?: continue
            // 对每个 tr，抓出 <td ...>...</td> 或 <th ...>...</th> 的内容
            val cellPattern = Pattern.compile("""<t[dh][\s\S]*?</t[dh]>""", Pattern.CASE_INSENSITIVE)
            val cellMatcher = cellPattern.matcher(trHtml)
            val cells = mutableListOf<String>()
            while (cellMatcher.find()) {
                val rawCell = cellMatcher.group() ?: continue
                // 去掉标签 + 去 HTML 实体
                cells.add(stripHtml(rawCell))
            }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        if (rows.size < 2) return null // 至少需要表头 + 一行数据

        // 判定 header：看第一行里是否含有 ip/host/port 等关键字；若有则其为 header，否则首行不是 header
        val header = rows.first().map { it.lowercase() }
        val hasHeader = header.any {
            it.contains("ip") || it.contains("host") || it.contains("port") ||
                it.contains("country") || it.contains("anon") || it.contains("https") ||
                it.contains("ssl") || it.contains("protocol") || it.contains("type")
        }
        val headerCells: List<String>
        val dataStart: Int
        if (hasHeader) {
            headerCells = header
            dataStart = 1
        } else {
            // 没有显式 header，按常见顺序猜（free-proxy-list 结构）：ip, port, code, country, anon, google, https, ...
            headerCells = listOf("ip", "port", "code", "country", "anonymity", "google", "https", "last checked", "")
            dataStart = 0
        }

        // 按 header 关键字找列
        fun findColIdx(vararg keywords: String): Int {
            for (kw in keywords) {
                val i = headerCells.indexOfFirst { it.contains(kw) }
                if (i >= 0) return i
            }
            return -1
        }
        val ipIdx = findColIdx("ip", "host")
        val portIdx = findColIdx("port")
        val typeIdx = findColIdx("type", "protocol")
        val countryIdx = headerCells.indexOfFirst { it.contains("country") && !it.contains("code") }
        val codeIdx = headerCells.indexOfFirst {
            (it.contains("country") && it.contains("code")) || it == "code" || it == "iso"
        }
        val anonIdx = findColIdx("anon")
        val httpsIdx = headerCells.indexOfFirst { it.contains("https") || it.contains("ssl") }

        val out = mutableListOf<ProxyInfo>()
        for (row in rows.subList(dataStart, rows.size)) {
            fun cell(idx: Int): String? = row.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() }

            // ip + port 是必须的
            val host = if (ipIdx >= 0) cell(ipIdx) else null
            val port = if (portIdx >= 0) cell(portIdx)?.toIntOrNull() else null
            if (host == null || port == null || port !in 1..65535) continue
            // host 至少看起来像 IP（4 段数字）
            if (!IPV4_LIKE.matcher(host).matches()) continue

            val typeStr = if (typeIdx >= 0) cell(typeIdx) else null
            val type = ProxyType.from(typeStr).let { if (it == ProxyType.UNKNOWN) fallbackType else it }

            val country = if (countryIdx >= 0) cell(countryIdx) else null
            val code = if (codeIdx >= 0) cell(codeIdx) else null

            val anonStr = if (anonIdx >= 0) cell(anonIdx) else null
            val anon = if (anonStr != null) {
                // 兼容 free-proxy-list.net 文案：elite proxy / anonymous / transparent
                val l = anonStr.lowercase()
                when {
                    l.contains("elite") -> AnonymityLevel.ELITE
                    l.contains("anonymous") -> AnonymityLevel.ANONYMOUS
                    l.contains("transparent") -> AnonymityLevel.TRANSPARENT
                    else -> AnonymityLevel.from(anonStr)
                }
            } else AnonymityLevel.UNKNOWN

            val https = if (httpsIdx >= 0) {
                val v = cell(httpsIdx)?.lowercase()
                v == "yes" || v == "true" || v == "1" || v == "https"
            } else false

            out.add(
                ProxyInfo(
                    host = host, port = port, type = type,
                    country = country ?: code, countryCode = code,
                    anonymity = anon, https = https,
                    source = source
                )
            )
        }
        return out
    }

    /**
     * 剥去 HTML 标签 + 反转常用 HTML 实体
     */
    private fun stripHtml(cell: String): String {
        var s = cell
            .replace(Regex("""<[^>]+>"""), "") // 去掉所有标签
            .trim()
        // 基本实体
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        // &#NNN; 数字实体
        val dec = Regex("""&#(\d+);""")
        s = dec.replace(s) { m ->
            m.groupValues.getOrNull(1)?.toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return s
    }

    private fun List<String>.containsAny(set: Set<String>) = any { it in set }
    private fun String.nullIfBlank(): String? = ifBlank { null }

    companion object {
        val IP_PORT_REGEX = Pattern.compile("""^[^\s:]+:\d{1,5}$""").toRegex()
        val IPV4_LIKE: Pattern = Pattern.compile("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    }
}
