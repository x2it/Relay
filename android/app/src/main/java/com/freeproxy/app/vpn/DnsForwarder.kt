package com.freeproxy.app.vpn

import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.net.OkHttpFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 最小化 DNS 二进制解析 + DoH / TCP DNS 兜底。
 * 不引入 dnsjava 等三方依赖。
 */
internal object DnsForwarder {

    data class Answer(val rcode: Int, val answers: List<DnsRR>)
    data class DnsRR(val name: String, val type: Int, val ttl: Long, val data: ByteArray)

    private val DNS_MESSAGE_TYPE = "application/dns-message".toMediaType()
    private const val DOH_URL = "https://dns.google/dns-query"

    /** 解析 QNAME：一串长度前缀标签，以 0x00 结尾。返回带尾点的域名如 "google.com." */
    private fun readQName(bytes: ByteArray, offset: Int): Pair<String, Int> {
        var pos = offset
        val labels = mutableListOf<String>()
        // 最大跳转数防止指针循环
        var jumps = 0
        var returnedPos = -1
        while (jumps < 10) {
            if (pos >= bytes.size) break
            val len = bytes[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                // compression pointer
                if (pos + 1 >= bytes.size) break
                val ptr = ((len and 0x3F) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                if (returnedPos < 0) returnedPos = pos + 2
                pos = ptr
                jumps++
                continue
            }
            if (len > 63 || pos + 1 + len > bytes.size) break
            labels.add(String(bytes, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        val finalPos = if (returnedPos >= 0) returnedPos else pos
        return (labels.joinToString(".") + ".") to finalPos
    }

    /**
     * 解析一个原始 UDP DNS 查询字节，拿到 QNAME + QTYPE。
     * 返回 qname("google.com.") + qtype（A=1, AAAA=28, ...）
     */
    fun parseQuery(bytes: ByteArray): Pair<String, Int>? {
        try {
            if (bytes.size < 12) return null
            val buf = ByteBuffer.wrap(bytes)
            /* val id = */ buf.short
            val flags = buf.short.toInt() and 0xFFFF
            val qr = (flags shr 15) and 1
            if (qr != 0) return null // 不是查询
            val qdcount = buf.short.toInt() and 0xFFFF
            /* val ancount = */ buf.short
            /* val nscount = */ buf.short
            /* val arcount = */ buf.short
            if (qdcount <= 0) return null

            var pos = 12
            val (qname, afterQname) = readQName(bytes, pos)
            pos = afterQname
            if (pos + 4 > bytes.size) return null
            val qtype = (bytes[pos].toInt() and 0xFF shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            return qname to qtype
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * 使用 DoH（POST）通过 OkHttp + 当前代理解析 DNS；失败则回退 TCP DNS 到 8.8.8.8:53。
     */
    suspend fun resolve(
        baseOkHttpClient: okhttp3.OkHttpClient,
        selectedProxy: ProxyInfo?,
        queryBytes: ByteArray,
        timeoutMs: Int = 3000,
    ): ByteArray? = withContext(Dispatchers.IO) {
        // 1) DoH 优先
        val doh = runCatching {
            val client = if (selectedProxy != null)
                OkHttpFactory.clientWithProxy(baseOkHttpClient, selectedProxy, timeoutMs) { sock ->
                    ProxyVpnService.instance?.protect(sock) ?: false
                }
            else
                baseOkHttpClient.newBuilder()
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()

            val body = queryBytes.toRequestBody(DNS_MESSAGE_TYPE)
            val req = Request.Builder().url(DOH_URL).post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()
            }
        }.getOrNull()
        if (doh != null && doh.size >= 12) return@withContext doh

        // 2) 回退：TCP DNS 直连 8.8.8.8:53（加 2 字节长度前缀）
        runCatching {
            val sock = Socket()
            ProxyVpnService.instance?.protect(sock)
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress("8.8.8.8", 53), timeoutMs)
            sock.use { s ->
                val dout = DataOutputStream(s.getOutputStream())
                dout.writeShort(queryBytes.size)
                dout.write(queryBytes)
                dout.flush()
                val din = DataInputStream(s.getInputStream())
                val respLen = din.readUnsignedShort()
                if (respLen <= 0 || respLen > 65535) return@use null
                val resp = ByteArray(respLen)
                din.readFully(resp)
                resp
            }
        }.getOrNull()
    }
}
