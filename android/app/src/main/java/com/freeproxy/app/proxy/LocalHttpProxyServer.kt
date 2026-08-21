package com.freeproxy.app.proxy

import android.util.Base64
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.net.OkHttpFactory
import com.freeproxy.app.vpn.LogLevel
import com.freeproxy.app.vpn.VpnStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy as JvmProxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * 本机 127.0.0.1 HTTP 代理服务器。
 *
 * 功能：
 *   - 绑定 bindHost:port（端口在 startPort..endPort 区间扫描，找到第一个可用）
 *   - 仅回环访问限制（拒绝非 127.x.x.x 源地址）
 *   - 普通 HTTP：把绝对 URL 提取出来，用 OkHttp(挂上游代理) 请求，响应写回
 *   - HTTPS CONNECT：建立上游隧道后，双向 pump（浏览器 Socket 与上游 Socket 互相对流）
 *   - 上游代理支持 HTTP CONNECT / SOCKS5 两种（最小实现）
 */
class LocalHttpProxyServer(
    private val baseOkHttp: OkHttpClient,
    private var selected: ProxyInfo?,
    private val bindHost: String = "127.0.0.1",
    private val startPort: Int = 8080,
    private val endPort: Int = 8099,
) : Closeable {

    @Volatile
    var localPort: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var serverScope: CoroutineScope? = null

    fun updateSelected(p: ProxyInfo?) {
        this.selected = p
    }

    fun start(scope: CoroutineScope) {
        require(!isRunning) { "Already running" }
        // 创建独立 scope：SupervisorJob + IO，不受外部 scope 取消影响（但会在 close() 时取消）
        val svScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = svScope
        acceptJob = svScope.launch {
            try {
            runCatching {
                val ss = ServerSocket()
                ss.reuseAddress = true
                // 绑定端口：扫描 startPort..endPort
                val bindAddr = InetAddress.getByName(bindHost)
                check(bindAddr.isLoopbackAddress) { "bindHost must be loopback" }
                var bound = false
                for (port in startPort..endPort) {
                    try {
                        ss.bind(InetSocketAddress(bindAddr, port), 64)
                        localPort = port
                        bound = true
                        break
                    } catch (_: Throwable) { continue }
                }
                if (!bound) {
                    runCatching { ss.close() }
                    VpnStateManager.appendLog(
                        LogLevel.ERROR,
                        "LocalHttpProxy: no free port in $startPort..$endPort"
                    )
                    return@launch
                }
                serverSocket = ss
                isRunning = true
                VpnStateManager.appendLog(
                    LogLevel.INFO,
                    "LocalHttpProxy: listening on $bindHost:$localPort"
                )
                // accept 循环
                while (isActive && !ss.isClosed) {
                    runCatching {
                        val client = ss.accept()
                        // 回环限制：检查远端地址
                        val remote = client.inetAddress
                        if (remote == null || !remote.isLoopbackAddress) {
                            runCatching { client.close() }
                            VpnStateManager.appendLog(
                                LogLevel.WARN,
                                "LocalHttpProxy: reject non-loopback client ${remote?.hostAddress}"
                            )
                            return@runCatching
                        }
                        // 每个连接开 launch
                        launch(Dispatchers.IO) { handleClient(client) }
                    }
                }
            }.onFailure { t ->
                VpnStateManager.appendLog(
                    LogLevel.ERROR,
                    "LocalHttpProxy accept err: ${t.message}"
                )
            }
            } finally {
                runCatching { serverSocket?.close() }
                isRunning = false
                localPort = 0
                serverSocket = null
            }
        }
    }

    override fun close() {
        isRunning = false
        runCatching { serverSocket?.close() }
        runCatching { acceptJob?.cancel() }
        runCatching { serverScope?.cancel() }
        serverSocket = null
        acceptJob = null
        serverScope = null
        localPort = 0
    }

    // =====================================================================
    // 单连接处理：解析 HTTP 请求行 → 普通 HTTP / CONNECT 分支
    // =====================================================================

    private fun handleClient(client: Socket) {
        runCatching {
            client.soTimeout = 60_000
            val ins = client.getInputStream()
            val out = client.getOutputStream()
            // 读取首行 + headers（空行分隔 body）
            val headList = ArrayList<String>()
            val reader = BufferedReader(InputStreamReader(ins, StandardCharsets.ISO_8859_1))
            var first = true
            var method = ""
            var rawTarget = ""
            var version = "HTTP/1.1"
            var contentLength: Long = -1
            var isConnect = false
            var hasBody = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                if (first) {
                    first = false
                    val parts = line.split(' ')
                    if (parts.size < 3) {
                        writePlain(out, "HTTP/1.1 400 Bad Request\r\n\r\n")
                        return
                    }
                    method = parts[0].uppercase(Locale.ROOT)
                    rawTarget = parts[1]
                    version = parts[2]
                    isConnect = (method == "CONNECT")
                    headList.add(line)
                    continue
                }
                headList.add(line)
                // 解析关键 headers
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val v = line.substring(colon + 1).trim()
                    when (k) {
                        "content-length" -> {
                            contentLength = v.toLongOrNull() ?: -1
                            if (contentLength > 0) hasBody = true
                        }
                        "transfer-encoding" -> {
                            if (v.contains("chunked", true)) hasBody = true
                        }
                    }
                }
            }
            if (first) { // 空请求
                runCatching { client.close() }
                return
            }

            if (isConnect) {
                // CONNECT host:443 HTTP/1.1
                val hostPort = rawTarget
                handleConnect(client, hostPort)
                return@handleClient
            }

            // 普通 HTTP：rawTarget 是绝对 URL（如 http://x.com/y）
            handleHttp(client, out, method, rawTarget, version, headList, ins, contentLength, hasBody)
        }.onFailure { t ->
            VpnStateManager.appendLog(
                LogLevel.ERROR,
                "LocalHttpProxy handle err: ${t.message}"
            )
            runCatching { client.close() }
        }
    }

    // =====================================================================
    // 普通 HTTP：通过 OkHttp 转发
    // =====================================================================

    private fun handleHttp(
        client: Socket,
        clientOut: OutputStream,
        method: String,
        rawTarget: String,
        version: String,
        headLines: List<String>,
        clientIn: InputStream,
        contentLength: Long,
        hasBody: Boolean,
    ) {
        val url = run {
            // rawTarget 可能是绝对 URL 或 path（代理模式通常为绝对）
            if (rawTarget.startsWith("http://") || rawTarget.startsWith("https://")) {
                rawTarget
            } else {
                // 退回：尝试从 Host header 组合
                val hostLine = headLines.firstOrNull {
                    it.trim().lowercase(Locale.ROOT).startsWith("host:")
                }
                val hostVal = hostLine?.substringAfter(':')?.trim()
                if (hostVal != null) "http://$hostVal$rawTarget"
                else { runCatching { client.close() }; return }
            }
        }
        runCatching {
            val proxy = selected
            val client0: OkHttpClient = if (proxy != null) {
                OkHttpFactory.clientWithProxy(baseOkHttp, proxy, timeoutMs = 20_000)
            } else {
                // 无代理：直接本机 netClient（带短超时的副本）
                baseOkHttp.newBuilder()
                    .connectTimeout(20_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(20_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(20_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            }

            val reqBuilder = Request.Builder().url(url)
            // 重新构建 headers（跳过 Proxy-Connection 等）
            val hb = Headers.Builder()
            for (h in headLines.drop(1)) {
                val colon = h.indexOf(':')
                if (colon <= 0) continue
                val k = h.substring(0, colon).trim()
                val v = h.substring(colon + 1).trim()
                when (k.lowercase(Locale.ROOT)) {
                    "host", "connection", "proxy-connection",
                    "content-length", "transfer-encoding", "keep-alive" -> continue
                    else -> runCatching { hb.add(k, v) }
                }
            }
            reqBuilder.headers(hb.build())

            // Body
            val body: RequestBody? = if (hasBody && method !in listOf("GET", "HEAD", "DELETE", "OPTIONS")) {
                val bytes = readBody(clientIn, contentLength)
                if (bytes != null && bytes.isNotEmpty()) {
                    // 尝试从 Content-Type 推断 media type
                    val ctHeader = headLines.firstOrNull {
                        it.trim().lowercase(Locale.ROOT).startsWith("content-type:")
                    }?.substringAfter(':')?.trim()
                    val mt = (ctHeader ?: "").toMediaTypeOrNull()
                    bytes.toRequestBody(mt)
                } else null
            } else null
            reqBuilder.method(method, body)

            val resp: Response = client0.newCall(reqBuilder.build()).execute()
            // 写响应回客户端
            val sb = StringBuilder()
            sb.append(version).append(' ').append(resp.code).append(' ')
                .append(resp.message.ifBlank { "OK" }).append("\r\n")
            // 复制响应 headers（去掉 Transfer-Encoding: chunked，因为我们直接写 content-length）
            var respBody: ByteArray? = null
            resp.body?.use { b ->
                respBody = b.bytes()
            }
            val hopHeaders = setOf(
                "transfer-encoding", "connection", "keep-alive",
                "proxy-authenticate", "proxy-authorization", "te", "trailers", "upgrade"
            )
            resp.headers.forEach { (k, v) ->
                if (k.lowercase(Locale.ROOT) !in hopHeaders) {
                    sb.append(k).append(": ").append(v).append("\r\n")
                }
            }
            val bytes = respBody
            if (bytes != null) {
                sb.append("Content-Length: ").append(bytes.size).append("\r\n")
            }
            sb.append("\r\n")
            clientOut.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
            if (bytes != null && bytes.isNotEmpty()) clientOut.write(bytes)
            clientOut.flush()
            runCatching { client.close() }
        }.onFailure { t ->
            VpnStateManager.appendLog(
                LogLevel.ERROR,
                "LocalHttpProxy HTTP fwd err: ${t.message}"
            )
            runCatching {
                writePlain(
                    clientOut,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                )
            }
            runCatching { client.close() }
        }
    }

    // =====================================================================
    // HTTPS CONNECT：建立上游隧道后双向 pump
    // =====================================================================

    private fun handleConnect(client: Socket, hostPort: String) {
        val proxy = selected
        val out = client.getOutputStream()
        runCatching {
            val upstream: Socket = if (proxy != null) {
                connectUpstreamProxy(proxy, hostPort, timeoutMs = 10_000)
            } else {
                // 没选代理：直接直连
                val (host, port) = splitHostPort(hostPort)
                Socket(JvmProxy.NO_PROXY).apply {
                    connect(InetSocketAddress(host, port), 10_000)
                }
            }
            // 告诉客户端：200 建立成功
            writePlain(out, "HTTP/1.1 200 Connection established\r\n\r\n")
            // 双向 pump
            bidirectionalPump(client, upstream)
        }.onFailure { t ->
            VpnStateManager.appendLog(
                LogLevel.ERROR,
                "LocalHttpProxy CONNECT err $hostPort: ${t.message}"
            )
            runCatching {
                writePlain(
                    out,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                )
            }
            runCatching { client.close() }
        }
    }

    // =====================================================================
    // 上游代理握手：HTTP CONNECT / SOCKS5（从 ProxyVpnService 复制最小实现）
    // =====================================================================

    private fun connectUpstreamProxy(
        proxy: ProxyInfo, hostPort: String, timeoutMs: Int,
    ): Socket {
        val (host, port) = splitHostPort(hostPort)
        val proxyAddr = InetSocketAddress(proxy.host, proxy.port)
        return when (proxy.type) {
            ProxyType.SOCKS4, ProxyType.SOCKS5 -> {
                val sock = Socket(JvmProxy.NO_PROXY)
                sock.connect(proxyAddr, timeoutMs)
                sock.soTimeout = timeoutMs
                if (proxy.type == ProxyType.SOCKS4)
                    socks4Handshake(sock, host, port, proxy.username)
                else
                    socks5Handshake(sock, host, port, proxy.username, proxy.password)
                sock
            }
            else -> {
                val sock = Socket(JvmProxy.NO_PROXY)
                sock.connect(proxyAddr, timeoutMs)
                sock.soTimeout = timeoutMs
                httpConnectHandshake(sock, host, port, proxy.username, proxy.password)
                sock
            }
        }
    }

    private fun splitHostPort(hp: String): Pair<String, Int> {
        val idx = hp.lastIndexOf(':')
        if (idx < 0) return hp to 443
        val host = hp.substring(0, idx)
        val port = hp.substring(idx + 1).toIntOrNull() ?: 443
        return host to port
    }

    private fun httpConnectHandshake(
        s: Socket, host: String, port: Int, user: String?, pass: String?,
    ) {
        val req = buildString {
            append("CONNECT $host:$port HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("User-Agent: Relay-LocalProxy/1.0\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                val auth = Base64.encodeToString(
                    "$user:$pass".toByteArray(StandardCharsets.ISO_8859_1), Base64.NO_WRAP
                )
                append("Proxy-Authorization: Basic $auth\r\n")
            }
            append("\r\n")
        }
        val out = s.getOutputStream(); val ins = s.getInputStream()
        out.write(req.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
        val sb = StringBuilder()
        val buf = ByteArray(1)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) throw IllegalStateException("HTTP CONNECT: EOF")
            sb.append(String(buf, 0, n, StandardCharsets.ISO_8859_1))
            if (sb.endsWith("\r\n\r\n")) break
        }
        val firstLine = sb.lines().firstOrNull() ?: ""
        val code = firstLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IllegalStateException("HTTP CONNECT: bad response $firstLine")
        if (code !in 200..299) throw IllegalStateException("HTTP CONNECT: $firstLine")
    }

    private fun socks5Handshake(
        s: Socket, host: String, port: Int, user: String?, pass: String?,
    ) {
        val out = s.getOutputStream(); val ins = s.getInputStream()
        val authNeeded = !user.isNullOrBlank()
        val methods = if (authNeeded) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00)
        out.write(byteArrayOf(0x05, methods.size.toByte()) + methods)
        out.flush()
        val h = ByteArray(2); val n = ins.read(h)
        if (n < 2 || h[0].toInt() != 0x05) throw IllegalStateException("SOCKS5: bad greeting")
        if (h[1].toInt() == 0x02) {
            val u = user!!.toByteArray(); val p = pass!!.toByteArray()
            out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            out.flush()
            val ah = ByteArray(2); ins.readFullyCompat(ah)
            if (ah[0].toInt() != 0x01 || ah[1].toInt() != 0x00)
                throw IllegalStateException("SOCKS5: auth failed")
        } else if (h[1].toInt() != 0x00) {
            throw IllegalStateException("SOCKS5: unsupported auth ${h[1]}")
        }
        val hb = host.toByteArray(StandardCharsets.ISO_8859_1)
        val addrType: Byte = 0x03
        val req = ByteArray(7 + hb.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = addrType
        req[4] = hb.size.toByte()
        System.arraycopy(hb, 0, req, 5, hb.size)
        req[5 + hb.size] = (port shr 8 and 0xFF).toByte()
        req[6 + hb.size] = (port and 0xFF).toByte()
        out.write(req); out.flush()
        val rh = ByteArray(4); if (ins.read(rh) < 4) throw IllegalStateException("SOCKS5: EOF")
        if (rh[1].toInt() != 0x00) throw IllegalStateException("SOCKS5: connect code=${rh[1]}")
        when (rh[3].toInt()) {
            0x01 -> ins.skipExact(4)
            0x04 -> ins.skipExact(16)
            0x03 -> {
                val lb = ByteArray(1); ins.readFullyCompat(lb)
                ins.skipExact(lb[0].toInt() and 0xFF)
            }
        }
        ins.skipExact(2)
    }

    private fun socks4Handshake(
        s: Socket, host: String, port: Int, user: String?,
    ) {
        val out = s.getOutputStream(); val ins = s.getInputStream()
        val userBytes = (user ?: "").toByteArray(StandardCharsets.ISO_8859_1)
        val hostBytes = host.toByteArray(StandardCharsets.ISO_8859_1)
        val isIp = host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val ip = if (isIp) {
            host.split(".").map { it.toInt().toByte() }.toByteArray()
        } else ByteArray(4) { 0 }.also { it[3] = 0x01 }
        val needDomain = !isIp
        val total = 8 + userBytes.size + 1 + if (needDomain) (hostBytes.size + 1) else 0
        val req = ByteArray(total)
        req[0] = 0x04; req[1] = 0x01
        req[2] = (port shr 8 and 0xFF).toByte(); req[3] = (port and 0xFF).toByte()
        System.arraycopy(ip, 0, req, 4, 4)
        System.arraycopy(userBytes, 0, req, 8, userBytes.size)
        var off = 8 + userBytes.size
        req[off++] = 0
        if (needDomain) {
            System.arraycopy(hostBytes, 0, req, off, hostBytes.size)
            off += hostBytes.size
            req[off] = 0
        }
        out.write(req); out.flush()
        val resp = ByteArray(8); ins.readFullyCompat(resp)
        if (resp[0].toInt() != 0x00) throw IllegalStateException("SOCKS4: bad reply VN=${resp[0]}")
        if (resp[1].toInt() != 0x5A) throw IllegalStateException("SOCKS4: rejected status=${resp[1]}")
    }

    // =====================================================================
    // 双向 pump：两个循环读 4KB 写对端，任一端 EOF 关双端
    // =====================================================================

    private fun bidirectionalPump(a: Socket, b: Socket) {
        val aIn = a.getInputStream(); val aOut = a.getOutputStream()
        val bIn = b.getInputStream(); val bOut = b.getOutputStream()
        val ex = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val t1 = Thread {
            try { pumpStream(aIn, bOut) } catch (t: Throwable) { ex.compareAndSet(null, t) }
            finally { runCatching { a.shutdownInput() }; runCatching { b.shutdownOutput() } }
        }.apply { isDaemon = true; start() }
        val t2 = Thread {
            try { pumpStream(bIn, aOut) } catch (t: Throwable) { ex.compareAndSet(null, t) }
            finally { runCatching { b.shutdownInput() }; runCatching { a.shutdownOutput() } }
        }.apply { isDaemon = true; start() }
        t1.join(30_000)
        t2.join(30_000)
        runCatching { a.close() }
        runCatching { b.close() }
    }

    private fun pumpStream(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(4096)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            if (n > 0) {
                dst.write(buf, 0, n)
                dst.flush()
            }
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun writePlain(out: OutputStream, s: String) {
        out.write(s.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    private fun readBody(ins: InputStream, contentLength: Long): ByteArray? {
        if (contentLength > 0) {
            if (contentLength > 50 * 1024 * 1024) return null // 避免超大
            val baos = ByteArrayOutputStream(contentLength.toInt())
            var rem = contentLength
            val buf = ByteArray(4096)
            while (rem > 0) {
                val n = ins.read(buf, 0, minOf(buf.size, rem.toInt()))
                if (n < 0) break
                if (n > 0) { baos.write(buf, 0, n); rem -= n }
            }
            return baos.toByteArray()
        }
        // chunked 或未知：简单的读至 EOF（可能阻塞，设置了 soTimeout 60s 会抛超时）
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            if (n == 0) continue
            baos.write(buf, 0, n)
            if (baos.size() > 50 * 1024 * 1024) break
        }
        return baos.toByteArray()
    }

    private fun InputStream.readFullyCompat(b: ByteArray) {
        var off = 0; val len = b.size
        while (off < len) {
            val n = read(b, off, len - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun InputStream.skipExact(n: Int) {
        var remain = n
        while (remain > 0) {
            val skipped = skip(remain.toLong()).toInt()
            if (skipped <= 0) {
                val trash = ByteArray(remain)
                val r = read(trash)
                if (r < 0) throw java.io.EOFException()
                remain -= r
            } else {
                remain -= skipped
            }
        }
    }
}
