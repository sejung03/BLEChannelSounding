package com.example.blecsreflector.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.ranging.oob.TransportHandle
import com.example.blecsreflector.ReflectorStateStore
import com.example.blecsreflector.ranging.OobFrameInspector
import java.util.ArrayDeque
import java.util.concurrent.Executor

class GattOobTransport(
    private val context: Context,
    private val gattServer: BluetoothGattServer,
    private val device: BluetoothDevice,
    private val txCharacteristic: BluetoothGattCharacteristic
) : TransportHandle {
    private val pending = ArrayDeque<ByteArray>()
    private val pendingIncoming = ArrayDeque<ByteArray>()
    private var callbackExecutor: Executor? = null
    private var receiveCallback: TransportHandle.ReceiveCallback? = null
    private var sending = false
    private var closed = false
    private var connected = true
    private var subscribed = false

    @Synchronized
    override fun registerReceiveCallback(
        executor: Executor,
        callback: TransportHandle.ReceiveCallback
    ) {
        check(!closed) { "Transport is closed" }
        callbackExecutor = executor
        receiveCallback = callback
        while (pendingIncoming.isNotEmpty()) {
            val data = pendingIncoming.removeFirst()
            dispatch { it.onReceiveData(data) }
        }
    }

    @Synchronized
    override fun sendData(data: ByteArray) {
        require(data.isNotEmpty()) { "OOB data must not be empty" }
        if (closed || !connected || !subscribed) {
            notifySendFailed()
            return
        }

        val copy = data.copyOf()
        pending.addLast(copy)
        ReflectorStateStore.update {
            it.copy(lastFrame = OobFrameInspector.summarize("TX", copy))
        }
        ReflectorStateStore.log(OobFrameInspector.summarize("TX", copy))
        drainQueueLocked()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        pendingIncoming.clear()
        sending = false
        dispatch { it.onClose() }
    }

    @Synchronized
    fun isUsable(): Boolean = !closed

    @Synchronized
    fun setSubscribed(enabled: Boolean) {
        subscribed = enabled
        if (enabled) {
            drainQueueLocked()
        }
    }

    @Synchronized
    fun receiveFromPeer(data: ByteArray) {
        if (closed || !connected) return
        val copy = data.copyOf()
        ReflectorStateStore.update {
            it.copy(lastFrame = OobFrameInspector.summarize("RX", copy))
        }
        ReflectorStateStore.log(OobFrameInspector.summarize("RX", copy))
        if (callbackExecutor == null || receiveCallback == null) {
            pendingIncoming.addLast(copy)
        } else {
            dispatch { it.onReceiveData(copy) }
        }
    }

    @Synchronized
    fun onNotificationSent(status: Int) {
        if (!sending) return
        if (pending.isNotEmpty()) pending.removeFirst()
        sending = false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            ReflectorStateStore.log("OOB indication failed: GATT status $status")
            notifySendFailed()
        }
        drainQueueLocked()
    }

    @Synchronized
    fun onDisconnected() {
        if (!connected || closed) return
        connected = false
        pending.clear()
        sending = false
        dispatch { it.onDisconnect() }
    }

    @Synchronized
    fun onReconnected() {
        if (connected || closed) return
        connected = true
        dispatch { it.onReconnect() }
        drainQueueLocked()
    }

    private fun drainQueueLocked() {
        if (sending || pending.isEmpty() || closed || !connected || !subscribed) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pending.removeFirst()
            notifySendFailed()
            return
        }
        val next = pending.first()
        val result = try {
            gattServer.notifyCharacteristicChanged(device, txCharacteristic, true, next)
        } catch (error: RuntimeException) {
            ReflectorStateStore.log("OOB indication exception: ${error.message ?: error.javaClass.simpleName}")
            BluetoothStatusCodes.ERROR_UNKNOWN
        }

        if (result == BluetoothStatusCodes.SUCCESS) {
            sending = true
        } else {
            pending.removeFirst()
            notifySendFailed()
        }
    }

    private fun notifySendFailed() {
        dispatch { it.onSendFailed() }
    }

    private fun dispatch(action: (TransportHandle.ReceiveCallback) -> Unit) {
        val executor = callbackExecutor ?: return
        val callback = receiveCallback ?: return
        executor.execute { action(callback) }
    }
}
