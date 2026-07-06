package com.example.blecsreflector.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.blecsreflector.transport.RasOobTransportHandle

@SuppressLint("MissingPermission")
class OobGattServerManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onTransportReady(transportHandle: RasOobTransportHandle)
        fun onPeerDisconnected()
    }

    companion object {
        private const val TAG = "OobGattServer"
    }

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter = bluetoothManager.adapter
    private val advertiser = adapter.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private var peerDevice: BluetoothDevice? = null
    private var transportHandle: RasOobTransportHandle? = null

    private lateinit var controlPointChar: BluetoothGattCharacteristic
    private lateinit var featuresChar: BluetoothGattCharacteristic
    private lateinit var realtimeRdChar: BluetoothGattCharacteristic
    private lateinit var ondemandRdChar: BluetoothGattCharacteristic
    private lateinit var rdReadyChar: BluetoothGattCharacteristic
    private lateinit var rdOverwrittenChar: BluetoothGattCharacteristic

    fun start() {
        startGattServer()
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)

        transportHandle?.close()
        transportHandle = null

        gattServer?.close()
        gattServer = null

        peerDevice = null

        Log.i(TAG, "OOB GATT server stopped")
    }

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, callback)

        val service = BluetoothGattService(
            RasConstants.RANGING_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        featuresChar = BluetoothGattCharacteristic(
            RasConstants.FEATURES_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        realtimeRdChar = notifyCharacteristic(
            RasConstants.REALTIME_RD_UUID
        )

        ondemandRdChar = BluetoothGattCharacteristic(
            RasConstants.ONDEMAND_RD_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(cccd())
        }

        controlPointChar = BluetoothGattCharacteristic(
            RasConstants.CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(cccd())
        }

        rdReadyChar = notifyCharacteristic(
            RasConstants.RD_READY_UUID
        )

        rdOverwrittenChar = notifyCharacteristic(
            RasConstants.RD_OVERWRITTEN_UUID
        )

        service.addCharacteristic(featuresChar)
        service.addCharacteristic(realtimeRdChar)
        service.addCharacteristic(ondemandRdChar)
        service.addCharacteristic(controlPointChar)
        service.addCharacteristic(rdReadyChar)
        service.addCharacteristic(rdOverwrittenChar)

        gattServer?.addService(service)

        val server = gattServer ?: return

        transportHandle = RasOobTransportHandle(
            gattServer = server,
            peerProvider = { peerDevice },
            txCharacteristicProvider = { controlPointChar }
        )

        listener.onTransportReady(transportHandle!!)

        Log.i(TAG, "RAS GATT server starting")
    }

    private fun notifyCharacteristic(
        uuid: java.util.UUID
    ): BluetoothGattCharacteristic {
        return BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(cccd())
        }
    }

    private fun cccd(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            RasConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
        )
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .setAdvertiseMode(
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(
                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(
                ParcelUuid(RasConstants.RANGING_SERVICE_UUID)
            )
            .build()

        advertiser?.startAdvertising(
            settings,
            data,
            advertiseCallback
        )
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) {
                Log.i(TAG, "RAS advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "RAS advertising failed: $errorCode")
            }
        }

    private val callback =
        object : BluetoothGattServerCallback() {

            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService
            ) {
                Log.i(
                    TAG,
                    "onServiceAdded status=$status uuid=${service.uuid}"
                )

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    Log.e(TAG, "RAS service add failed: $status")
                }
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    peerDevice = device
                    Log.i(TAG, "Nordic connected: ${device.address}")
                    transportHandle?.notifyReconnected()
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Nordic disconnected")
                    transportHandle?.notifyDisconnected()
                    peerDevice = null
                    listener.onPeerDisconnected()
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                when (characteristic.uuid) {
                    RasConstants.FEATURES_UUID -> {
                        val features =
                            RasConstants.RAS_FEATURE_REALTIME_RD

                        val value = byteArrayOf(
                            (features and 0xFF).toByte(),
                            ((features shr 8) and 0xFF).toByte(),
                            ((features shr 16) and 0xFF).toByte(),
                            ((features shr 24) and 0xFF).toByte()
                        )

                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            value
                        )

                        Log.i(TAG, "RAS Features read")
                    }

                    else -> {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            byteArrayOf()
                        )
                    }
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
                Log.i(
                    TAG,
                    "WRITE uuid=${characteristic.uuid}, size=${value.size}, data=${value.joinToString(" ") { "%02X".format(it) }}"
                )

                transportHandle?.onDataReceivedFromPeer(value)

                if (characteristic.uuid == RasConstants.CONTROL_POINT_UUID) {
                    transportHandle?.onDataReceivedFromPeer(value)
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
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
                Log.i(TAG, "Descriptor write: ${descriptor.uuid}")

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
            }

            override fun onNotificationSent(
                device: BluetoothDevice,
                status: Int
            ) {
                Log.i(TAG, "Notification sent status=$status device=${device.address}")
            }
        }
}