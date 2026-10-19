package com.freeproxy.app.vpn

import android.content.Context
import android.util.Log
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * sing-box 真实内核封装（基于 v1.14.3 libbox CommandServer）。
 *
 * 加密节点（SS / VMess / VLESS / Trojan）无法用普通 HTTP/SOCKS 隧道直连，这里把它们交给
 * sing-box，以「本地 mixed 入站」（HTTP+SOCKS5，监听 127.0.0.1:2080）方式运行；既有
 * ProxyVpnService 的手写 TUN 与 HTTP CONNECT 隧道逻辑不变，只需把上游改为本机 sing-box。
 *
 * 本进程内不使用 sing-box 的 TUN 入站（无 tun inbound），因此 PlatformInterface.openTun
 * 不会被调用；平台回调以安全默认值实现，出站由系统默认网络发出。
 *
 * 生命周期由 VPN 服务持有：VPN 启动前 ensureRunning()，VPN 停止时 stop()。
 */
object SingBoxManager {

    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 2080

    private const val TAG = "SingBox"
    private const val OUTBOUND_TAG = "node"

    @Volatile private var server: CommandServer? = null
    @Volatile private var startedFingerprint: String? = null

    private val setupLock = Any()
    @Volatile private var setupDone = false

    /** 最近一次启动失败原因（供 UI / 日志展示） */
    @Volatile var lastError: String? = null
        private set

    fun isRunning(): Boolean = server != null

