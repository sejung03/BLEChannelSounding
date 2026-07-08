package com.example.blecsreflector.ranging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.oob.DeviceHandle
import android.ranging.oob.OobResponderRangingConfig
import android.ranging.oob.TransportHandle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.UUID

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
class RangingController(
    private val context: Context,
    private val transportHandle: TransportHandle
) {
    companion object {
        private const val TAG = "RangingController"
    }

    private val rangingManager: RangingManager? =
        context.getSystemService(RangingManager::class.java)

    private var session: RangingSession? = null

    fun startReflector() {
        if (rangingManager == null) {
            Log.e(TAG, "RangingManager is not available")
            return
        }

        if (!hasRangingPermission()) {
            Log.e(TAG, "Missing RANGING permission")
            return
        }

        if (session != null) {
            Log.w(TAG, "Reflector session already exists")
            return
        }

        val rangingDevice = RangingDevice.Builder()
            .setUuid(UUID.randomUUID())
            .build()

        val deviceHandle = DeviceHandle.Builder(
            rangingDevice,
            transportHandle
        ).build()

        val responderConfig = OobResponderRangingConfig.Builder(deviceHandle)
            .build()

        val rangingPreference = RangingPreference.Builder(
            RangingPreference.DEVICE_ROLE_RESPONDER,
            responderConfig
        ).build()

        session = rangingManager.createRangingSession(
            context.mainExecutor,
            object : RangingSession.Callback {
                override fun onOpened() {
                    Log.d(TAG, "Reflector ranging session opened")
                    session?.start(rangingPreference)
                }

                override fun onOpenFailed(reason: Int) {
                    Log.e(TAG, "Ranging session open failed: $reason")
                    session = null
                }

                override fun onStarted(peer: RangingDevice, technology: Int) {
                    Log.d(TAG, "CS procedure started by Nordic initiator")
                }

                override fun onResults(peer: RangingDevice, data: RangingData) {
                    Log.d(TAG, "Ranging result: $data")
                }

                override fun onStopped(peer: RangingDevice, technology: Int) {
                    Log.d(TAG, "CS procedure stopped")
                }

                override fun onClosed(reason: Int) {
                    Log.d(TAG, "Ranging session closed: $reason")
                    session = null
                }
            }
        )
    }

    fun stopReflector() {
        try {
            session?.stop()
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop reflector session", e)
        } finally {
            session = null
        }
    }

    private fun hasRangingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RANGING
        ) == PackageManager.PERMISSION_GRANTED
    }
}