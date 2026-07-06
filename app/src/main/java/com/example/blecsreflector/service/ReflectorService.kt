package com.example.blecsreflector.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.blecsreflector.R
import com.example.blecsreflector.ble.OobGattServerManager
import com.example.blecsreflector.ranging.RangingController
import com.example.blecsreflector.transport.RasOobTransportHandle

class ReflectorService : Service(), OobGattServerManager.Listener {

    companion object {
        private const val TAG = "ReflectorService"

        private const val CHANNEL_ID = "ble_cs_reflector"
        private const val CHANNEL_NAME = "BLE CS Reflector"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.blecsreflector.action.START"
        const val ACTION_STOP = "com.example.blecsreflector.action.STOP"
    }

    private var oobGattServerManager: OobGattServerManager? = null
    private var rangingController: RangingController? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopReflector()
                stopSelf()
            }

            ACTION_START, null -> {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Waiting for Nordic Initiator")
                )

                startReflector()
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startReflector() {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing permissions. Cannot start reflector.")
            stopSelf()
            return
        }

        if (oobGattServerManager != null) {
            Log.w(TAG, "Reflector already started")
            return
        }

        Log.i(TAG, "Starting OOB GATT server")

        oobGattServerManager = OobGattServerManager(
            context = this,
            listener = this
        )

        oobGattServerManager?.start()
    }

    private fun stopReflector() {
        Log.i(TAG, "Stopping reflector")

        rangingController?.stopResponder()
        rangingController = null

        oobGattServerManager?.stop()
        oobGattServerManager = null

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTransportReady(
        transportHandle: RasOobTransportHandle
    ) {
        Log.i(TAG, "TransportHandle ready. Starting ranging responder.")

        if (rangingController != null) {
            Log.w(TAG, "RangingController already running")
            return
        }

        rangingController = RangingController(
            context = this,
            transportHandle = transportHandle
        )

        rangingController?.startResponder()
    }

    override fun onPeerDisconnected() {
        Log.i(TAG, "Peer disconnected")

        rangingController?.stopResponder()
        rangingController = null
    }

    override fun onDestroy() {
        stopReflector()

        Log.i(TAG, "Service destroyed")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.RANGING
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createNotification(): Notification {
        return createNotification("Running")
    }

    private fun createNotification(
        text: String
    ): Notification {
        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BLE CS Reflector")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(
            NotificationManager::class.java
        )

        manager.createNotificationChannel(channel)
    }
}