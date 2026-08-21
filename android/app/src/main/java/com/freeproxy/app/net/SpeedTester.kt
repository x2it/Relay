package com.freeproxy.app.net

import com.freeproxy.app.data.model.ProxyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToLong

/**
 * 单个代理测试结果
 */
data class TestResult(
    val ok: Boolean,
    val latencyMs: Long? = null,
    val downloadKbps: Long? = null,
    val responseCode: Int? = null,
    val error: String? = null,
)

/**
 * 速度测试：延迟（TCP 握手）+ 真实 HTTP 请求（验证 + 下载速度）
 */
class SpeedTester(private val baseClient: OkHttpClient) {

    /**
     * 仅测 TCP 握手延迟（快速、省流量）
     */
    suspend fun testTcpLatency(p: ProxyInfo, timeoutMs: Int = 5000): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                val sock = Socket()
                val t0 = System.currentTimeMillis()
                sock.connect(InetSocketAddress(p.host, p.port), timeoutMs)
                val dt = System.currentTimeMillis() - t0
                runCatching { sock.close() }
                dt
            }.getOrNull()
        }

    /**
     * 通过代理发起 HTTP 请求，同时测握手+响应+下载速度
     * 下载速度仅在 response.body 存在且较小时计算（用一个可配置的小文件URL或GET target取前N字节）
     */
    suspend fun testHttp(
        p: ProxyInfo,
        targetUrl: String,
        timeoutMs: Int = 8000,
        downloadMaxBytes: Long = 64 * 1024, // 默认最多读 64KB 推算速度
    ): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpFactory.clientWithProxy(baseClient, p, timeoutMs)
            val req = Request.Builder().url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36 Relay/1.0")
                .header("Cache-Control", "no-cache")
                .build()
            val t0 = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - t0
                if (!resp.isSuccessful) {
                    return@use TestResult(false, latencyMs = latency, responseCode = resp.code,
                        error = "HTTP ${resp.code}")
                }
                val body = resp.body
                var kbps: Long? = null
                if (body != null) {
                    val contentLength = body.contentLength()
                    val t1 = System.currentTimeMillis()
                    val read = body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        var n: Int
                        while (run { n = input.read(buf); n != -1 } && total < downloadMaxBytes) {
                            total += n
                        }
                        total
                    }
                    val elapsed = (System.currentTimeMillis() - t1).coerceAtLeast(1L)
                    val sizeToUse = if (contentLength in 1..downloadMaxBytes) contentLength else read
                    // (size KB) / (elapsed s)
                    val seconds = elapsed / 1000.0
                    kbps = if (seconds > 0) ((sizeToUse / 1024.0) / seconds).roundToLong() else null
                }
                TestResult(ok = true, latencyMs = latency, downloadKbps = kbps,
                    responseCode = resp.code)
            }
        }.getOrElse { e ->
            TestResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
