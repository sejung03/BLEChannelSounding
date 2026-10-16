package com.example.blecsranger.ranging

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.example.blecsranger.model.BoardConfig
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class GattConnectionKeeper(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnectionState(boardIndex: Int, state: State, detail: String)
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter = requireNotNull(bluetoothManager.adapter) {
        "Bluetooth adapter is unavailable"
    }
    private val handler = Handler(Looper.getMainLooper())
    private val gatts = ConcurrentHashMap<Int, BluetoothGatt>()
    private val configs = ConcurrentHashMap<Int, BoardConfig>()
    private val states = ConcurrentHashMap<Int, State>()
    private val gattConnected = ConcurrentHashMap<Int, Boolean>()
    private val connectQueue = ArrayDeque<Int>()
    private val retryCounts = ConcurrentHashMap<Int, Int>()
    @Volatile private var reconnectEnabled = true
    @Volatile private var bondingBoardIndex: Int? = null
    @Volatile private var activeConnectBoardIndex: Int? = null

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            ) ?: return
            val boardIndex = configs.entries.firstOrNull {
                it.value.macAddress.equals(device.address, ignoreCase = true)
            }?.key ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDING -> {
                    states[boardIndex] = State.CONNECTING
                    listener.onConnectionState(boardIndex, State.CONNECTING, "페어링 승인 대기")
                }

                BluetoothDevice.BOND_BONDED -> {
                    clearActiveBond(boardIndex)
                    markReady(boardIndex)
                }

                BluetoothDevice.BOND_NONE -> {
                    val previous = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_NONE,
                    )
                    if (previous == BluetoothDevice.BOND_BONDING) {
                        clearActiveBond(boardIndex)
                        recycleAndRetry(boardIndex, "페어링 실패·새 연결 재시도")
                    }
                }
            }
        }
    }

    init {
        appContext.registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun state(boardIndex: Int): State = states[boardIndex] ?: State.DISCONNECTED

    fun allConnected(boards: List<BoardConfig>): Boolean =
        boards.all { state(it.index) == State.CONNECTED }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connectAll(boards: List<BoardConfig>) {
        closeAll()
        reconnectEnabled = true
        boards.sortedBy { it.index }.forEach { board ->
            configs[board.index] = board
            gattConnected[board.index] = false
            states[board.index] = State.CONNECTING
            retryCounts[board.index] = 0
            connectQueue.addLast(board.index)
            listener.onConnectionState(board.index, State.CONNECTING, "순차 연결 대기")
        }
        handler.post { startNextConnectionIfNeeded() }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startNextConnectionIfNeeded() {
        if (!reconnectEnabled || activeConnectBoardIndex != null) return
        val boardIndex = generateSequence {
            if (connectQueue.isEmpty()) null else connectQueue.removeFirst()
        }.firstOrNull { configs.containsKey(it) && states[it] != State.CONNECTED } ?: return
        val board = configs[boardIndex] ?: return
        activeConnectBoardIndex = boardIndex
        states[boardIndex] = State.CONNECTING
        val attempt = (retryCounts[boardIndex] ?: 0) + 1
        listener.onConnectionState(
            boardIndex,
            State.CONNECTING,
            if (attempt == 1) "순차 연결 중" else "새 GATT로 재연결 중($attempt)",
        )

        runCatching {
            val device = bluetoothAdapter.getRemoteDevice(board.macAddress)
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.onSuccess { gatt ->
            gatts[boardIndex] = gatt
        }.onFailure { error ->
            activeConnectBoardIndex = null
            recycleAndRetry(boardIndex, error.message ?: "GATT 생성 실패")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun closeAll() {
        reconnectEnabled = false
        handler.removeCallbacksAndMessages(null)
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        configs.clear()
        gattConnected.clear()
        connectQueue.clear()
        retryCounts.clear()
        bondingBoardIndex = null
        activeConnectBoardIndex = null
        states.keys.forEach { states[it] = State.DISCONNECTED }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val boardIndex = configs.entries.firstOrNull {
                it.value.macAddress.equals(gatt.device.address, ignoreCase = true)
            }?.key ?: return
            if (gatts[boardIndex] !== gatt) {
                runCatching { gatt.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gattConnected[boardIndex] = true
                retryCounts[boardIndex] = 0
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                    markReady(boardIndex)
                } else {
                    states[boardIndex] = State.CONNECTING
                    listener.onConnectionState(boardIndex, State.CONNECTING, "GATT 연결됨·페어링 대기")
                    startNextBondIfNeeded()
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS ||
                newState == BluetoothProfile.STATE_DISCONNECTED
            ) {
                val detail = if (status == BluetoothGatt.GATT_CONNECTION_TIMEOUT) {
                    "연결 시간 초과(147)·새 연결 재시도"
                } else {
                    "끊김(status=$status)·새 연결 재시도"
                }
                recycleAndRetry(boardIndex, detail)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startNextBondIfNeeded() {
        if (!reconnectEnabled || bondingBoardIndex != null) return
        val nextIndex = configs.keys.sorted().firstOrNull { index ->
            gattConnected[index] == true &&
                states[index] != State.CONNECTED &&
                gatts[index]?.device?.bondState != BluetoothDevice.BOND_BONDED
        } ?: return
        val device = gatts[nextIndex]?.device ?: return
        bondingBoardIndex = nextIndex
        states[nextIndex] = State.CONNECTING
        listener.onConnectionState(nextIndex, State.CONNECTING, "페어링 승인 대기")
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started && device.bondState != BluetoothDevice.BOND_BONDING) {
            bondingBoardIndex = null
            recycleAndRetry(nextIndex, "페어링 요청 실패·새 연결 재시도")
        }
    }

    @Synchronized
    private fun clearActiveBond(boardIndex: Int) {
        if (bondingBoardIndex == boardIndex) bondingBoardIndex = null
    }

    @Synchronized
    private fun markReady(boardIndex: Int) {
        states[boardIndex] = State.CONNECTED
        listener.onConnectionState(boardIndex, State.CONNECTED, "연결됨·본딩됨")
        if (activeConnectBoardIndex == boardIndex) activeConnectBoardIndex = null
        handler.postDelayed({ startNextConnectionIfNeeded() }, NEXT_CONNECTION_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun recycleAndRetry(boardIndex: Int, detail: String) {
        val oldGatt = gatts.remove(boardIndex)
        if (oldGatt != null) {
            runCatching { oldGatt.disconnect() }
            runCatching { oldGatt.close() }
        }
        gattConnected[boardIndex] = false
        clearActiveBond(boardIndex)
        if (activeConnectBoardIndex == boardIndex) activeConnectBoardIndex = null
        states[boardIndex] = State.DISCONNECTED
        listener.onConnectionState(boardIndex, State.DISCONNECTED, detail)
        if (!reconnectEnabled || !configs.containsKey(boardIndex)) return

        val retry = (retryCounts[boardIndex] ?: 0) + 1
        retryCounts[boardIndex] = retry
        val delayMs = (RECONNECT_BASE_DELAY_MS + (retry - 1) * 500L)
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        handler.postDelayed({
            synchronized(this) {
                if (!reconnectEnabled || !configs.containsKey(boardIndex)) return@postDelayed
                if (!connectQueue.contains(boardIndex)) connectQueue.addLast(boardIndex)
            }
            startNextConnectionIfNeeded()
        }, delayMs)
    }

    fun destroy() {
        closeAll()
        runCatching { appContext.unregisterReceiver(bondReceiver) }
    }

    companion object {
        private const val NEXT_CONNECTION_DELAY_MS = 500L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 5_000L
    }
}
