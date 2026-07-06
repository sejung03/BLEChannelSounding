package com.example.blecsreflector.ranging

import android.annotation.SuppressLint
import android.content.Context
import android.os.CancellationSignal
import android.ranging.RangingCapabilities
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.oob.DeviceHandle
import android.ranging.oob.OobResponderRangingConfig
import android.ranging.oob.TransportHandle
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

class RangingController(
    private val context: Context,
    private val transportHandle: TransportHandle
) {

    companion object {
        private const val TAG = "RangingController"

        private val PEER_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-567812345678")
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val rangingManager: RangingManager =
        context.getSystemService(RangingManager::class.java)

    private var rangingSession: RangingSession? = null
    private var cancellationSignal: CancellationSignal? = null

    private val capabilitiesCallback =
        RangingManager.RangingCapabilitiesCallback { capabilities ->
            logCapabilities(capabilities)
        }

    fun registerCapabilityLogger() {
        Log.i(TAG, "Registering RangingCapabilitiesCallback")

        rangingManager.registerCapabilitiesCallback(
            executor,
            capabilitiesCallback
        )
    }

    fun unregisterCapabilityLogger() {
        try {
            rangingManager.unregisterCapabilitiesCallback(
                capabilitiesCallback
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister capabilities callback", e)
        }
    }

    private fun logCapabilities(capabilities: RangingCapabilities) {
        Log.i(TAG, "RangingCapabilities = $capabilities")
        Log.i(TAG, "Check whether BLE_CS appears in capabilities above.")
    }

    @SuppressLint("MissingPermission")
    fun startResponder() {
        registerCapabilityLogger()

        Log.i(TAG, "Creating RangingSession")

        val session = rangingManager.createRangingSession(
            executor,
            sessionCallback
        )

        if (session == null) {
            Log.e(TAG, "Failed to create RangingSession")
            return
        }

        rangingSession = session

        val rangingDevice = RangingDevice.Builder()
            .setUuid(PEER_UUID)
            .build()

        val deviceHandle = DeviceHandle.Builder(
            rangingDevice,
            transportHandle
        ).build()

        val config = OobResponderRangingConfig.Builder(deviceHandle)
            .build()

        val preference = RangingPreference.Builder(
            RangingPreference.DEVICE_ROLE_RESPONDER,
            config
        ).build()

        Log.i(TAG, "Starting OOB responder ranging session")
        cancellationSignal = session.start(preference)
    }

    @SuppressLint("MissingPermission")
    fun stopResponder() {
        Log.i(TAG, "Stopping responder")

        cancellationSignal?.cancel()
        cancellationSignal = null

        rangingSession?.close()
        rangingSession = null

        unregisterCapabilityLogger()
    }

    private val sessionCallback = object : RangingSession.Callback {

        override fun onOpened() {
            Log.i(TAG, "Ranging session opened")
        }

        override fun onOpenFailed(reason: Int) {
            Log.e(TAG, "Ranging session open failed: $reason")
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            Log.i(TAG, "Ranging started: peer=${peer.uuid}, tech=$technology")
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            Log.i(TAG, "Ranging result: peer=${peer.uuid}, data=$data")
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            Log.i(TAG, "Ranging stopped: peer=${peer.uuid}, tech=$technology")
        }

        override fun onClosed(reason: Int) {
            Log.i(TAG, "Ranging session closed: $reason")
        }
    }
}