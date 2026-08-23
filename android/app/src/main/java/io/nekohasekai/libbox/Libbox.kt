package io.nekohasekai.libbox

class BoxService {
    fun create(config: String) {}
    fun start() {}
    fun stop() {}
    fun destroy() {}
    fun close() {}
    fun isRunning(): Boolean = false
}

class SetupOptions {
    var configPath: String = ""
    var cachePath: String = ""
    var includeIPv6: Boolean = false
    var autoDetectInterface: Boolean = true
    var preferIPv6: Boolean = false
    var extraPath: String = ""
    var basePath: String = ""
    var workingPath: String = ""
    var tempPath: String = ""
}

class TunOptions {
    var fd: Int = -1
    var tunName: String = ""
    var mtu: Int = 1500
    var ipv4Address: String = ""
    var ipv6Address: String = ""
}

class WIFIState {
    var ssid: String = ""
    var bssid: String = ""
}

class NetworkInterface {
    var name: String = ""
    var addrs: String = ""
}

interface NetworkInterfaceIterator {
    fun hasNext(): Boolean
    fun next(): NetworkInterface
}

class Notification {
    var id: Int = 0
    var channelId: String = ""
    var channelName: String = ""
    var title: String = ""
    var content: String = ""
}

interface InterfaceUpdateListener {
    fun onUpdate()
}

interface PlatformInterface {
    fun acquireWakeLock(): Boolean
    fun releaseWakeLock()
    fun getInterfaces(): NetworkInterfaceIterator
    fun includeAllNetworks(): Boolean
    fun openTun(options: TunOptions?): Int
    fun packageNameByUid(uid: Int): String?
    fun readWIFIState(): WIFIState
    fun sendNotification(notification: Notification?)
    fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?)
    fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?)
    fun uidByPackageName(packageName: String?): Int
    fun underNetworkExtension(): Boolean
    fun useProcFS(): Boolean
    fun writeLog(message: String?)
    fun autoDetectInterfaceControl(fd: Int)
    fun usePlatformAutoDetectInterfaceControl(): Boolean
    fun clearDNSCache()
    fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int,
    ): Int
}

object Libbox {
    fun createService(platform: PlatformInterface): BoxService = BoxService()
    fun getVersion(): String = "stub"
    fun touch() {}
    fun checkConfig(config: String) {}
    fun newService(config: String, platform: PlatformInterface): BoxService = BoxService()
    fun setup(options: SetupOptions) {}
}
