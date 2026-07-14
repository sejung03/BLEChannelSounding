package com.example.blecsreflector

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

enum class CsSupport {
    CHECKING,
    AVAILABLE,
    DISABLED,
    UNSUPPORTED,
    REQUIRES_ANDROID_16,
    ERROR
}

enum class RangingSessionState {
    IDLE,
    WAITING_FOR_BOND,
    WAITING_FOR_INDICATIONS,
    OPENING,
    OPEN,
    ACTIVE,
    FAILED,
    CLOSED
}

data class ReflectorUiState(
    val serviceRunning: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val advertising: Boolean = false,
    val gattReady: Boolean = false,
    val csSupport: CsSupport = CsSupport.CHECKING,
    val csSecurityLevels: Set<Int> = emptySet(),
    val connectedDeviceName: String? = null,
    val connectedDeviceAddress: String? = null,
    val bondState: Int? = null,
    val indicationsEnabled: Boolean = false,
    val sessionState: RangingSessionState = RangingSessionState.IDLE,
    val mtu: Int = 23,
    val lastFrame: String? = null,
    val logs: List<String> = emptyList()
)

object ReflectorStateStore {
    private const val MAX_LOG_LINES = 160
    private val listeners = CopyOnWriteArraySet<(ReflectorUiState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var current = ReflectorUiState()

    fun snapshot(): ReflectorUiState = current

    fun observe(listener: (ReflectorUiState) -> Unit) {
        listeners += listener
        dispatch(listener, current)
    }

    fun removeObserver(listener: (ReflectorUiState) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun update(transform: (ReflectorUiState) -> ReflectorUiState) {
        current = transform(current)
        notifyListeners(current)
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
        val nextLogs = (current.logs + "$timestamp  $message").takeLast(MAX_LOG_LINES)
        current = current.copy(logs = nextLogs)
        notifyListeners(current)
    }

    @Synchronized
    fun clearLogs() {
        current = current.copy(logs = emptyList())
        notifyListeners(current)
    }

    private fun notifyListeners(state: ReflectorUiState) {
        listeners.forEach { dispatch(it, state) }
    }

    private fun dispatch(listener: (ReflectorUiState) -> Unit, state: ReflectorUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener(state)
        } else {
            mainHandler.post { listener(state) }
        }
    }
}
