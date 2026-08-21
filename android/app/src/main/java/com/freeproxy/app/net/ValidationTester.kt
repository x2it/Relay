package com.freeproxy.app.net

import com.freeproxy.app.data.model.ProxyInfo
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 代理验证层级
 *  L1_TCP:           代理服务器本身 TCP 握手可达（最粗粒度）
 *  L2_HTTP:          通过代理访问 HTTP 明文站点正常（http://httpbin.org/ip）
 *  L3_HTTPS_GOOGLE:  通过代理访问 HTTPS Google 正常（验证 HTTPS CONNECT 隧道）
 *  L4_SITES:         通过代理访问 YouTube / Facebook 等真实站点正常
 */
enum class ValLevel { L1_TCP, L2_HTTP, L3_HTTPS_GOOGLE, L4_SITES }

/**
 * 四层验证结果聚合
 */
data class ValResult(
    val ok: Boolean,              // 整体是否通过（达到要求的最高层级）
    val okL1: Boolean = false,    // L1 TCP 握手成功
    val okL2: Boolean = false,    // L2 HTTP 明文代理成功
    val okL3: Boolean = false,    // L3 HTTPS (Google) 成功
    val okYt: Boolean = false,    // L4 YouTube 可达
    val okFb: Boolean = false,    // L4 Facebook 可达
    val latencyMs: Long? = null,  // L1 或首次有效响应延迟
    val downloadKbps: Long? = null, // 推算下载速度（若读取了 body）
    val failReason: String? = null,  // 规范化失败原因
    val httpsTunnel: Boolean = true,  // true=支持 HTTPS CONNECT；false=L2通L3败
)

/**
 * L1/L2/L3/L4 分层代理验证器
 *
 *  所有 L2/L3/L4 测试强制走 OkHttpFactory.clientWithProxy（挂代理），
 *  杜绝"直连本机网络却误报代理可用"的假阳性。
 */
class ValidationTester(private val baseClient: OkHttpClient) {

    private val gson = Gson()

    companion object {
        private const val HTTPBIN_IP = "http://httpbin.org/ip"
        private const val GOOGLE_HOME = "https://www.google.com/"
        private const val YOUTUBE_M = "https://m.youtube.com/"
        private const val FACEBOOK_COM = "https://www.facebook.com/"

        private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36 Relay/1.0"
    }

    // ------------------------------------------------------------------
    // 顶层统一入口：按给定 levels 集合逐级跑；失败最多 retries 次
    // ------------------------------------------------------------------
    suspend fun validate(
        p: ProxyInfo,
        levels: Set<ValLevel>,
        timeoutMs: Int,
        retries: Int = 1,
    ): ValResult = withContext(Dispatchers.IO) {
        val sortedLevels = levels
            .sortedBy { it.ordinal } // 按 L1→L2→L3→L4 顺序跑
        var last: ValResult? = null
        var attempt = 0
        while (attempt < retries) {
            attempt++
            var acc = ValResult(ok = false)
            try {
                for (lvl in sortedLevels) {
                    acc = when (lvl) {
                        ValLevel.L1_TCP -> mergeL(acc, testL1(p, timeoutMs))
                        ValLevel.L2_HTTP -> mergeL(acc, testL2(p, timeoutMs))
                        ValLevel.L3_HTTPS_GOOGLE -> mergeL(acc, testL3(p, timeoutMs))
                        ValLevel.L4_SITES -> mergeL(acc, testL4(p, timeoutMs))
                    }
                }
                // 最终 ok = 要求的最高层 ok
                val top = sortedLevels.last()
                val finalOk = when (top) {
                    ValLevel.L1_TCP -> acc.okL1
                    ValLevel.L2_HTTP -> acc.okL2
                    ValLevel.L3_HTTPS_GOOGLE -> acc.okL3
                    ValLevel.L4_SITES -> (acc.okYt || acc.okFb || acc.okL3)
                }
                last = acc.copy(ok = finalOk)
                if (finalOk) break // 成功直接结束，不再重试
            } catch (t: Throwable) {
                last = ValResult(
                    ok = false,
                    okL1 = acc.okL1, okL2 = acc.okL2, okL3 = acc.okL3,
                    okYt = acc.okYt, okFb = acc.okFb,
                    latencyMs = acc.latencyMs, downloadKbps = acc.downloadKbps,
                    failReason = normalize(t), httpsTunnel = acc.httpsTunnel,
                )
            }
        }
        last ?: ValResult(ok = false, failReason = "unknown")
    }

