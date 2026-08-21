package com.freeproxy.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.freeproxy.app.FreeProxyApp
import com.freeproxy.app.R
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.VpnStatus
import com.freeproxy.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy as JvmProxy
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量级 VPN 服务：
 *   1) 建立 TUN 接口（10.0.2.1/24，DNS 8.8.8.8）
 *   2) 读取 IPv4 TCP / UDP 包
 *   3) TCP：根据 srcPort 对应当前连接，通过 HTTP/SOCKS 代理做 CONNECT 握手，双向桥接
 *   4) UDP DNS (dst 53)：通过 DoH + OkHttp 代理解析，失败回退 TCP DNS
 */
class ProxyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private var trafficSyncJob: Job? = null
    private var bytesIn = AtomicLong(0)
    private var bytesOut = AtomicLong(0)
    // 速率计算：上次同步时的字节和时间戳
    private var lastSyncBytesIn = 0L
    private var lastSyncBytesOut = 0L
    private var lastSyncTime = 0L

    private val connections = ConcurrentHashMap<Int, Tunnel>() // sourcePort -> tunnel
    private var currentProxy: ProxyInfo? = null
    private var currentRouteMode: RouteMode = RouteMode.SMART

    // 虚拟网络参数
    private val tunIp = "10.0.2.1"
    private val dnsIp = "8.8.8.8"
    private val sessionId = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键修复：Android 12+ 通过 startForegroundService 启动后，
        // 必须在 5s 内调用 startForeground，否则抛 ForegroundServiceDidNotStartInTimeException 闪退。
        // 因此任何路径（包括 proxy 为空、已正在运行需要忽略）都先 ensureForeground。
        when (intent?.action) {
            ACTION_START -> {
                val proxy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROXY, ProxyInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROXY)
                }
                if (proxy != null) {
                    startVpn(proxy)
                } else {
                    // 缺少 proxy extra：先满足前台要求再自停，避免系统抛异常
                    ensureForegroundThenStop()
                }
            }
            ACTION_STOP -> {
                stopVpn()
                ensureForegroundThenStop()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 防御性兜底：先以一个最小通知满足 startForeground 契约，再 stopSelf。
     * 用于 ACTION_START 缺 proxy、ACTION_STOP、或 vpnJob 已 active 的早退路径。
     */
    private fun ensureForegroundThenStop() {
        runCatching {
            startForegroundCompat(NOTIF_ID, buildNotification(VpnStatus.IDLE, currentProxy))
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        runCatching { serviceScope.cancel() }
        super.onDestroy()
        instance = null
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn(proxy: ProxyInfo) {
        if (vpnJob?.isActive == true) {
            // 已有连接在进行：仍需满足 startForeground 契约，避免系统抛
            // ForegroundServiceDidNotStartInTimeException
            runCatching {
                startForegroundCompat(NOTIF_ID, buildNotification(VpnStatus.CONNECTING, proxy))
            }
            return
        }
        currentProxy = proxy
        VpnStateManager.update {
            copy(status = VpnStatus.CONNECTING, activeProxy = proxy, lastError = null,
                sessionStart = System.currentTimeMillis(), bytesIn = 0, bytesOut = 0)
        }
        bytesIn.set(0); bytesOut.set(0)
        lastSyncBytesIn = 0L; lastSyncBytesOut = 0L; lastSyncTime = 0L
        try {
            startForegroundCompat(NOTIF_ID, buildNotification(VpnStatus.CONNECTING, proxy))
        } catch (e: Exception) {
            android.util.Log.e("ProxyVpnService", "startForeground failed", e)
            VpnStateManager.update { copy(status = VpnStatus.ERROR, lastError = "前台服务启动失败: ${e.message}") }
            stopSelf()
            return
        }

        vpnJob = serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val appCtx = application as? FreeProxyApp
                val prefs = appCtx?.userPrefs
                val mode = if (prefs != null) prefs.routeMode.first() else RouteMode.SMART
                currentRouteMode = mode
                val excluded = if (prefs != null) prefs.excludedApps.first() else emptySet()

                val builder = Builder()
                    .setSession("Relay")
                    .addAddress(tunIp, 24)
                    .addDnsServer(dnsIp)
                    .addDisallowedApplication(packageName)
                    .setMtu(1500)
                RoutePolicy.applyRoutes(builder, mode)
                RoutePolicy.applyAppExclusions(builder, this@ProxyVpnService, excluded)

                val newTun = builder.establish()
                    ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
                tun = newTun
                VpnStateManager.update { copy(status = VpnStatus.CONNECTED) }
                VpnStateManager.appendLog(LogLevel.INFO, "VPN connected mode=$mode proxy=${proxy.display()}")
                startForegroundCompat(NOTIF_ID, buildNotification(VpnStatus.CONNECTED, proxy))
                startTrafficSync()
                runTunLoop(newTun, appCtx)
            }.onFailure { e ->
                VpnStateManager.appendLog(LogLevel.ERROR, "VPN start failed: ${e.message}")
                VpnStateManager.update {
                    copy(status = VpnStatus.ERROR, lastError = e.message ?: e.javaClass.simpleName)
                }
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        VpnStateManager.update {
            val keepProxy = activeProxy
            copy(status = VpnStatus.DISCONNECTING, activeProxy = keepProxy)
        }
        runCatching { trafficSyncJob?.cancel() }
        trafficSyncJob = null
        runCatching { vpnJob?.cancel() }
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        runCatching { tun?.close() }
        tun = null
        VpnStateManager.update { copy(status = VpnStatus.IDLE, activeProxy = null, bytesIn = 0, bytesOut = 0, rateIn = 0, rateOut = 0) }
        // stopForeground 参数兼容：STOP_FOREGROUND_REMOVE 需 API 33，低版本用旧版布尔
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ==================== 流量同步：每秒把 bytesIn/bytesOut 同步到 UI，并计算实时速率 ====================

    private fun startTrafficSync() {
        trafficSyncJob?.cancel()
        lastSyncBytesIn = 0L
        lastSyncBytesOut = 0L
        lastSyncTime = System.currentTimeMillis()
        trafficSyncJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = System.currentTimeMillis()
                val curIn = bytesIn.get()
                val curOut = bytesOut.get()
                val dtMs = now - lastSyncTime
                val rateIn = if (dtMs > 0) ((curIn - lastSyncBytesIn) * 1000 / dtMs).coerceAtLeast(0) else 0
                val rateOut = if (dtMs > 0) ((curOut - lastSyncBytesOut) * 1000 / dtMs).coerceAtLeast(0) else 0
                lastSyncBytesIn = curIn
                lastSyncBytesOut = curOut
                lastSyncTime = now
                VpnStateManager.update {
                    copy(bytesIn = curIn, bytesOut = curOut, rateIn = rateIn, rateOut = rateOut)
                }
            }
        }
    }

    // ==================== TUN 读取 + 简易 IP/TCP/UDP 处理 ====================

    private fun runTunLoop(tunFd: ParcelFileDescriptor, appCtx: FreeProxyApp?) {
        val fis = FileInputStream(tunFd.fileDescriptor)
        val fos = FileOutputStream(tunFd.fileDescriptor)
        val buf = ByteBuffer.allocate(1500)
        val byteArr = ByteArray(1500)

        while (!Thread.interrupted()) {
            val n = fis.read(byteArr)
            if (n <= 0) continue
            bytesIn.addAndGet(n.toLong())
            buf.clear()
            buf.put(byteArr, 0, n)
            buf.flip()
            handleIpPacket(buf, fos, appCtx)
        }
    }

    /**
     * 处理 IP 包：支持 IPv4 TCP (protocol=6) 与 UDP (protocol=17)。
     */
    private fun handleIpPacket(buf: ByteBuffer, tunOut: FileOutputStream, appCtx: FreeProxyApp?) {
        if (buf.remaining() < 20) return
        val start = buf.position()
        val version = (buf.get(start).toInt() shr 4) and 0xF
        if (version != 4) return
        val ihl = (buf.get(start).toInt() and 0xF) * 4
        if (ihl < 20) return
        val totalLen = buf.getShort(start + 2).toInt() and 0xFFFF
        if (totalLen < ihl || totalLen > buf.remaining()) return
        val protocol = buf.get(start + 9).toInt() and 0xFF
        val srcIpInt = buf.getInt(start + 12)
        val dstIpInt = buf.getInt(start + 16)

        when (protocol) {
            6 -> handleTcpPacket(buf, start, ihl, totalLen, srcIpInt, dstIpInt, tunOut)
            17 -> handleUdpPacket(buf, start, ihl, totalLen, srcIpInt, dstIpInt, tunOut, appCtx)
            // 其他协议静默 drop
        }
    }

    // ---------- TCP ----------

    private fun handleTcpPacket(
        buf: ByteBuffer, start: Int, ihl: Int, totalLen: Int,
        srcIpInt: Int, dstIpInt: Int, tunOut: FileOutputStream,
    ) {
        val tcpStart = start + ihl
        if (buf.remaining() - (tcpStart - start) < 20) return

        val srcPort = buf.getShort(tcpStart).toInt() and 0xFFFF
        val dstPort = buf.getShort(tcpStart + 2).toInt() and 0xFFFF
        val seqNum = buf.getInt(tcpStart + 4).toLong() and 0xFFFFFFFFL
        val ackNum = buf.getInt(tcpStart + 8).toLong() and 0xFFFFFFFFL
        val dataOffset = (buf.getShort(tcpStart + 12).toInt() shr 4) and 0xF
        val flags = buf.getShort(tcpStart + 13).toInt() and 0xFF
        // 读取 window (2 bytes)
        val windowRaw = buf.getShort(tcpStart + 14).toInt() and 0xFFFF
        val payloadStart = tcpStart + dataOffset * 4
        val payloadLen = totalLen - (payloadStart - start)

        val fin = (flags and 0x01) != 0
        val syn = (flags and 0x02) != 0
        val rst = (flags and 0x04) != 0
        val ackF = (flags and 0x10) != 0

        val key = srcPort

        if (rst || fin) {
            connections.remove(key)?.close()
            sendTcpReply(tunOut, dstIpInt, srcIpInt, dstPort, srcPort,
                seq = ackNum, ack = if (syn) seqNum + 1 else seqNum + payloadLen.coerceAtLeast(0),
                flags = 0x10 or if (fin) 0x01 else 0x04)
            return
        }

        if (syn) {
            // SMART 模式下：如果 dstIp 是 CN IP，默默 drop，让系统重试真实网卡
            if (currentRouteMode == RouteMode.SMART && ChinaIpList.isCn(dstIpInt)) {
                VpnStateManager.appendLog(LogLevel.INFO,
                    "DIRECT-CN ${intToIp(dstIpInt)}:$dstPort")
                return
            }
            val tunnel = connections[key] ?: run {
                val t = Tunnel(srcIpInt, dstIpInt, srcPort, dstPort, initialSeq = seqNum, tunOut,
                    currentProxy ?: return)
                connections[key] = t
                t.startAsync(serviceScope)
                t
            }
            tunnel.gotSyn(seqNum)
            return
        }

        val tunnel = connections[key] ?: return
        if (payloadLen <= 0) {
            tunnel.gotAck(ackNum, windowRaw)
            return
        }

        val bytes = ByteArray(payloadLen)
        buf.position(payloadStart)
        buf.get(bytes)
        tunnel.feedPayload(bytes, seqNum, expectAckUpTo = seqNum + payloadLen, windowRaw)
    }

    // ---------- UDP DNS ----------

    private fun handleUdpPacket(
        buf: ByteBuffer, start: Int, ihl: Int, totalLen: Int,
        srcIpInt: Int, dstIpInt: Int, tunOut: FileOutputStream, appCtx: FreeProxyApp?,
    ) {
        val udpStart = start + ihl
        if (totalLen - (udpStart - start) < 8) return
        val srcPort = buf.getShort(udpStart).toInt() and 0xFFFF
        val dstPort = buf.getShort(udpStart + 2).toInt() and 0xFFFF
        val udpLen = buf.getShort(udpStart + 4).toInt() and 0xFFFF
        if (udpLen < 8) return
        if (dstPort != 53) return // 只处理 DNS

        val queryLen = udpLen - 8
        if (queryLen <= 0) return
        val queryBytes = ByteArray(queryLen)
        buf.position(udpStart + 8)
        buf.get(queryBytes)

        val qnameAndType = DnsForwarder.parseQuery(queryBytes)
        val qname = qnameAndType?.first ?: "?"

        val okClient = appCtx?.okhttp ?: return
        val proxy = currentProxy

        serviceScope.launch(Dispatchers.IO) {
            val respBytes = DnsForwarder.resolve(okClient, proxy, queryBytes, 3000)
            if (respBytes == null || respBytes.isEmpty()) {
                VpnStateManager.appendLog(LogLevel.WARN, "DNS resolve timeout: $qname")
                return@launch
            }
            val finalResp = if (respBytes.size > 512) respBytes.copyOf(512) else respBytes
            runCatching {
                sendUdpReply(tunOut,
                    srcIp = dstIpInt, dstIp = srcIpInt,
                    srcPort = dstPort, dstPort = srcPort,
                    payload = finalResp)
            }
        }
    }

    // ==================== 写回 TUN 工具函数 ====================

    private fun sendTcpReply(
        out: FileOutputStream,
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray? = null,
        window: Short = 0x2000,
    ) {
        val payLen = payload?.size ?: 0
        val ipHdr = 20; val tcpHdr = 20
        val total = ipHdr + tcpHdr + payLen
        val bb = ByteBuffer.allocate(total)

        val verIhl = (4 shl 4) or 5
        bb.put(verIhl.toByte())
        bb.put(0)
        bb.putShort(total.toShort())
        bb.putShort(sessionId.incrementAndGet().toShort())
        bb.putShort(0)
        bb.put(64)
        bb.put(6)
        bb.putShort(0)
        bb.putInt(srcIp)
        bb.putInt(dstIp)
        bb.putShort(0, checksum(bb.array(), 0, ipHdr).toShort())

        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putInt(seq.toInt())
        bb.putInt(ack.toInt())
        val dataOff = (5 shl 4)
        bb.put((dataOff or 0).toByte())
        bb.put((flags and 0xFF).toByte())
        bb.putShort(window)
        bb.putShort(0)
        bb.putShort(0)

        if (payload != null) bb.put(payload)

        val ph = ByteBuffer.allocate(12)
        ph.putInt(srcIp); ph.putInt(dstIp)
        ph.put(0); ph.put(6); ph.putShort((tcpHdr + payLen).toShort())
        val tcpCk = tcpChecksum(bb.array(), ipHdr, tcpHdr + payLen, ph.array())
        bb.putShort(ipHdr + 16, tcpCk.toShort())

        runCatching {
            synchronized(out) {
                out.write(bb.array(), 0, total)
                out.flush()
            }
            bytesOut.addAndGet(total.toLong())
        }
    }

    private fun sendUdpReply(
        out: FileOutputStream,
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        payload: ByteArray,
    ) {
        val payLen = payload.size
        val ipHdr = 20; val udpHdr = 8
        val udpTotal = udpHdr + payLen
        val total = ipHdr + udpTotal
        val bb = ByteBuffer.allocate(total)

        // IPv4 header
        val verIhl = (4 shl 4) or 5
        bb.put(verIhl.toByte())
        bb.put(0)
        bb.putShort(total.toShort())
        bb.putShort(sessionId.incrementAndGet().toShort())
        bb.putShort(0)
        bb.put(64)
        bb.put(17) // UDP
        bb.putShort(0)
        bb.putInt(srcIp)
        bb.putInt(dstIp)
        bb.putShort(0, checksum(bb.array(), 0, ipHdr).toShort())

        // UDP header: srcPort, dstPort, length, checksum(0 先占位)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpTotal.toShort())
        bb.putShort(0) // checksum placeholder
        bb.put(payload)

        // UDP checksum (含 pseudo header)
        val ph = ByteBuffer.allocate(12)
        ph.putInt(srcIp); ph.putInt(dstIp)
        ph.put(0); ph.put(17); ph.putShort(udpTotal.toShort())
        val udpCk = tcpChecksum(bb.array(), ipHdr, udpTotal, ph.array())
        bb.putShort(ipHdr + 6, udpCk.toShort()) // 写回 UDP checksum

        runCatching {
            synchronized(out) {
                out.write(bb.array(), 0, total)
                out.flush()
            }
            bytesOut.addAndGet(total.toLong())
        }
    }

    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((data[i].toInt() and 0xFF shl 8) or (data[i+1].toInt() and 0xFF))
            i += 2
        }
        if (i < off + len) sum += (data[i].toInt() and 0xFF shl 8)
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun tcpChecksum(tcp: ByteArray, tcpOff: Int, tcpLen: Int, pseudo: ByteArray): Int {
        var sum = 0L
        fun add(bs: ByteArray, off: Int, len: Int) {
            var i = off
            while (i < off + len - 1) {
                sum += ((bs[i].toInt() and 0xFF shl 8) or (bs[i+1].toInt() and 0xFF))
                i += 2
            }
            if (i < off + len) sum += (bs[i].toInt() and 0xFF shl 8)
        }
        add(pseudo, 0, pseudo.size)
        add(tcp, tcpOff, tcpLen)
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    // ==================== Tunnel：把 app 的 TCP 流桥接到上游代理 ====================

    private inner class Tunnel(
        val srcIp: Int, val dstIp: Int,
        val srcPort: Int, val dstPort: Int,
        initialSeq: Long,
        val tunOut: FileOutputStream,
        val proxy: ProxyInfo,
    ) : Closeable {

        private val appToProxyQueue = ArrayDeque<ByteArray>()
        private val lock = Object()
        @Volatile private var closed = false

        // app -> VPN 侧：app 发过来的 seq 追踪
        private var appLastSeq = initialSeq
        private var ourLastAck = initialSeq

        // VPN -> app 侧：我们发回给 app 的 seq
        private var ourSeq = (System.currentTimeMillis() and 0xFFFFFFFFL) xor 0x12345678L

        // ===== T5.1.1 窗口控制 =====
        private var peerWindow: Int = 0x2000
        private var peerWindowUsed: Int = 0

        // ===== T5.1.3 ACK 合并 =====
        @Volatile private var pendingAck: Long? = null
        private var ackJob: Job? = null
        private val ackLock = Any()

        // ===== T5.1.4 空闲计时器活动时间 =====
        @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private var idleJob: Job? = null

        private var upstreamSocket: Socket? = null

        fun startAsync(scope: CoroutineScope) {
            scope.launch(Dispatchers.IO) { run() }
            // T5.1.4 空闲 15s 回收
            idleJob = scope.launch(Dispatchers.IO) {
                while (!closed) {
                    delay(5000)
                    if (System.currentTimeMillis() - lastActivityMs > 15_000L) {
                        runCatching { close() }
                        break
                    }
                }
            }
        }

        fun touchActivity() { lastActivityMs = System.currentTimeMillis() }

        fun gotSyn(seq: Long) {
            touchActivity()
            sendAckFlags(flags = 0x12, payload = null, seq = ourSeq, ack = seq + 1)
            ourSeq++
            appLastSeq = seq
            ourLastAck = seq + 1
        }

        fun gotAck(ack: Long, windowFromApp: Int = 0x2000) {
            touchActivity()
            // T5.1.1：刷新 peerWindow，推进已确认窗口
            val acked = (ack and 0xFFFFFFFFL)
            val ourBase = (ourSeq - peerWindowUsed) and 0xFFFFFFFFL
            val diff = (acked - ourBase) and 0xFFFFFFFFL
            if (diff in 0..0x7FFFFFFF) {
                peerWindowUsed = (peerWindowUsed - diff.toInt()).coerceAtLeast(0)
            }
            peerWindow = windowFromApp.coerceAtLeast(0x1000)
        }

        fun feedPayload(bytes: ByteArray, startSeq: Long, expectAckUpTo: Long, windowFromApp: Int) {
            touchActivity()
            peerWindow = windowFromApp.coerceAtLeast(0x1000)
            // T5.1.3 ACK 合并：不立刻 send ACK，用 pendingAck 延迟 50ms
            scheduleAck(expectAckUpTo)
            ourLastAck = expectAckUpTo
            synchronized(lock) {
                appToProxyQueue.addLast(bytes)
                (lock as Object).notifyAll()
            }
        }

        // T5.1.3 ACK 合并 coroutine
        private fun scheduleAck(ack: Long) {
            pendingAck = ack
            synchronized(ackLock) {
                if (ackJob?.isActive == true) return // 已有 delay，下次 delay 结束时会用最新 pendingAck
                ackJob = serviceScope.launch(Dispatchers.IO) {
                    delay(50)
                    val a = pendingAck ?: return@launch
                    pendingAck = null
                    if (!closed) {
                        sendAckFlags(flags = 0x10, payload = null,
                            seq = ourSeq, ack = a)
                    }
                }
            }
        }

        private fun run() {
            try {
                val dstHost = intToIp(dstIp)
                val sock = try {
                    connectUpstream(proxy, dstHost, dstPort, timeoutMs = 8000)
                } catch (t: Throwable) {
                    // T5.1.4 握手失败：记日志 + 回送 RST
                    val reason = t.message ?: t.javaClass.simpleName
                    VpnStateManager.appendLog(LogLevel.ERROR,
                        "connectUpstream fail $dstHost:$dstPort via ${proxy.display()}: $reason")
                    sendAckFlags(flags = 0x14, payload = null,
                        seq = ourSeq, ack = ourLastAck) // RST|ACK
                    return
                }
                upstreamSocket = sock
                val fromUpstream = sock.getInputStream()
                val toUpstream = sock.getOutputStream()

                val pumpUp = Thread {
                    try {
                        while (!closed) {
                            val chunk: ByteArray? = synchronized(lock) {
                                while (appToProxyQueue.isEmpty() && !closed)
                                    (lock as Object).wait(100)
                                if (closed && appToProxyQueue.isEmpty()) null
                                else appToProxyQueue.removeFirstOrNull()
                            }
                            if (chunk == null) break
                            toUpstream.write(chunk)
                            toUpstream.flush()
                            touchActivity()
                        }
                    } catch (_: Throwable) {
                    } finally { close() }
                }.apply { isDaemon = true; start() }

                // upstream->app：读 socket 数据，分片 + 窗口控制
                val buffer = ByteArray(8192)
                while (!closed) {
                    val n = fromUpstream.read(buffer)
                    if (n < 0) break
                    touchActivity()
                    var off = 0
                    var remaining = n
                    while (remaining > 0 && !closed) {
                        // T5.1.1 窗口检查：等待可用 peerWindow
                        var waited = 0
                        while (!closed && peerWindow - peerWindowUsed < 1) {
                            Thread.sleep(10)
                            waited += 10
                            if (waited > 5000) break // 超时硬发，避免死锁
                        }
                        if (closed) break
                        val avail = peerWindow - peerWindowUsed
                        if (avail <= 0) break
                        // T5.1.2 分片：单段不超过 1380 字节，并受窗口限制
                        val chunkSize = minOf(1380, remaining, avail)
                        val chunk = buffer.copyOfRange(off, off + chunkSize)
                        sendAckFlags(flags = 0x18, payload = chunk,
                            seq = ourSeq, ack = ourLastAck)
                        ourSeq = (ourSeq + chunkSize) and 0xFFFFFFFFL
                        peerWindowUsed += chunkSize
                        off += chunkSize
                        remaining -= chunkSize
                    }
                }
                pumpUp.interrupt()
            } catch (_: Throwable) {
            } finally {
                close()
                runCatching {
                    sendAckFlags(flags = 0x11, payload = null,
                        seq = ourSeq, ack = ourLastAck) // FIN|ACK
                }
            }
        }

        private fun sendAckFlags(flags: Int, payload: ByteArray?, seq: Long, ack: Long) {
            sendTcpReply(tunOut,
                srcIp = dstIp, dstIp = srcIp,
                srcPort = dstPort, dstPort = srcPort,
                seq = seq, ack = ack, flags = flags, payload = payload)
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) { (lock as Object).notifyAll() }
            runCatching { ackJob?.cancel() }
            runCatching { idleJob?.cancel() }
            runCatching { upstreamSocket?.close() }
            connections.remove(srcPort)
        }
    }

    // ==================== 代理上游连接握手 ====================

    private fun connectUpstream(
        proxy: ProxyInfo, targetHost: String, targetPort: Int, timeoutMs: Int,
    ): Socket {
        val type = proxy.type
        val proxyAddr = InetSocketAddress(proxy.host, proxy.port)
        return when (type) {
            com.freeproxy.app.data.model.ProxyType.SOCKS4,
            com.freeproxy.app.data.model.ProxyType.SOCKS5 -> {
                val sock = Socket(JvmProxy.NO_PROXY)
                protect(sock)
                sock.connect(proxyAddr, timeoutMs)
                if (type == com.freeproxy.app.data.model.ProxyType.SOCKS4)
                    socks4Handshake(sock, proxyAddr, targetHost, targetPort, proxy.username)
                else
                    socks5Handshake(sock, targetHost, targetPort, proxy.username, proxy.password)
                sock
            }
            else -> {
                val sock = Socket(JvmProxy.NO_PROXY)
                protect(sock)
                sock.connect(proxyAddr, timeoutMs)
                httpConnectHandshake(sock, targetHost, targetPort, proxy.username, proxy.password)
                sock
            }
        }
    }

    private fun httpConnectHandshake(
        s: Socket, host: String, port: Int, user: String?, pass: String?,
    ) {
        val req = buildString {
            append("CONNECT $host:$port HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("User-Agent: Relay/1.0\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                val auth = android.util.Base64.encodeToString(
                    "$user:$pass".toByteArray(StandardCharsets.ISO_8859_1), android.util.Base64.NO_WRAP)
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
        if (n < 2 || h[0] != 0x05.toByte()) throw IllegalStateException("SOCKS5: bad greeting")
        if (h[1] == 0x02.toByte()) {
            val u = user!!.toByteArray(); val p = pass!!.toByteArray()
            out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            out.flush()
            val ah = ByteArray(2); ins.read(ah)
            if (ah[0] != 0x01.toByte() || ah[1] != 0x00.toByte())
                throw IllegalStateException("SOCKS5: auth failed")
        } else if (h[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5: unsupported auth method ${h[1]}")
        }
        val addrType: Byte = 0x03
        val hb = host.toByteArray(StandardCharsets.ISO_8859_1)
        val req = ByteArray(10 + hb.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = addrType
        req[4] = hb.size.toByte()
        System.arraycopy(hb, 0, req, 5, hb.size)
        req[5 + hb.size] = (port shr 8 and 0xFF).toByte()
        req[6 + hb.size] = (port and 0xFF).toByte()
        out.write(req, 0, 7 + hb.size); out.flush()
        val rh = ByteArray(4); if (ins.read(rh) < 4) throw IllegalStateException("SOCKS5: connect EOF")
        if (rh[1] != 0x00.toByte()) throw IllegalStateException("SOCKS5: connect failed code=${rh[1].toInt()}")
        when (rh[3].toInt()) {
            0x01 -> ins.skip(4)
            0x04 -> ins.skip(16)
            0x03 -> {
                val lb = ByteArray(1); ins.read(lb); ins.skip(lb[0].toLong())
            }
        }
        ins.skip(2)
    }

    private fun socks4Handshake(
        s: Socket, proxyAddr: InetSocketAddress, host: String, port: Int, user: String?,
    ) {
        val out = s.getOutputStream(); val ins = s.getInputStream()
        val userBytes = (user ?: "").toByteArray(StandardCharsets.ISO_8859_1)
        val hostBytes = host.toByteArray(StandardCharsets.ISO_8859_1)
        val ip = if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
            host.split(".").map { it.toInt().toByte() }.toByteArray()
        } else ByteArray(4) { 0 }.apply { this[3] = 1 }
        val req = ByteArray(9 + userBytes.size + 1 + hostBytes.size + 1)
        req[0] = 0x04; req[1] = 0x01
        req[2] = (port shr 8 and 0xFF).toByte(); req[3] = (port and 0xFF).toByte()
        System.arraycopy(ip, 0, req, 4, 4)
        System.arraycopy(userBytes, 0, req, 8, userBytes.size)
        var off = 8 + userBytes.size
        req[off++] = 0
        if (!host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
            System.arraycopy(hostBytes, 0, req, off, hostBytes.size)
            off += hostBytes.size
            req[off] = 0
        }
        out.write(req); out.flush()
        val resp = ByteArray(8); ins.readFully(resp)
        if (resp[1] != 0x5A.toByte()) throw IllegalStateException("SOCKS4: rejected status=${resp[1]}")
    }

    private fun intToIp(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    private fun java.io.InputStream.readFully(b: ByteArray) {
        var off = 0; val len = b.size
        while (off < len) {
            val n = read(b, off, len - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    // ==================== Notification ====================

    /**
     * 兼容启动前台服务。
     * 注意：VPN 服务通过 BIND_VPN_SERVICE 权限豁免 foregroundServiceType 声明要求，
     * 不要传 type 参数，否则 Android 14+ 上若清单未声明对应 type 会抛
     * MissingForegroundServiceTypeException 导致闪退。
     */
    private fun startForegroundCompat(id: Int, notification: Notification) {
        // targetSdk=33：Android 14 设备走兼容路径，不强制 foregroundServiceType 声明，
        // 也无需在 startForeground 时传 type，避免本地 SDK 缺 vpn flag 的 AAPT 校验问题
        startForeground(id, notification)
    }

    private fun buildNotification(status: VpnStatus, proxy: ProxyInfo?): Notification {
        val title = getString(R.string.vpn_notification_title)
        val text = when (status) {
            VpnStatus.CONNECTED ->
                getString(R.string.vpn_notification_connected) +
                    (proxy?.let { "  ${it.display()}(${it.type.value})" } ?: "")
            VpnStatus.CONNECTING -> getString(R.string.vpn_notification_connecting)
            else -> getString(R.string.disconnected)
        }
        val click = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(click)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val ACTION_START = "com.freeproxy.app.vpn.START"
        const val ACTION_STOP = "com.freeproxy.app.vpn.STOP"
        const val EXTRA_PROXY = "extra_proxy"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "freeproxy_vpn"

        @Volatile var instance: ProxyVpnService? = null
            private set
    }
}
