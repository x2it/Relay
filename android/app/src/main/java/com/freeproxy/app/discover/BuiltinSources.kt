package com.freeproxy.app.discover

import com.freeproxy.app.data.model.ProxyType

/**
 * 一个代理源：定义从 URL 抓取并解析出 ProxyInfo 列表
 */
data class ProxySource(
    val name: String,
    val url: String,
    val format: Format = Format.AUTO,
    val schemeHint: ProxyType? = null,
    val timeoutMs: Int = 15_000,
    val enabled: Boolean = true,
    val mirrors: List<String> = emptyList(),
    val category: Category = Category.B, // A=JSON原生API, B=GitHub RAW, C=HTML表, D=TXT/CSV镜像池, E=订阅(share-link/Base64)
) {
    enum class Format { AUTO, JSON, PLAIN, CSV, HTML }
    enum class Category { A, B, C, D, E, CUSTOM }
}

/**
 * GitHub RAW URL 镜像工厂：把 raw.githubusercontent.com 的 URL 转成两个镜像
 * （fastgit / ghproxy.com 已停服，换用存活的 gh 前缀镜像）
 * mirrors[0] = gh-proxy.com 前缀包装
 * mirrors[1] = ghfast.top 前缀包装
 */
private fun ghMirrors(url: String): List<String> {
    val origin = "https://raw.githubusercontent.com/"
    if (!url.startsWith(origin)) return emptyList()
    val suffix = url.removePrefix(origin) // USER/REPO/BRANCH/PATH
    return listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/$suffix",
        "https://ghfast.top/https://raw.githubusercontent.com/$suffix",
    )
}

/**
 * 内置的公开免费代理源（45 个，覆盖 HTTP/HTTPS/SOCKS4/SOCKS5 + 加密节点订阅）
 * A: JSON 原生 API
 * B: GitHub RAW（含 HTTPS / SOCKS4 补齐）
 * C: HTML 表解析
 * D: TXT/CSV 镜像池
 * E: 订阅源（分享链接 / Base64 → ShareLinkParser 解析为 VMess/Trojan/VLESS/SS）
 */
object BuiltinSources {