    /** 合并两次层级结果：保留每个 ok 的 OR、非空的延迟/速度、最新的失败原因/httpsTunnel */
    private fun mergeL(prev: ValResult, next: ValResult): ValResult = ValResult(
        ok = prev.ok || next.ok,
        okL1 = prev.okL1 || next.okL1,
        okL2 = prev.okL2 || next.okL2,
        okL3 = prev.okL3 || next.okL3,
        okYt = prev.okYt || next.okYt,
        okFb = prev.okFb || next.okFb,
        latencyMs = prev.latencyMs ?: next.latencyMs,
        downloadKbps = prev.downloadKbps ?: next.downloadKbps,
        failReason = next.failReason ?: prev.failReason,
        httpsTunnel = next.httpsTunnel && prev.httpsTunnel, // 任一层失败过置 false
    )

    // ==================================================================
    // L1：直接 TCP 握手（不通过 OkHttp，就是测代理 host:port 能不能连上）
    // ==================================================================
    suspend fun testL1(p: ProxyInfo, timeoutMs: Int): ValResult = withContext(Dispatchers.IO) {
        runCatching {
            val sock = Socket()
            val t0 = System.currentTimeMillis()
            sock.connect(InetSocketAddress(p.host, p.port), timeoutMs)
            val dt = System.currentTimeMillis() - t0
            runCatching { sock.close() }
            ValResult(ok = true, okL1 = true, latencyMs = dt, failReason = null)
        }.getOrElse { t ->
            ValResult(ok = false, okL1 = false, failReason = normalize(t))
        }
    }

