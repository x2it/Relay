package com.freeproxy.app.net

import android.content.Context
import android.util.Base64
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy as JvmProxy
import java.net.Socket
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

object OkHttpFactory {

    fun create(ctx: Context): OkHttpClient {
        val cacheDir = File(ctx.cacheDir, "http_cache").apply { mkdirs() }
        val cache = Cache(cacheDir, 10L * 1024 * 1024) // 10MB
        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1)) // 代理大多只支持 HTTP/1.1
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 根据 ProxyInfo 创建一个配置了该代理的 OkHttp 客户端副本。
     *
     * - HTTP/HTTPS 代理：用 JvmProxy(Type.HTTP) + 拦截器加 Proxy-Authorization + proxyAuthenticator 处理 CONNECT
     * - SOCKS4/5 代理：用自定义 SocketFactory（不走全局 Authenticator），在 connect 时自行完成 SOCKS 握手
     * - 四个超时（connect/read/write/call）统一按 timeoutMs 设置
     */
    fun clientWithProxy(base: OkHttpClient, info: ProxyInfo, timeoutMs: Int = 10000, socketProtector: ((Socket) -> Boolean)? = null): OkHttpClient {
        val b = base.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)

        when (info.type) {
            ProxyType.SOCKS4, ProxyType.SOCKS5 -> {
                b.proxy(JvmProxy.NO_PROXY)
                b.socketFactory(SocksProxySocketFactory(info, timeoutMs, socketProtector))
            }
            else -> {
                val proxy = JvmProxy(JvmProxy.Type.HTTP, InetSocketAddress(info.host, info.port))
                b.proxy(proxy)
                if (socketProtector != null) {
                    b.socketFactory(ProtectedSocketFactory(socketProtector))
                }
                // 账密：拦截器 + proxyAuthenticator 双通道（前者覆盖普通请求，后者覆盖 CONNECT 隧道）
                val user = info.username
                val pass = info.password
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    val credential = CredentialsCompat.basic(user, pass)
                    // 1) 拦截器：对每个请求自动加 Proxy-Authorization
                    b.addInterceptor(ProxyAuthInterceptor(credential))
                    // 2) proxyAuthenticator：应对 407 挑战（尤其是 HTTPS CONNECT 时）
                    b.proxyAuthenticator(object : Authenticator {
                        override fun authenticate(route: Route?, response: Response): Request? {
                            if (response.request.header("Proxy-Authorization") != null) return null
                            return response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    })
                }
            }
        }
        return b.build()
    }

    /**
     * 为单次请求创建带独立 callTimeout 的客户端副本（用于按源设置超时）
     */
    fun perCall(client: OkHttpClient, timeoutMs: Int): OkHttpClient =
        client.newBuilder().callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()

    // ======================================================================
    // 内部：兼容 Base64 编码 helper
    // ======================================================================
    private object CredentialsCompat {
        fun basic(user: String, pass: String): String {
            val raw = "$user:$pass".toByteArray(StandardCharsets.ISO_8859_1)
            val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
            return "Basic $b64"
        }
    }

    // ======================================================================
    // 内部：HTTP 代理请求级拦截器（加 Proxy-Authorization）
    // ======================================================================
    private class ProxyAuthInterceptor(private val credential: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val augmented = orig.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
            return chain.proceed(augmented)
        }
    }

    // ======================================================================
    // 内部：SOCKS4/SOCKS5 自定义 SocketFactory
    // OkHttp 看到 NO_PROXY + socketFactory 时会用 sf.createSocket() 创建 Socket
    // 再调用 socket.connect(targetAddr, timeout)；我们在 connect() 内完成握手。
    // ======================================================================
    private class SocksProxySocketFactory(
        private val proxy: ProxyInfo,
        private val timeoutMs: Int,
        private val socketProtector: ((Socket) -> Boolean)? = null,
    ) : SocketFactory() {

        override fun createSocket(): Socket = SocksProxySocket(proxy, timeoutMs, socketProtector)

        override fun createSocket(host: String, port: Int): Socket =
            SocksProxySocket(proxy, timeoutMs, socketProtector).apply { connect(InetSocketAddress(host, port), timeoutMs) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket(host, port)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            SocksProxySocket(proxy, timeoutMs, socketProtector).apply { connect(InetSocketAddress(host, port), timeoutMs) }

        override fun createSocket(address: InetAddress, port: Int, localAddr: InetAddress?, localPort: Int): Socket =
            createSocket(address, port)
    }

    private class ProtectedSocketFactory(private val protector: (Socket) -> Boolean) : SocketFactory() {
        override fun createSocket(): Socket = Socket().apply { protector(this) }
        override fun createSocket(host: String, port: Int): Socket = Socket().apply { protector(this); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int): Socket = Socket().apply { protector(this); connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddr: InetAddress?, localPort: Int): Socket = createSocket(address, port)
    }

    // ======================================================================
    // 内部：支持 SOCKS4/5 握手的代理 Socket（全部 API 委托给真实底层 Socket）
    // OkHttp 会调用 socket.connect(target, timeout)，我们在这里：
    //   1) 真实 Socket -> connect(proxyAddr, timeout)
    //   2) 在该 Socket 上完成 socks4/socks5 握手，目标 = target
    //   3) 之后所有 IO 直接走真实 Socket 的流
    // ======================================================================
    private class SocksProxySocket(
        private val proxyInfo: ProxyInfo,
        private val timeoutMs: Int,
        private val socketProtector: ((Socket) -> Boolean)? = null,
    ) : Socket() {
        // 真正连接代理服务器的底层 Socket（延迟到 connect() 时创建）
        private var real: Socket? = null

        private fun requireReal(): Socket = real ?: throw IllegalStateException("Socket not connected")

        // ---- 覆盖 connect：先连代理 + 握手 ----
        override fun connect(endpoint: SocketAddress?, timeoutMs_: Int) {
            val target = endpoint as? InetSocketAddress
                ?: throw IllegalArgumentException("Unsupported endpoint: $endpoint")
            val targetHost = target.hostString
                ?: throw IllegalArgumentException("Unresolved endpoint: $endpoint")
            val targetPort = target.port
            // 注意：优先使用 OkHttp 传来的 timeoutMs_（若>0），否则退回工厂级 timeoutMs
            val effectiveTimeout = if (timeoutMs_ > 0) timeoutMs_ else this.timeoutMs

            val sock = Socket(JvmProxy.NO_PROXY)
            socketProtector?.invoke(sock)
            sock.soTimeout = effectiveTimeout
            // 1) TCP 连接代理服务器
            val proxyAddr = InetSocketAddress(proxyInfo.host, proxyInfo.port)
            sock.connect(proxyAddr, effectiveTimeout)
            // 2) SOCKS 握手：连接到真正的 target
            when (proxyInfo.type) {
                ProxyType.SOCKS5 -> internalSocks5Connect(
                    sock, proxyInfo, targetHost, targetPort,
                    proxyInfo.username, proxyInfo.password,
                )
                ProxyType.SOCKS4 -> internalSocks4Connect(
                    sock, proxyInfo, targetHost, targetPort,
                    proxyInfo.username,
                )
                else -> throw IllegalStateException("Not SOCKS type: ${proxyInfo.type}")
            }
            this.real = sock
        }

        // ---- 委托所有其他 Socket API ----
        override fun bind(bindpoint: SocketAddress?) = requireReal().bind(bindpoint)
        override fun getInetAddress(): InetAddress = requireReal().inetAddress
        override fun getLocalAddress(): InetAddress = requireReal().localAddress
        override fun getPort(): Int = requireReal().port
        override fun getLocalPort(): Int = requireReal().localPort
        override fun getRemoteSocketAddress(): SocketAddress? = requireReal().remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = requireReal().localSocketAddress
        override fun getChannel() = requireReal().channel
        override fun isConnected(): Boolean = real?.isConnected == true
        override fun isBound(): Boolean = real?.isBound == true
        override fun isClosed(): Boolean = real?.isClosed == true // 未连接时返回 false（没被显式关闭）
        override fun close() { runCatching { real?.close() } }
        override fun shutdownInput() = requireReal().shutdownInput()
        override fun shutdownOutput() = requireReal().shutdownOutput()
        override fun isInputShutdown(): Boolean = requireReal().isInputShutdown
        override fun isOutputShutdown(): Boolean = requireReal().isOutputShutdown
        override fun getInputStream(): InputStream = requireReal().getInputStream()
        override fun getOutputStream(): OutputStream = requireReal().getOutputStream()
        override fun setTcpNoDelay(on: Boolean) = requireReal().setTcpNoDelay(on)
        override fun getTcpNoDelay(): Boolean = requireReal().tcpNoDelay
        override fun setSoLinger(on: Boolean, linger: Int) = requireReal().setSoLinger(on, linger)
        override fun getSoLinger(): Int = requireReal().soLinger
        override fun sendUrgentData(data: Int) = requireReal().sendUrgentData(data)
        override fun setOOBInline(on: Boolean) = requireReal().setOOBInline(on)
        override fun getOOBInline(): Boolean = requireReal().oobInline
        override fun setSoTimeout(timeout: Int) { if (real != null) requireReal().soTimeout = timeout }
        override fun getSoTimeout(): Int = real?.soTimeout ?: 0
        override fun setSendBufferSize(size: Int) = requireReal().setSendBufferSize(size)
        override fun getSendBufferSize(): Int = requireReal().sendBufferSize
        override fun setReceiveBufferSize(size: Int) = requireReal().setReceiveBufferSize(size)
        override fun getReceiveBufferSize(): Int = requireReal().receiveBufferSize
        override fun setKeepAlive(on: Boolean) = requireReal().setKeepAlive(on)
        override fun getKeepAlive(): Boolean = requireReal().keepAlive
        override fun setTrafficClass(tc: Int) = requireReal().setTrafficClass(tc)
        override fun getTrafficClass(): Int = requireReal().trafficClass
        override fun setReuseAddress(on: Boolean) = requireReal().setReuseAddress(on)
        override fun getReuseAddress(): Boolean = requireReal().reuseAddress
        override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
            requireReal().setPerformancePreferences(connectionTime, latency, bandwidth)
    }

    // ======================================================================
    // 内部 helper：纯 Java.net.Socket 级 SOCKS5 握手
    // 复制改造自 ProxyVpnService.socks5Handshake，避免循环依赖 vpn 包
    // ======================================================================
    private fun internalSocks5Connect(
        s: Socket, info: ProxyInfo, host: String, port: Int, user: String?, pass: String?,
    ) {
        val out = s.getOutputStream(); val ins = s.getInputStream()
        val authNeeded = !user.isNullOrBlank()
        // Client greeting: VER=5, NMETHODS, METHODS(0x00=NO AUTH, 0x02=USERNAME/PASSWORD)
        val methods = if (authNeeded) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00)
        out.write(byteArrayOf(0x05, methods.size.toByte()) + methods)
        out.flush()
        val h = ByteArray(2); val n = ins.read(h)
        if (n < 2 || h[0].toInt() != 0x05) throw IllegalStateException("SOCKS5: bad greeting reply")
        if (h[1].toInt() == 0x02) {
            // RFC 1929 Username/Password auth
            val u = user!!.toByteArray(); val p = pass!!.toByteArray()
            if (u.size > 255 || p.size > 255) throw IllegalStateException("SOCKS5: user/pass too long")
            out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            out.flush()
            val ah = ByteArray(2); ins.readFullyCompat(ah)
            if (ah[0].toInt() != 0x01 || ah[1].toInt() != 0x00)
                throw IllegalStateException("SOCKS5: auth failed status=${ah[1]}")
        } else if (h[1].toInt() != 0x00) {
            throw IllegalStateException("SOCKS5: unsupported auth method=${h[1]}")
        }
        // CONNECT request: VER=5, CMD=1(CONNECT), RSV=0, ATYP=0x03(domain)
        val hb = host.toByteArray(StandardCharsets.ISO_8859_1)
        if (hb.size > 255) throw IllegalStateException("SOCKS5: hostname too long")
        val req = ByteArray(7 + hb.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hb.size.toByte()
        System.arraycopy(hb, 0, req, 5, hb.size)
        req[5 + hb.size] = (port shr 8 and 0xFF).toByte()
        req[6 + hb.size] = (port and 0xFF).toByte()
        out.write(req); out.flush()
        // Read reply: VER, REP, RSV, ATYP
        val rh = ByteArray(4); ins.readFullyCompat(rh)
        if (rh[0].toInt() != 0x05) throw IllegalStateException("SOCKS5: bad connect reply ver")
        if (rh[1].toInt() != 0x00) throw IllegalStateException("SOCKS5: connect rejected code=${rh[1]}")
        // Skip BND.ADDR + BND.PORT
        when (rh[3].toInt()) {
            0x01 -> ins.skipExact(4)          // IPv4: 4 bytes
            0x04 -> ins.skipExact(16)         // IPv6: 16 bytes
            0x03 -> {
                val lb = ByteArray(1); ins.readFullyCompat(lb)
                ins.skipExact(lb[0].toInt() and 0xFF)
            }
            else -> throw IllegalStateException("SOCKS5: unknown atyp=${rh[3]}")
        }
        ins.skipExact(2) // BND.PORT
    }

    // ======================================================================
    // 内部 helper：纯 Java.net.Socket 级 SOCKS4(a) 握手
    // 复制改造自 ProxyVpnService.socks4Handshake，避免循环依赖 vpn 包
    // ======================================================================
    private fun internalSocks4Connect(
        s: Socket, info: ProxyInfo, host: String, port: Int, user: String?,
    ) {
        val out = s.getOutputStream(); val ins = s.getInputStream()
        val userBytes = (user ?: "").toByteArray(StandardCharsets.ISO_8859_1)
        val hostBytes = host.toByteArray(StandardCharsets.ISO_8859_1)
        val isIp = host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val ip = if (isIp) {
            host.split(".").map { it.toInt().toByte() }.toByteArray()
        } else {
            // SOCKS4A：IP 字段填 0.0.0.x (x!=0) 表示用远端 DNS
            ByteArray(4) { 0 }.also { it[3] = 0x01 }
        }
        // Packet: VN=4, CD=1(CONNECT), DSTPORT(2), DSTIP(4), USERID(\0), [DSTADDR(\0) if SOCKS4A]
        val needDomain = !isIp
        val total = 8 + userBytes.size + 1 + if (needDomain) (hostBytes.size + 1) else 0
        val req = ByteArray(total)
        req[0] = 0x04; req[1] = 0x01
        req[2] = (port shr 8 and 0xFF).toByte()
        req[3] = (port and 0xFF).toByte()
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
        // Reply: VN(0), CD, DSTPORT(2), DSTIP(4) = 8 bytes
        val resp = ByteArray(8); ins.readFullyCompat(resp)
        if (resp[0].toInt() != 0x00) throw IllegalStateException("SOCKS4: bad reply VN=${resp[0]}")
        if (resp[1].toInt() != 0x5A) throw IllegalStateException("SOCKS4: rejected status=${resp[1]}")
    }

    // ======================================================================
    // InputStream 小工具
    // ======================================================================
    private fun InputStream.readFullyCompat(b: ByteArray) {
        var off = 0; val len = b.size
        while (off < len) {
            val n = read(b, off, len - off)
            if (n < 0) throw java.io.EOFException("Unexpected EOF in handshake")
            off += n
        }
    }

    private fun InputStream.skipExact(n: Int) {
        var remain = n
        while (remain > 0) {
            val skipped = skip(remain.toLong()).toInt()
            if (skipped <= 0) {
                // skip 返回 0 时退化为 read 丢弃
                val trash = ByteArray(remain)
                val r = read(trash)
                if (r < 0) throw java.io.EOFException("skipExact EOF")
                remain -= r
            } else {
                remain -= skipped
            }
        }
    }
}
