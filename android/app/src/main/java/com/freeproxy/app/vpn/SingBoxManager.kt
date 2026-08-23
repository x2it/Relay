package com.freeproxy.app.vpn

import android.content.Context
import android.util.Log
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * sing-box 核心封装。
 *
 * 加密节点（VMess/Trojan/VLESS/Shadowsocks）无法用普通 HTTP/SOCKS 隧道直连，
 * 这里把它们交给 sing-box 以「本地 mixed 入站」（HTTP+SOCKS5，监听 127.0.0.1:2080）方式运行，
 * 既有 ProxyVpnService 的 HTTP CONNECT 隧道逻辑不变，只需把上游改为本机 sing-box 即可。
 *
 * 生命周期由 VPN 服务持有：VPN 启动前 ensureRunning()，VPN 停止时 stop()。
 */
object SingBoxManager {

    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 2080

    private const val TAG = "SingBox"
    private const val OUTBOUND_TAG = "node"

    @Volatile private var service: BoxService? = null
    @Volatile private var startedFingerprint: String? = null

    private val setupLock = Any()
    @Volatile private var setupDone = false

    /** 最近一次启动失败原因（供 UI / 日志展示） */
    @Volatile var lastError: String? = null
        private set

    fun isRunning(): Boolean = service != null

    /**
     * 确保 sing-box 已用指定加密节点启动。
     * 若当前运行节点与目标一致（指纹相同）则复用；否则先停旧服务再启动新节点。
     * 会等待本地入站监听就绪后再返回，保证后续 CONNECT 立即可用。
     */
    @Synchronized
    fun ensureRunning(proxy: ProxyInfo, appContext: Context): Boolean {
        val fp = proxyFingerprint(proxy)
        if (service != null && startedFingerprint == fp) return true

        return runCatching {
            stopInternal()
            Libbox.touch()
            ensureSetup(appContext)

            val config = buildConfig(proxy)
                ?: error("无法生成 sing-box 配置（缺少必要字段）")
            Libbox.checkConfig(config)

            val newService = Libbox.newService(config, RelayPlatform())
            // start() 会阻塞直到服务停止，必须在独立线程运行
            Thread {
                runCatching { newService.start() }
            }.apply { isDaemon = true; start() }

            service = newService
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
        service = null
        startedFingerprint = null
    }

    private fun stopInternal() {
        val s = service ?: return
        service = null
        startedFingerprint = null
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
    // sing-box 配置生成（把 ShareLinkParser 的扁平 configJson 转为出站）
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
        val uuid = c?.optString("uuid") ?: proxy.username ?: return null
        val security = c?.optString("security")?.takeIf { it.isNotBlank() } ?: "auto"
        val alterId = c?.optInt("alterId", 0) ?: 0
        val tls = c?.optString("tls") ?: "none"
        val sni = c?.optString("sni") ?: server
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
        val password = c?.optString("password") ?: proxy.username ?: return null
        val tls = c?.optString("tls") ?: "tls"
        val sni = c?.optString("sni") ?: server
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
        val uuid = c?.optString("uuid") ?: proxy.username ?: return null
        val tls = c?.optString("tls") ?: "none"
        val sni = c?.optString("sni") ?: server
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
        val method = c?.optString("method") ?: proxy.username ?: return null
        val password = c?.optString("password") ?: proxy.password ?: return null
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
    // PlatformInterface：非 TUN 模式，多数方法空实现 / 返回安全默认值
    // ======================================================================

    private class RelayPlatform : PlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {}
        override fun clearDNSCache() {}
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
        override fun findConnectionOwner(
            ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
            destinationAddress: String?, destinationPort: Int,
        ): Int = -1
        override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetIterator
        override fun includeAllNetworks(): Boolean = false
        override fun openTun(options: TunOptions?): Int = -1
        override fun packageNameByUid(uid: Int): String? = null
        override fun readWIFIState(): WIFIState = WIFIState("", "")
        override fun sendNotification(notification: Notification?) {}
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
        override fun uidByPackageName(packageName: String?): Int = -1
        override fun underNetworkExtension(): Boolean = false
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
        override fun useProcFS(): Boolean = false
        override fun writeLog(message: String?) { Log.d(TAG, message ?: "") }
    }

    private object EmptyNetIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface = NetworkInterface()
    }
}