    /**
     * 确保 sing-box 已用指定加密节点启动。
     * 若当前运行节点与目标一致（指纹相同）则复用；否则先停旧服务再启动新节点。
     * 会等待本地入站监听就绪后再返回，保证后续 CONNECT 立即可用。
     */
    @Synchronized
    fun ensureRunning(proxy: ProxyInfo, appContext: Context): Boolean {
        val fp = proxyFingerprint(proxy)
        if (server != null && startedFingerprint == fp) return true

        return runCatching {
            stopInternal()
            ensureSetup(appContext)

            val config = buildConfig(proxy)
                ?: error("无法生成 sing-box 配置（缺少必要字段）")
            Libbox.checkConfig(config)

            val commandServer = Libbox.newCommandServer(RelayHandler(), RelayPlatform())
            commandServer.start()
            commandServer.startOrReloadService(config, OverrideOptions().apply { autoRedirect = false })

            server = commandServer
            startedFingerprint = fp

            if (!waitForInboundReady(5000)) {
                lastError = "sing-box 本地入站($LOCAL_HOST:$LOCAL_PORT)未就绪"
                stopInternal()
                return false
            }
            lastError = null
            true
        }.getOrElse { e ->
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "ensureRunning failed", e)
            stopInternal()
            false
        }
    }

    /** 停止 sing-box（VPN 断开时调用） */
    fun stop() {
        runCatching { stopInternal() }
        server = null
        startedFingerprint = null
    }

    private fun stopInternal() {
        val s = server ?: return
        server = null
        startedFingerprint = null
        runCatching { s.closeService() }
        runCatching { s.close() }
    }

    private fun proxyFingerprint(proxy: ProxyInfo): String =
        "${proxy.type.value}|${proxy.host}|${proxy.port}|${proxy.configJson ?: ""}"

    private fun ensureSetup(appContext: Context) {
        if (setupDone) return
        synchronized(setupLock) {
            if (setupDone) return
            val root = File(appContext.cacheDir, "sing-box")
            root.mkdirs()
            val working = File(root, "working").apply { mkdirs() }
            val temp = File(root, "temp").apply { mkdirs() }
            Libbox.setup(SetupOptions().apply {
                basePath = root.absolutePath
                workingPath = working.absolutePath
                tempPath = temp.absolutePath
                logMaxLines = 3000L
                crashReportSource = "Relay"
                appVersion = "2.0.0"
                appMarketingVersion = "2.0.0"
                fixAndroidStack = false
                debug = false
                oomKillerEnabled = false
                powerReportEnabled = false
            })
            setupDone = true
        }
    }

    private fun waitForInboundReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val probe = Socket()
                probe.connect(InetSocketAddress(LOCAL_HOST, LOCAL_PORT), 200)
                probe.close()
                return true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        return false
    }

    // ======================================================================
    // sing-box 配置生成（把 ShareLinkParser 的扁平 configJson 转为出站，v1.14 格式）
    // ======================================================================

    private fun buildConfig(proxy: ProxyInfo): String? {
        val outbound = buildOutbound(proxy) ?: return null
        val root = JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", true))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "local")
                        .put("listen", LOCAL_HOST)
                        .put("listen_port", LOCAL_PORT)
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put("route", JSONObject().put("final", OUTBOUND_TAG))
        return root.toString()
    }

    private fun buildOutbound(proxy: ProxyInfo): JSONObject? {
        val c = runCatching { proxy.configJson?.let { JSONObject(it) } }.getOrNull()
        return when (proxy.type) {
            ProxyType.VMESS -> buildVmess(c, proxy)
            ProxyType.TROJAN -> buildTrojan(c, proxy)
            ProxyType.VLESS -> buildVless(c, proxy)
            ProxyType.SHADOWSOCKS -> buildSs(c, proxy)
            else -> null
        }
    }

    private fun buildVmess(c: JSONObject?, proxy: ProxyInfo): JSONObject? {
        val server = proxy.host
        val uuid = c?.optString("uuid")?.takeIf { it.isNotBlank() } ?: proxy.username ?: return null
        val security = c?.optString("security")?.takeIf { it.isNotBlank() } ?: "auto"
        val alterId = c?.optInt("alterId", 0) ?: 0
        val tls = c?.optString("tls") ?: "none"
        val sni = c?.optString("sni")?.takeIf { it.isNotBlank() } ?: server
        val network = c?.optString("network") ?: "tcp"
        val host = c?.optString("host")
        val path = c?.optString("path")
        val alpn = c?.optString("alpn")
        val fp = c?.optString("fp")
        val allowInsecure = c?.optBoolean("allowInsecure", false) ?: false

        val o = JSONObject()
            .put("type", "vmess")
            .put("tag", OUTBOUND_TAG)
            .put("server", server)
            .put("server_port", proxy.port)
            .put("uuid", uuid)
            .put("security", security)
            .put("alter_id", alterId)
        val tlsObj = buildTls(tls == "tls", sni, allowInsecure, alpn, fp)
        if (tlsObj != null) o.put("tls", tlsObj)
        buildTransport(o, network, host, path)
        return o
    }

    private fun buildTrojan(c: JSONObject?, proxy: ProxyInfo): JSONObject? {
        val server = proxy.host
        val password = c?.optString("password")?.takeIf { it.isNotBlank() } ?: proxy.username ?: return null
        val tls = c?.optString("tls") ?: "tls"
        val sni = c?.optString("sni")?.takeIf { it.isNotBlank() } ?: server
        val network = c?.optString("network") ?: "tcp"
        val host = c?.optString("host")
        val path = c?.optString("path")
        val allowInsecure = c?.optBoolean("allowInsecure", false) ?: false
        val fp = c?.optString("fp")

        val o = JSONObject()
            .put("type", "trojan")
            .put("tag", OUTBOUND_TAG)
            .put("server", server)
            .put("server_port", proxy.port)
            .put("password", password)
        val tlsObj = buildTls(tls == "tls", sni, allowInsecure, null, fp)
        if (tlsObj != null) o.put("tls", tlsObj)
        buildTransport(o, network, host, path)
        return o
    }

    private fun buildVless(c: JSONObject?, proxy: ProxyInfo): JSONObject? {
        val server = proxy.host
        val uuid = c?.optString("uuid")?.takeIf { it.isNotBlank() } ?: proxy.username ?: return null
        val tls = c?.optString("tls") ?: "none"
        val sni = c?.optString("sni")?.takeIf { it.isNotBlank() } ?: server
        val network = c?.optString("network") ?: "tcp"
        val host = c?.optString("host")
        val path = c?.optString("path")
        val flow = c?.optString("flow")?.takeIf { it.isNotBlank() && network == "tcp" }
        val fp = c?.optString("fp")
        val allowInsecure = c?.optBoolean("allowInsecure", false) ?: false

        val o = JSONObject()
            .put("type", "vless")
            .put("tag", OUTBOUND_TAG)
            .put("server", server)
            .put("server_port", proxy.port)
            .put("uuid", uuid)
        if (flow != null) o.put("flow", flow)
        val tlsObj = buildTls(tls == "tls", sni, allowInsecure, null, fp)
        if (tlsObj != null) o.put("tls", tlsObj)
        buildTransport(o, network, host, path)
        return o
    }

    private fun buildSs(c: JSONObject?, proxy: ProxyInfo): JSONObject? {
        val method = c?.optString("method")?.takeIf { it.isNotBlank() } ?: proxy.username ?: return null
        val password = c?.optString("password")?.takeIf { it.isNotBlank() } ?: proxy.password ?: return null
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", OUTBOUND_TAG)
            .put("server", proxy.host)
            .put("server_port", proxy.port)
            .put("method", method)
            .put("password", password)
    }

    private fun buildTls(
        enabled: Boolean, sni: String, insecure: Boolean,
        alpn: String?, fp: String?,
    ): JSONObject? {
        if (!enabled) return null
        val t = JSONObject()
            .put("enabled", true)
            .put("server_name", sni)
            .put("insecure", insecure)
        if (!alpn.isNullOrBlank()) {
            t.put("alpn", JSONArray().put(alpn))
        }
        if (!fp.isNullOrBlank()) {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        return t
    }

    private fun buildTransport(o: JSONObject, network: String, host: String?, path: String?) {
        when (network) {
            "ws" -> {
                val t = JSONObject().put("type", "ws")
                if (!path.isNullOrBlank()) t.put("path", path)
                if (!host.isNullOrBlank()) t.put("headers", JSONObject().put("Host", host))
                o.put("transport", t)
            }
            "grpc" -> {
                val t = JSONObject().put("type", "grpc")
                if (!path.isNullOrBlank()) t.put("service_name", path)
                o.put("transport", t)
            }
            // tcp / http / h2 / quic 等：不附加 transport
        }
    }

    // ======================================================================
    // CommandServerHandler：核心回调，仅实现必要的安全默认值
    // ======================================================================

    private class RelayHandler : CommandServerHandler {
        override fun serviceStop() {
            runCatching { SingBoxManager.server?.closeService() }
        }

        override fun serviceReload() {
            // 单节点即时切换由 ensureRunning 重建服务，无需热重载
        }

        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().apply { available = false; enabled = false }

        override fun setSystemProxyEnabled(enabled: Boolean) {
            // 不使用系统代理
        }

        override fun triggerNativeCrash(): Nothing = throw UnsupportedOperationException("native crash disabled")

        override fun connectSSHAgent(): Int = throw UnsupportedOperationException("ssh agent unsupported")

        override fun writeDebugMessage(message: String) {
            Log.d(TAG, message)
        }
    }

    // ======================================================================
    // PlatformInterface：非 TUN 模式，多数方法返回安全默认值 / 抛不支持
    // ======================================================================

    private class RelayPlatform : PlatformInterface {
        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

        override fun autoDetectInterfaceControl(fd: Int) {
            // 出站套接字由系统默认网络发出，无需绑定指定接口
        }

        override fun openTun(options: TunOptions): Int =
            throw UnsupportedOperationException("tun inbound is not used (mixed inbound mode)")

        override fun useProcFS(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int, sourceAddress: String, sourcePort: Int,
            destinationAddress: String, destinationPort: Int,
        ): ConnectionOwner = throw UnsupportedOperationException("connection owner lookup unsupported")

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            // 本地 mixed 入站不需要默认接口监控
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        }

        override fun getInterfaces(): NetworkInterfaceIterator = EmptyInterfaceIterator

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun readWIFIState(): WIFIState? = null

        override fun clearDNSCache() {
        }

        override fun sendNotification(notification: Notification) {
            // 通知由 VPN 服务统一管理
        }

        override fun cancelNotification(identifier: String, typeID: Int) {
        }

        override fun startNeighborMonitor(listener: NeighborUpdateListener) =
            throw UnsupportedOperationException("neighbor monitor unsupported")

        override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
        }

        override fun registerMyInterface(name: String) {
        }

        override fun usePlatformShell(): Boolean = false

        override fun checkPlatformShell() =
            throw UnsupportedOperationException("shell unsupported")

        override fun openShellSession(
            user: PlatformUser?, command: String?, environ: io.nekohasekai.libbox.StringIterator?,
            term: String?, rows: Int, cols: Int,
        ): ShellSession = throw UnsupportedOperationException("shell session unsupported")

        override fun lookupUser(username: String?): PlatformUser =
            throw UnsupportedOperationException("lookup user unsupported")

        override fun lookupSFTPServer(): String =
            throw UnsupportedOperationException("sftp unsupported")

        override fun readSystemSSHHostKey(): String =
            throw UnsupportedOperationException("ssh host key unsupported")

        override fun tailscaleHostname(): String = ""

        override fun usePlatformBridge(): Boolean = false

        override fun createBridge(options: BridgeOptions?): BridgeSession =
            throw UnsupportedOperationException("bridge unsupported")
    }

    private object EmptyInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface = NetworkInterface()
    }
}