    val default: List<ProxySource> = buildList {

        // ============== A 类：JSON/API 源 (≥2，实际 4 个) ==============
        // A1: GeoNode JSON API
        add(
            ProxySource(
                name = "GeoNode API",
                url = "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc",
                format = ProxySource.Format.JSON,
                category = ProxySource.Category.A,
                timeoutMs = 20_000,
            )
        )
        // A2: PubProxy JSON API
        add(
            ProxySource(
                name = "PubProxy API",
                url = "http://pubproxy.com/api/proxy?limit=20&format=json&type=http",
                format = ProxySource.Format.JSON,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.A,
                timeoutMs = 15_000,
            )
        )
        // A3: ProxyScrape HTTP (PLAIN 但属于 API 类)
        add(
            ProxySource(
                name = "ProxyScrape HTTP",
                url = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=all",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.A,
            )
        )
        // A4: proxy-list.download HTTP (PLAIN 但属于 API 类)
        add(
            ProxySource(
                name = "proxy-list.download HTTP",
                url = "https://www.proxy-list.download/api/v1/get?type=http",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.A,
            )
        )

        // ============== B 类：GitHub RAW (≥12，实际 16 个) ==============
        val ghTimeout = 20_000
        // B1-B2: TheSpeedX
        add(
            ProxySource(
                name = "github/TheSpeedX HTTP",
                url = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/TheSpeedX SOCKS5",
                url = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt"),
            )
        )
        // B3-B4: ShiftyTR
        add(
            ProxySource(
                name = "github/ShiftyTR HTTP",
                url = "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/ShiftyTR HTTPS",
                url = "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/https.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTPS,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/https.txt"),
            )
        )
        // B5: hookzof
        add(
            ProxySource(
                name = "github/hookzof SOCKS5",
                url = "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt"),
            )
        )
        // B6: clarketm
        add(
            ProxySource(
                name = "github/clarketm",
                url = "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt"),
            )
        )
        // B7-B8: monosans
        add(
            ProxySource(
                name = "github/monosans HTTP",
                url = "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/monosans SOCKS5",
                url = "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"),
            )
        )
        // B9-B11: prxchk (http, socks4, socks5)
        add(
            ProxySource(
                name = "github/prxchk HTTP",
                url = "https://raw.githubusercontent.com/prxchk/proxy-list/main/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/prxchk/proxy-list/main/http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/prxchk SOCKS4",
                url = "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks4.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS4,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/prxchk/proxy-list/main/socks4.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/prxchk SOCKS5",
                url = "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt"),
            )
        )
        // B12-B13: jetkai (http, socks5)
        add(
            ProxySource(
                name = "github/jetkai HTTP",
                url = "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/jetkai SOCKS5",
                url = "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt"),
            )
        )
        // B14: ALIILAPRO
        add(
            ProxySource(
                name = "github/ALIILAPRO HTTP",
                url = "https://raw.githubusercontent.com/ALIILAPRO/Proxy/main/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/ALIILAPRO/Proxy/main/http.txt"),
            )
        )
        // B15: officialputuid
        add(
            ProxySource(
                name = "github/officialputuid HTTP",
                url = "https://raw.githubusercontent.com/officialputuid/Proxy-List/main/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/officialputuid/Proxy-List/main/http.txt"),
            )
        )
        // B16: zuoxiaodongai (聚合)
        add(
            ProxySource(
                name = "github/zuoxiaodongai 聚合",
                url = "https://raw.githubusercontent.com/zuoxiaodongai/proxies/main/all.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/zuoxiaodongai/proxies/main/all.txt"),
            )
        )

        // ============== C 类：HTML 表格解析 (≥2，实际 2 个) ==============
        add(
            ProxySource(
                name = "free-proxy-list.net",
                url = "https://free-proxy-list.net/",
                format = ProxySource.Format.HTML,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.C,
                timeoutMs = 20_000,
            )
        )
        add(
            ProxySource(
                name = "sslproxies.org",
                url = "https://www.sslproxies.org/",
                format = ProxySource.Format.HTML,
                schemeHint = ProxyType.HTTPS,
                category = ProxySource.Category.C,
                timeoutMs = 20_000,
            )
        )

        // ============== D 类：TXT/CSV 镜像池 (≥2，实际 4 个) ==============
        add(
            ProxySource(
                name = "openproxylist.xyz HTTP",
                url = "https://openproxylist.xyz/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.D,
            )
        )
        add(
            ProxySource(
                name = "openproxylist.xyz SOCKS5",
                url = "https://openproxylist.xyz/socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.D,
            )
        )
        add(
            ProxySource(
                name = "ProxyScrape SOCKS4",
                url = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks4&timeout=10000&country=all",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS4,
                category = ProxySource.Category.D,
            )
        )
        add(
            ProxySource(
                name = "ProxyScrape SOCKS5",
                url = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=10000&country=all",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.D,
            )
        )
        // 附加：spys.me (放入 D 类)
        add(
            ProxySource(
                name = "spys.me HTTP",
                url = "https://spys.me/proxy.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.D,
            )
        )

        // ============== B 类补充：补齐 HTTPS / SOCKS4 覆盖 ==============
        add(
            ProxySource(
                name = "github/TheSpeedX SOCKS4",
                url = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks4.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS4,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks4.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/monosans SOCKS4",
                url = "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS4,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/jetkai SOCKS4",
                url = "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks4.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS4,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks4.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/zloi-user HTTP",
                url = "https://raw.githubusercontent.com/zloi-user/hideip.me/main/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/zloi-user/hideip.me/main/http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/zloi-user HTTPS",
                url = "https://raw.githubusercontent.com/zloi-user/hideip.me/main/https.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTPS,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/zloi-user/hideip.me/main/https.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/zloi-user SOCKS4",
                url = "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks4.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS4,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks4.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/zloi-user SOCKS5",
                url = "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/roosterkid HTTPS",
                url = "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTPS,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/roosterkid SOCKS5",
                url = "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/vakhov HTTP",
                url = "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/http.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.HTTP,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/http.txt"),
            )
        )
        add(
            ProxySource(
                name = "github/vakhov SOCKS5",
                url = "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt",
                format = ProxySource.Format.PLAIN,
                schemeHint = ProxyType.SOCKS5,
                category = ProxySource.Category.B,
                timeoutMs = ghTimeout,
                mirrors = ghMirrors("https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"),
            )
        )

        // ============== E 类：订阅源（分享链接/Base64 → VMess/Trojan/VLESS/SS） ==============
        // 免费公共订阅内容随时变动，抓取结果在数据源页可见（ok n:x / err）
        add(
            ProxySource(
                name = "sub/Pawdroid",
                url = "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
                format = ProxySource.Format.AUTO,
                category = ProxySource.Category.E,
                timeoutMs = 25_000,
                mirrors = ghMirrors("https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub"),
            )
        )
        add(
            ProxySource(
                name = "sub/aiboboxx",
                url = "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
                format = ProxySource.Format.AUTO,
                category = ProxySource.Category.E,
                timeoutMs = 25_000,
                mirrors = ghMirrors("https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2"),
            )
        )
        add(
            ProxySource(
                name = "sub/mfuu",
                url = "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray",
                format = ProxySource.Format.AUTO,
                category = ProxySource.Category.E,
                timeoutMs = 25_000,
                mirrors = ghMirrors("https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray"),
            )
        )
        add(
            ProxySource(
                name = "sub/ermaozi",
                url = "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt",
                format = ProxySource.Format.AUTO,
                category = ProxySource.Category.E,
                timeoutMs = 25_000,
                mirrors = ghMirrors("https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt"),
            )
        )
        add(
            ProxySource(
                name = "sub/NoMoreWalls",
                url = "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
                format = ProxySource.Format.AUTO,
                category = ProxySource.Category.E,
                timeoutMs = 25_000,
                mirrors = ghMirrors("https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt"),
            )
        )
        add(
            ProxySource(
                name = "sub/V2RayAggregator",
                url = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
                format = ProxySource.Format.AUTO,
                category = ProxySource.Category.E,
                timeoutMs = 25_000,
                mirrors = ghMirrors("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"),
            )
        )
    }

    /**
     * 按 category 分组统计，方便调试
     */
    fun stats(): Map<ProxySource.Category, Int> {
        val result = mutableMapOf<ProxySource.Category, Int>()
        for (c in ProxySource.Category.values()) result[c] = 0
        for (s in default) result[s.category] = (result[s.category] ?: 0) + 1
        return result
    }
}
