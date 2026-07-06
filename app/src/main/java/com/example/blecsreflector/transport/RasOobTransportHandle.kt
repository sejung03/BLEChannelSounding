package com.example.blecsreflector.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.ranging.oob.TransportHandle
import android.util.Log
import java.util.concurrent.Executor

class RasOobTransportHandle(
    private val gattServer: BluetoothGattServer,
    private val peerProvider: () -> BluetoothDevice?,
    private val txCharacteristicProvider: () -> BluetoothGattCharacteristic?
) : TransportHandle {

    companion object {
        private const val TAG = "RasOobTransport"
    }

    private var executor: Executor? = null
    private var callback: TransportHandle.ReceiveCallback? = null

    override fun registerReceiveCallback(
        executor: Executor,
        callback: TransportHandle.ReceiveCallback
    ) {
        this.executor = executor
        this.callback = callback
        Log.i(TAG, "ReceiveCallback registered")
    }

    @SuppressLint("MissingPermission")
    override fun sendData(data: ByteArray) {
        Log.i(
            TAG,
            "sendData called: size=${data.size}, data=${data.joinToString(" ") { "%02X".format(it) }}"
        )
        val peer = peerProvider()
        val txChar = txCharacteristicProvider()

        if (peer == null || txChar == null) {
            Log.e(TAG, "Cannot send OOB data. peer=$peer char=$txChar")
            notifySendFailed()
            return
        }

        val result = gattServer.notifyCharacteristicChanged(
            peer,
            txChar,
            true,
            data
        )

        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "notifyCharacteristicChanged failed: $result")
            notifySendFailed()
        } else {
            Log.d(TAG, "OOB sendData: ${data.size} bytes")
        }
    }

    fun onDataReceivedFromPeer(data: ByteArray) {
        Log.d(TAG, "OOB received: ${data.size} bytes")
        executor?.execute {
            callback?.onReceiveData(data)
        }
    }

    fun notifyDisconnected() {
        executor?.execute {
            callback?.onDisconnect()
        }
    }

    fun notifyReconnected() {
        executor?.execute {
            callback?.onReconnect()
        }
    }

    private fun notifySendFailed() {
        executor?.execute {
            callback?.onSendFailed()
        }
    }

    override fun close() {
        executor?.execute {
            callback?.onClose()
        }
        callback = null
        executor = null
    }
}