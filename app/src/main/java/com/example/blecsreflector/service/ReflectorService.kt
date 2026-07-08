package com.example.blecsreflector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.blecsreflector.R
import com.example.blecsreflector.ble.OobGattServerManager
import com.example.blecsreflector.ranging.RangingController
import com.example.blecsreflector.transport.RasOobTransportHandle

class ReflectorService : Service() {

    companion object {
        const val ACTION_START = "com.example.blecsreflector.ACTION_START"
        const val ACTION_STOP = "com.example.blecsreflector.ACTION_STOP"
        private const val CHANNEL_ID = "ble_cs_reflector"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var gattServerManager: OobGattServerManager
    private lateinit var transportHandle: RasOobTransportHandle
    private lateinit var rangingController: RangingController

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        gattServerManager = OobGattServerManager(this)
        transportHandle = RasOobTransportHandle(gattServerManager)
        rangingController = RangingController(this, transportHandle)

        gattServerManager.setTransportHandle(transportHandle)

        gattServerManager.start()
        gattServerManager.startAdvertising()

        rangingController.startReflector()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            rangingController.stopReflector()
        }

        transportHandle.close()
        gattServerManager.stop()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE CS Reflector",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE CS Reflector")
            .setContentText("Waiting for Nordic CS Initiator")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}