    // ==================================================================
    // L2：通过代理访问 HTTP 明文 http://httpbin.org/ip
    //   必须 2xx；body JSON 有 origin 字段且 origin != p.host
    // ==================================================================
    suspend fun testL2(p: ProxyInfo, timeoutMs: Int): ValResult = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpFactory.clientWithProxy(baseClient, p, timeoutMs)
            val req = Request.Builder().url(HTTPBIN_IP)
                .header("User-Agent", UA)
                .header("Cache-Control", "no-cache")
                .build()
            val t0 = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - t0
                if (resp.code == 407) {
                    return@use ValResult(
                        ok = false, okL2 = false, latencyMs = latency,
                        failReason = "auth",
                    )
                }
                if (resp.code !in 200..299) {
                    return@use ValResult(
                        ok = false, okL2 = false, latencyMs = latency,
                        failReason = "code:${resp.code}",
                    )
                }
                val bodyStr = resp.body?.string().orEmpty()
                val origin = runCatching {
                    gson.fromJson(bodyStr, JsonObject::class.java)
                        ?.get("origin")?.asString
                }.getOrNull()
                if (origin.isNullOrBlank()) {
                    return@use ValResult(
                        ok = false, okL2 = false, latencyMs = latency,
                        failReason = "proxy_refused",
                    )
                }
                // 关键校验：origin (出口 IP) 不能等于代理 host，否则是本地回环假代理
                if (origin == p.host) {
                    return@use ValResult(
                        ok = false, okL2 = false, latencyMs = latency,
                        failReason = "proxy_refused",
                    )
                }
                ValResult(
                    ok = true, okL2 = true, latencyMs = latency,
                    failReason = null,
                )
            }
        }.getOrElse { t ->
            ValResult(ok = false, okL2 = false, failReason = normalize(t))
        }
    }

    // ==================================================================
    // L3：通过代理访问 https://www.google.com/
    //   200..399 且 body 前 16KB 含 "google"（忽略大小写） => okL3=true
    //   若 L3 失败但 L2 已 ok（可通过 prev 判断）=> httpsTunnel=false
    //
    //   注意：此处的 httpsTunnel 判定依赖"调用者在 merge 时保留 L2 结果"，
    //   testL3 本身若失败会把 failReason 保留；httpsTunnel 只在"本层失败"时暂置 false
    // ==================================================================
    suspend fun testL3(p: ProxyInfo, timeoutMs: Int): ValResult = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpFactory.clientWithProxy(baseClient, p, timeoutMs)
            val req = Request.Builder().url(GOOGLE_HOME)
                .header("User-Agent", UA)
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val t0 = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - t0
                if (resp.code == 407) {
                    return@use ValResult(
                        ok = false, okL3 = false, latencyMs = latency,
                        failReason = "auth", httpsTunnel = false,
                    )
                }
                val codeOk = resp.code in 200..399
                val bodyBytes = resp.body?.byteStream()?.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    var n: Int
                    while (run { n = ins.read(buf, total, buf.size - total); n != -1 } && total < buf.size) {
                        total += n
                    }
                    String(buf, 0, total, Charsets.UTF_8)
                }.orEmpty()
                val hasGoogle = bodyBytes.contains("google", ignoreCase = true)
                if (codeOk && hasGoogle) {
                    ValResult(
                        ok = true, okL3 = true, latencyMs = latency,
                        failReason = null, httpsTunnel = true,
                    )
                } else {
                    // HTTPS 访问失败：若 HTTP code 不对，归类 code:XXX；否则 TLS/reset/unknown
                    val reason = if (!codeOk) "code:${resp.code}" else "unknown"
                    ValResult(
                        ok = false, okL3 = false, latencyMs = latency,
                        failReason = reason, httpsTunnel = false,
                    )
                }
            }
        }.getOrElse { t ->
            ValResult(
                ok = false, okL3 = false, failReason = normalize(t),
                httpsTunnel = false, // 只要抛异常，就认为 HTTPS 隧道能力可疑
            )
        }
    }

    // ==================================================================
    // L4：并行跑 YouTube Mobile + Facebook 首页
    //   任一站点：code in 200..399 OR body bytes >= 10KB => ok
    // ==================================================================
    suspend fun testL4(p: ProxyInfo, timeoutMs: Int): ValResult = withContext(Dispatchers.IO) {
        // 并行：用 runBlocking 内 async（这里已在 Dispatchers.IO，直接用 async）
        val dYt = async(Dispatchers.IO) { checkSiteOnce(p, YOUTUBE_M, timeoutMs, minBytesOk = 10L * 1024) }
        val dFb = async(Dispatchers.IO) { checkSiteOnce(p, FACEBOOK_COM, timeoutMs, minBytesOk = 10L * 1024) }
        val yt = dYt.await()
        val fb = dFb.await()
        // merge
        val okAny = yt.ok || fb.ok
        val lat = yt.latencyMs ?: fb.latencyMs
        val kbps = yt.kbps ?: fb.kbps
        val reason = listOfNotNull(yt.failReason, fb.failReason).firstOrNull()
        ValResult(
            ok = okAny,
            okL1 = false, okL2 = false, okL3 = false,
            okYt = yt.ok, okFb = fb.ok,
            latencyMs = lat, downloadKbps = kbps,
            failReason = if (okAny) null else (reason ?: "unknown"),
            httpsTunnel = true, // L4 内不重置；由合并逻辑继承 L3 的结论
        )
    }

    /** 单个站点 HTTP(S) 检查：返回 (ok, latency, kbps, failReason) */
    private suspend fun checkSiteOnce(
        p: ProxyInfo, url: String, timeoutMs: Int, minBytesOk: Long,
    ): SiteCheck = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpFactory.clientWithProxy(baseClient, p, timeoutMs)
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val t0 = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - t0
                if (resp.code == 407) {
                    return@use SiteCheck(ok = false, latency, null, "auth")
                }
                val codeOk = resp.code in 200..399
                val body = resp.body
                var readBytes = 0L
                var kbps: Long? = null
                if (body != null) {
                    val t1 = System.currentTimeMillis()
                    readBytes = body.byteStream().use { ins ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        var n: Int
                        while (run { n = ins.read(buf); n != -1 }) {
                            total += n
                            if (total >= minBytesOk * 4) break // 读到足够的量就停
                        }
                        total
                    }
                    val elapsed = (System.currentTimeMillis() - t1).coerceAtLeast(1L)
                    val sec = elapsed / 1000.0
                    if (sec > 0) {
                        kbps = ((readBytes / 1024.0) / sec).toLong()
                    }
                }
                val sizeOk = readBytes >= minBytesOk
                if (codeOk || sizeOk) {
                    SiteCheck(ok = true, latency, kbps, null)
                } else {
                    SiteCheck(ok = false, latency, kbps, "code:${resp.code}")
                }
            }
        }.getOrElse { t ->
            SiteCheck(ok = false, null, null, normalize(t))
        }
    }

    private data class SiteCheck(
        val ok: Boolean,
        val latencyMs: Long?,
        val kbps: Long?,
        val failReason: String?,
    )

    // ==================================================================
    // 异常 → 规范化失败原因
    //   timeout / dns / TLS / auth / reset / proxy_refused / code:XXX / unknown
    // ==================================================================
    private fun normalize(t: Throwable): String {
        val root = generateSequence<Throwable>(t) { it.cause }.lastOrNull() ?: t
        return when {
            root is SocketTimeoutException -> "timeout"
            root is UnknownHostException -> "dns"
            root is SSLHandshakeException -> "TLS"
            root is ConnectException -> "reset"
            root is java.net.ProtocolException ||
                root.message?.contains("reset", ignoreCase = true) == true -> "reset"
            root.message?.contains("auth", ignoreCase = true) == true ||
                root.message?.contains("407") == true -> "auth"
            root.message?.contains("proxy", ignoreCase = true) == true &&
                root.message?.contains("refused", ignoreCase = true) == true -> "proxy_refused"
            else -> {
                val m = root.message
                if (m != null) {
                    // 尝试从消息提取 HTTP code:XXX
                    val codeRe = Regex("""HTTP\s+(\d{3})""").find(m)
                        ?: Regex("""code[:=]\s*(\d{3})""", RegexOption.IGNORE_CASE).find(m)
                    if (codeRe != null) return "code:${codeRe.groupValues[1]}"
                }
                "unknown"
            }
        }
    }
}
