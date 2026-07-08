package com.example.blecsreflector.transport

import android.ranging.oob.TransportHandle
import android.util.Log
import com.example.blecsreflector.ble.OobGattServerManager
import java.util.concurrent.Executor

class RasOobTransportHandle(
    private val gattServerManager: OobGattServerManager
) : TransportHandle {

    companion object {
        private const val TAG = "RasOobTransport"
    }

    private var receiveCallback: TransportHandle.ReceiveCallback? = null
    private var callbackExecutor: Executor? = null

    override fun registerReceiveCallback(
        executor: Executor,
        callback: TransportHandle.ReceiveCallback
    ) {
        Log.d(TAG, "ReceiveCallback registered")
        callbackExecutor = executor
        receiveCallback = callback
    }

    override fun sendData(data: ByteArray) {
        Log.d(TAG, "sendData to Nordic board: ${data.size} bytes")
        gattServerManager.sendOobDataToBoard(data)
    }

    fun onReceiveFromBoard(data: ByteArray) {
        val executor = callbackExecutor
        val callback = receiveCallback

        if (executor == null || callback == null) {
            Log.w(TAG, "ReceiveCallback is not registered yet")
            return
        }

        executor.execute {
            callback.onReceiveData(data)
        }
    }

    fun notifyDisconnected() {
        callbackExecutor?.execute {
            receiveCallback?.onDisconnect()
        }
    }

    fun notifyReconnected() {
        callbackExecutor?.execute {
            receiveCallback?.onReconnect()
        }
    }

    fun notifySendFailed() {
        callbackExecutor?.execute {
            receiveCallback?.onSendFailed()
        }
    }

    override fun close() {
        Log.d(TAG, "TransportHandle closed")

        callbackExecutor?.execute {
            receiveCallback?.onClose()
        }

        receiveCallback = null
        callbackExecutor = null
    }
}