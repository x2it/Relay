package com.freeproxy.app.vpn

import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
}

data class LogLine(
    val time: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val msg: String,
) {
    private companion object {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    fun formatShort(): String = "[${fmt.format(Date(time))}] ${level.label} $msg"
}

/**
 * 全局 VPN 状态管理器（单例，UI 和 Service 都能读写）
 */
object VpnStateManager {

    data class State(
        val status: VpnStatus = VpnStatus.IDLE,
        val activeProxy: ProxyInfo? = null,
        val sessionStart: Long? = null,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        val rateIn: Long = 0,
        val rateOut: Long = 0,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // 最近 200 条日志（环形缓存，新的在前）
    private const val MAX_LOGS = 200
    private val _recentLogs = MutableStateFlow<List<LogLine>>(emptyList())
    val recentLogs: StateFlow<List<LogLine>> = _recentLogs.asStateFlow()

    fun update(block: State.() -> State) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = State()
        _recentLogs.value = emptyList()
    }

    /** 追加日志，自动裁剪到 MAX_LOGS 条 */
    fun appendLog(line: LogLine) {
        val current = _recentLogs.value
        val newList = if (current.size >= MAX_LOGS) {
            ArrayList<LogLine>(MAX_LOGS + 1).apply {
                add(line)
                addAll(current.subList(0, MAX_LOGS - 1))
            }
        } else {
            ArrayList<LogLine>(current.size + 1).apply {
                add(line)
                addAll(current)
            }
        }
        _recentLogs.value = newList
    }

    fun appendLog(level: LogLevel, msg: String) = appendLog(LogLine(level = level, msg = msg))
}
