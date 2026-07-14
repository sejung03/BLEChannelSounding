package com.example.blecsreflector.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.oob.DeviceHandle
import android.ranging.oob.OobResponderRangingConfig
import androidx.core.app.NotificationCompat
import com.example.blecsreflector.R
import com.example.blecsreflector.RangingSessionState
import com.example.blecsreflector.ReflectorStateStore
import com.example.blecsreflector.ranging.CsCapabilityMonitor
import java.nio.charset.StandardCharsets
import java.util.UUID

@SuppressLint("MissingPermission")
class ReflectorService : Service() {
    private lateinit var bluetoothManager: BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var currentDevice: BluetoothDevice? = null
    private var transport: GattOobTransport? = null
    private var rangingSession: RangingSession? = null
    private var indicationsEnabled = false
    private var receiversRegistered = false
    private lateinit var capabilityMonitor: CsCapabilityMonitor

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val enabled = adapter?.isEnabled == true
                    ReflectorStateStore.update { it.copy(bluetoothEnabled = enabled) }
                    ReflectorStateStore.log("Bluetooth ${if (enabled) "enabled" else "disabled"}")
                    if (enabled) startBluetoothStack() else stopBluetoothStack()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDevice() ?: return
                    if (device.address != currentDevice?.address) return
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    ReflectorStateStore.update { it.copy(bondState = bondState) }
                    ReflectorStateStore.log("Bond state: ${bondStateName(bondState)}")
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        maybeStartResponderSession()
                    } else if (bondState == BluetoothDevice.BOND_NONE) {
                        stopRangingSession(RangingSessionState.WAITING_FOR_BOND)
                    }
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            ReflectorStateStore.update { it.copy(advertising = true) }
            ReflectorStateStore.log("Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            ReflectorStateStore.update { it.copy(advertising = false) }
            ReflectorStateStore.log("Advertising failed: code $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != ReflectorGattContract.SERVICE_UUID) return
            val ready = status == BluetoothGatt.GATT_SUCCESS
            ReflectorStateStore.update { it.copy(gattReady = ready) }
            ReflectorStateStore.log("GATT service ${if (ready) "ready" else "failed: $status"}")
            if (ready) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> handleConnected(device)
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected(device, status)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (device.address != currentDevice?.address) return
            ReflectorStateStore.update { it.copy(mtu = mtu) }
            ReflectorStateStore.log("ATT MTU: $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != ReflectorGattContract.STATUS_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val value = statusPayload()
            if (offset > value.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value.copyOfRange(offset, value.size)
                )
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
            val supported = characteristic.uuid == ReflectorGattContract.RX_UUID && !preparedWrite && offset == 0
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (supported) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null
                )
            }
            if (!supported || device.address != currentDevice?.address) return

            maybeStartResponderSession()
            transport?.receiveFromPeer(value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid != ReflectorGattContract.CCCD_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val value = if (indicationsEnabled) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            if (offset > value.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value.copyOfRange(offset, value.size)
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
            val supported = descriptor.uuid == ReflectorGattContract.CCCD_UUID && !preparedWrite && offset == 0
            if (!supported) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null
                    )
                }
                return
            }

            indicationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            transport?.setSubscribed(indicationsEnabled)
            ReflectorStateStore.update { it.copy(indicationsEnabled = indicationsEnabled) }
            ReflectorStateStore.log("OOB indications ${if (indicationsEnabled) "enabled" else "disabled"}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (indicationsEnabled) {
                maybeStartResponderSession()
            } else {
                stopRangingSession(RangingSessionState.WAITING_FOR_INDICATIONS)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (device.address == currentDevice?.address) {
                transport?.onNotificationSent(status)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        capabilityMonitor = CsCapabilityMonitor(this)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        registerSystemReceivers()
        capabilityMonitor.start()

        val enabled = adapter?.isEnabled == true
        ReflectorStateStore.update {
            it.copy(
                serviceRunning = true,
                bluetoothEnabled = enabled,
                sessionState = RangingSessionState.IDLE
            )
        }
        ReflectorStateStore.log("Reflector service started")
        if (enabled) startBluetoothStack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (adapter?.isEnabled == true && gattServer == null) {
            startBluetoothStack()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capabilityMonitor.stop()
        stopBluetoothStack()
        unregisterSystemReceivers()
        ReflectorStateStore.update {
            it.copy(
                serviceRunning = false,
                advertising = false,
                gattReady = false,
                connectedDeviceName = null,
                connectedDeviceAddress = null,
                bondState = null,
                indicationsEnabled = false,
                sessionState = RangingSessionState.IDLE,
                mtu = 23
            )
        }
        ReflectorStateStore.log("Reflector service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerSystemReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
        receiversRegistered = true
    }

    private fun unregisterSystemReceivers() {
        if (!receiversRegistered) return
        unregisterReceiver(systemReceiver)
        receiversRegistered = false
    }

    private fun hasBluetoothPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBluetoothStack() {
        if (gattServer != null || !hasBluetoothPermissions()) return
        val bluetoothAdapter = adapter ?: run {
            ReflectorStateStore.log("Bluetooth adapter unavailable")
            return
        }
        if (!bluetoothAdapter.isEnabled) return

        val server = bluetoothManager.openGattServer(this, gattCallback)
        if (server == null) {
            ReflectorStateStore.log("Unable to open GATT server")
            return
        }
        gattServer = server

        val service = createGattService()
        if (!server.addService(service)) {
            ReflectorStateStore.log("Unable to queue GATT service")
            server.close()
            gattServer = null
        }
    }

    private fun stopBluetoothStack() {
        stopRangingSession(RangingSessionState.IDLE)
        transport?.close()
        transport = null
        currentDevice = null
        indicationsEnabled = false
        stopAdvertising()
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        rxCharacteristic = null
        txCharacteristic = null
        statusCharacteristic = null
        ReflectorStateStore.update {
            it.copy(
                advertising = false,
                gattReady = false,
                connectedDeviceName = null,
                connectedDeviceAddress = null,
                bondState = null,
                indicationsEnabled = false,
                sessionState = RangingSessionState.IDLE,
                mtu = 23
            )
        }
    }

    private fun createGattService(): BluetoothGattService {
        val service = BluetoothGattService(
            ReflectorGattContract.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        rxCharacteristic = BluetoothGattCharacteristic(
            ReflectorGattContract.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        ).also(service::addCharacteristic)

        txCharacteristic = BluetoothGattCharacteristic(
            ReflectorGattContract.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).also { characteristic ->
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    ReflectorGattContract.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                        BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
            service.addCharacteristic(characteristic)
        }

        statusCharacteristic = BluetoothGattCharacteristic(
            ReflectorGattContract.STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).also(service::addCharacteristic)

        return service
    }

    private fun startAdvertising() {
        val bluetoothAdapter = adapter ?: return
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            ReflectorStateStore.log("BLE advertising is unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(ReflectorGattContract.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: RuntimeException) {
            // Bluetooth can disappear while the stack is being torn down.
        }
    }

    private fun handleConnected(device: BluetoothDevice) {
        val existing = currentDevice
        if (existing != null && existing.address != device.address) {
            gattServer?.cancelConnection(device)
            ReflectorStateStore.log("Rejected second GATT peer: ${device.address}")
            return
        }

        currentDevice = device
        indicationsEnabled = false
        val tx = txCharacteristic ?: return
        val server = gattServer ?: return
        transport = GattOobTransport(this, server, device, tx)

        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        val bondState = device.bondState
        ReflectorStateStore.update {
            it.copy(
                connectedDeviceName = name,
                connectedDeviceAddress = device.address,
                bondState = bondState,
                indicationsEnabled = false,
                sessionState = if (bondState == BluetoothDevice.BOND_BONDED) {
                    RangingSessionState.WAITING_FOR_INDICATIONS
                } else {
                    RangingSessionState.WAITING_FOR_BOND
                }
            )
        }
        ReflectorStateStore.log("GATT connected: ${name ?: "Nordic"} ${device.address}")
        updateNotification(true)

        if (bondState == BluetoothDevice.BOND_NONE) {
            val started = device.createBond()
            ReflectorStateStore.log("Bond request ${if (started) "started" else "not started"}")
        }
    }

    private fun handleDisconnected(device: BluetoothDevice, status: Int) {
        if (device.address != currentDevice?.address) return
        ReflectorStateStore.log("GATT disconnected: status $status")
        transport?.onDisconnected()
        stopRangingSession(RangingSessionState.IDLE)
        transport?.close()
        transport = null
        currentDevice = null
        indicationsEnabled = false
        ReflectorStateStore.update {
            it.copy(
                connectedDeviceName = null,
                connectedDeviceAddress = null,
                bondState = null,
                indicationsEnabled = false,
                sessionState = RangingSessionState.IDLE,
                mtu = 23
            )
        }
        updateNotification(false)
    }

    private fun maybeStartResponderSession() {
        val device = currentDevice ?: return
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.WAITING_FOR_BOND) }
            return
        }
        if (!indicationsEnabled) {
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.WAITING_FOR_INDICATIONS) }
            return
        }
        if (rangingSession != null) return
        if (checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) {
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.FAILED) }
            ReflectorStateStore.log("RANGING permission is missing")
            return
        }
        startApi36Responder(device)
    }

    private fun startApi36Responder(device: BluetoothDevice) {
        val oobTransport = ensureTransport(device) ?: return
        val manager = getSystemService(RangingManager::class.java)
        val peerId = UUID.nameUUIDFromBytes(device.address.toByteArray(StandardCharsets.UTF_8))
        val rangingDevice = RangingDevice.Builder().setUuid(peerId).build()
        val deviceHandle = DeviceHandle.Builder(rangingDevice, oobTransport).build()
        val config = OobResponderRangingConfig.Builder(deviceHandle).build()
        val preference = RangingPreference.Builder(
            RangingPreference.DEVICE_ROLE_RESPONDER,
            config
        ).build()

        try {
            val session = manager.createRangingSession(mainExecutor, rangingCallback)
            if (session == null) {
                ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.FAILED) }
                ReflectorStateStore.log("Ranging service did not create a responder session")
                return
            }
            rangingSession = session
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.OPENING) }
            ReflectorStateStore.log("Opening Android Ranging responder session")
            session.start(preference)
        } catch (error: RuntimeException) {
            rangingSession = null
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.FAILED) }
            ReflectorStateStore.log("Responder session failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private val rangingCallback = object : RangingSession.Callback {
        override fun onOpened() {
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.OPEN) }
            ReflectorStateStore.log("Ranging responder session open")
        }

        override fun onOpenFailed(reason: Int) {
            rangingSession = null
            resetTransport()
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.FAILED) }
            ReflectorStateStore.log("Ranging session open failed: ${rangingReason(reason)}")
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.ACTIVE) }
            ReflectorStateStore.log("Ranging started: ${technologyName(technology)}")
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            ReflectorStateStore.log("Ranging data received: ${technologyName(data.rangingTechnology)}")
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            ReflectorStateStore.update { it.copy(sessionState = RangingSessionState.OPEN) }
            ReflectorStateStore.log("Ranging stopped: ${technologyName(technology)}")
        }

        override fun onClosed(reason: Int) {
            rangingSession = null
            resetTransport()
            val next = if (currentDevice == null) {
                RangingSessionState.CLOSED
            } else if (indicationsEnabled) {
                RangingSessionState.CLOSED
            } else {
                RangingSessionState.WAITING_FOR_INDICATIONS
            }
            ReflectorStateStore.update { it.copy(sessionState = next) }
            ReflectorStateStore.log("Ranging session closed: ${rangingReason(reason)}")
        }
    }

    private fun ensureTransport(device: BluetoothDevice): GattOobTransport? {
        transport?.takeIf { it.isUsable() }?.let { return it }
        val server = gattServer ?: return null
        val tx = txCharacteristic ?: return null
        return GattOobTransport(this, server, device, tx).also {
            it.setSubscribed(indicationsEnabled)
            transport = it
        }
    }

    private fun resetTransport() {
        transport?.close()
        transport = null
    }

    private fun stopRangingSession(nextState: RangingSessionState) {
        val session = rangingSession
        rangingSession = null
        if (session != null) {
            try {
                session.stop()
            } catch (_: RuntimeException) {
                // Session may already have closed through a remote request.
            }
        }
        ReflectorStateStore.update { it.copy(sessionState = nextState) }
    }

    private fun statusPayload(): ByteArray {
        val state = ReflectorStateStore.snapshot()
        val payload = "CS-RSP;bond=${if (state.bondState == BluetoothDevice.BOND_BONDED) 1 else 0};oob=${if (state.indicationsEnabled) 1 else 0}"
        return payload.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(connected: Boolean): Notification {
        val launchIntent = Intent(this, com.example.blecsreflector.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reflector)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    if (connected) R.string.notification_connected else R.string.notification_waiting
                )
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(connected: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(connected))
    }

    private fun technologyName(technology: Int): String = when (technology) {
        RangingManager.BLE_CS -> "BLE CS"
        RangingManager.BLE_RSSI -> "BLE RSSI"
        RangingManager.UWB -> "UWB"
        RangingManager.WIFI_NAN_RTT -> "Wi-Fi NAN RTT"
        else -> "technology $technology"
    }

    private fun rangingReason(reason: Int): String = when (reason) {
        RangingSession.Callback.REASON_LOCAL_REQUEST -> "local request"
        RangingSession.Callback.REASON_REMOTE_REQUEST -> "remote request"
        RangingSession.Callback.REASON_UNSUPPORTED -> "unsupported"
        RangingSession.Callback.REASON_SYSTEM_POLICY -> "system policy"
        RangingSession.Callback.REASON_NO_PEERS_FOUND -> "no peers found"
        else -> "unknown ($reason)"
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        else -> "not bonded"
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? {
        return getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    }

    companion object {
        private const val ACTION_START = "com.example.blecsreflector.action.START"
        private const val ACTION_STOP = "com.example.blecsreflector.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "ble_cs_reflector"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ReflectorService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReflectorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
