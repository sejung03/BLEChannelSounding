package com.example.blecsreflector.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.blecsreflector.transport.RasOobTransportHandle

class OobGattServerManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "OobGattServer"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter = bluetoothManager.adapter
    private val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var oobCharacteristic: BluetoothGattCharacteristic? = null
    private var transportHandle: RasOobTransportHandle? = null

    fun setTransportHandle(handle: RasOobTransportHandle) {
        transportHandle = handle
    }

    fun start() {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

            val service = BluetoothGattService(
                RasConstants.RAS_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            oobCharacteristic = BluetoothGattCharacteristic(
                RasConstants.RAS_OOB_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val cccd = BluetoothGattDescriptor(
                RasConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
            )

            oobCharacteristic?.addDescriptor(cccd)
            service.addCharacteristic(oobCharacteristic)

            val added = gattServer?.addService(service) ?: false
            Log.d(TAG, "RAS OOB service added: $added")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission rejected", e)
        }
    }

    fun startAdvertising() {
        if (!hasBluetoothAdvertisePermission()) {
            Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission")
            return
        }

        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(RasConstants.RAS_SERVICE_UUID))
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE permission rejected", e)
        }
    }

    fun sendOobDataToBoard(data: ByteArray) {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            transportHandle?.notifySendFailed()
            return
        }

        try {
            val server = gattServer
            val device = connectedDevice
            val characteristic = oobCharacteristic

            if (server == null || device == null || characteristic == null) {
                Log.w(TAG, "Cannot notify: GATT server, device, or characteristic is null")
                transportHandle?.notifySendFailed()
                return
            }

            val status = server.notifyCharacteristicChanged(
                device,
                characteristic,
                false,
                data
            )

            Log.d(TAG, "Notify OOB data status: $status")

            if (status != BluetoothStatusCodes.SUCCESS) {
                transportHandle?.notifySendFailed()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission rejected", e)
            transportHandle?.notifySendFailed()
        }
    }

    fun stop() {
        try {
            if (hasBluetoothAdvertisePermission()) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to stop advertising", e)
        }

        try {
            if (hasBluetoothConnectPermission()) {
                gattServer?.close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to close GATT server", e)
        }

        connectedDevice = null
        gattServer = null
        oobCharacteristic = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (!hasBluetoothConnectPermission()) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
                return
            }

            try {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        connectedDevice = device
                        Log.d(TAG, "Nordic board connected")
                        transportHandle?.notifyReconnected()
                    }

                    BluetoothGatt.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Nordic board disconnected")
                        connectedDevice = null
                        transportHandle?.notifyDisconnected()
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Connection state handling failed", e)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "OOB write from board: ${value.size} bytes")

            if (characteristic.uuid == RasConstants.RAS_OOB_CHARACTERISTIC_UUID) {
                transportHandle?.onReceiveFromBoard(value)
            }

            if (responseNeeded) {
                sendGattResponse(device, requestId, offset)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Descriptor write received")

            if (responseNeeded) {
                sendGattResponse(device, requestId, offset)
            }
        }
    }

    private fun sendGattResponse(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int
    ) {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }

        try {
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                null
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to send GATT response", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothAdvertisePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
    }
}