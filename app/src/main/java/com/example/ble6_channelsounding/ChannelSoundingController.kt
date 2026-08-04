package com.example.ble6_channelsounding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SessionConfig
import android.ranging.ble.cs.BleCsRangingCapabilities
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawResponderRangingConfig
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
class ChannelSoundingController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onCapability(supported: Boolean, detail: String)
        fun onSessionOpened(role: Role)
        fun onRangingStarted(role: Role)
        fun onDistance(rawMeters: Double, smoothedMeters: Double, sampleCount: Int)
        fun onRangingStopped(role: Role)
        fun onError(message: String)
        fun onClosed(reason: Int)
    }

    enum class Role { INITIATOR, REFLECTOR }

    private val rangingManager: RangingManager? =
        context.getSystemService(RangingManager::class.java)

    private var session: RangingSession? = null
    private var sessionCancellation: CancellationSignal? = null
    private var capabilityCallback: RangingManager.RangingCapabilitiesCallback? = null

    private var activeRole: Role? = null
    private var starting = false

    private val recentDistances = ArrayDeque<Double>()
    private var totalSamples = 0

    fun checkCapability() {
        if (!hasRangingPermission()) {
            listener.onCapability(false, "RANGING permission 없음")
            return
        }

        val manager = rangingManager ?: run {
            listener.onCapability(false, "RangingManager 없음")
            return
        }

        unregisterCapabilityCallback()

        val callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
            val cs = capabilities.csCapabilities
            val supported = cs != null

            val detail = if (cs == null) {
                "BLE Channel Sounding 미지원"
            } else {
                "BLE CS 지원 / security=${cs.supportedSecurityLevels}"
            }

            listener.onCapability(supported, detail)
            unregisterCapabilityCallback()
        }

        capabilityCallback = callback
        manager.registerCapabilitiesCallback(context.mainExecutor, callback)
    }

    fun startInitiator(peerAddress: String) {
        start(Role.INITIATOR, peerAddress)
    }

    fun startReflector(peerAddress: String) {
        start(Role.REFLECTOR, peerAddress)
    }

    private fun start(role: Role, peerAddress: String) {
        if (starting || session != null) {
            listener.onError("이미 CS 세션이 시작 중이거나 실행 중입니다.")
            return
        }

        if (!hasRangingPermission()) {
            listener.onError("RANGING 권한이 없습니다.")
            return
        }

        if (!BluetoothAdapter.checkBluetoothAddress(peerAddress)) {
            listener.onError("잘못된 peer Bluetooth 주소: $peerAddress")
            return
        }

        val manager = rangingManager ?: run {
            listener.onError("RangingManager를 사용할 수 없습니다.")
            return
        }

        starting = true
        activeRole = role
        recentDistances.clear()
        totalSamples = 0

        val normalizedAddress = peerAddress.uppercase(Locale.US)

        val rangingDevice = RangingDevice.Builder()
            .setUuid(UUID.nameUUIDFromBytes(normalizedAddress.toByteArray()))
            .build()

        val csParams = BleCsRangingParams.Builder(normalizedAddress)
            .setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_NORMAL)
            .setSecurityLevel(BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE)
            .setLocationType(BleCsRangingParams.LOCATION_TYPE_INDOOR)
            .build()

        val rawDevice = RawRangingDevice.Builder()
            .setRangingDevice(rangingDevice)
            .setCsRangingParams(csParams)
            .build()

        val rangingConfig = when (role) {
            Role.INITIATOR -> RawInitiatorRangingConfig.Builder()
                .addRawRangingDevice(rawDevice)
                .build()

            Role.REFLECTOR -> RawResponderRangingConfig.Builder()
                .setRawRangingDevice(rawDevice)
                .build()
        }

        val sessionConfig = SessionConfig.Builder()
            .setRangingMeasurementsLimit(1000)
            .build()

        val deviceRole = when (role) {
            Role.INITIATOR -> RangingPreference.DEVICE_ROLE_INITIATOR
            Role.REFLECTOR -> RangingPreference.DEVICE_ROLE_RESPONDER
        }

        val preference = RangingPreference.Builder(deviceRole, rangingConfig)
            .setSessionConfig(sessionConfig)
            .build()

        unregisterCapabilityCallback()

        val callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
            val cs = capabilities.csCapabilities

            if (cs == null) {
                fail("이 휴대폰은 BLE Channel Sounding을 지원하지 않습니다.")
                return@RangingCapabilitiesCallback
            }

            if (!cs.supportedSecurityLevels.contains(
                    BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE
                )
            ) {
                fail("CS Security Level 1을 지원하지 않습니다.")
                return@RangingCapabilitiesCallback
            }

            try {
                val newSession = manager.createRangingSession(
                    context.mainExecutor,
                    sessionCallback
                ) ?: run {
                    fail("RangingSession 생성 실패")
                    return@RangingCapabilitiesCallback
                }

                session = newSession

                /*
                 * addDeviceToRangingSession()은 호출하지 않습니다.
                 * RangingPreference에 포함된 raw config로 바로 시작합니다.
                 */
                sessionCancellation = newSession.start(preference)

                unregisterCapabilityCallback()
            } catch (e: Exception) {
                fail(
                    "CS 세션 시작 실패: " +
                            "${e.javaClass.simpleName}: ${e.message ?: "원인 정보 없음"}"
                )
            }
        }

        capabilityCallback = callback
        manager.registerCapabilitiesCallback(context.mainExecutor, callback)
    }

    private val sessionCallback = object : RangingSession.Callback {
        override fun onOpened() {
            starting = false
            activeRole?.let(listener::onSessionOpened)
        }

        override fun onOpenFailed(reason: Int) {
            starting = false
            fail(
                "RangingSession open 실패: " +
                        "${reasonToText(reason)}(reason=$reason)"
            )
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            activeRole?.let(listener::onRangingStarted)
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            /* Android 공개 API의 BLE CS 거리 결과는 Initiator에서 처리합니다. */
            if (activeRole != Role.INITIATOR) return

            val raw = data.distance?.measurement?.toDouble() ?: return

            totalSamples += 1
            recentDistances.addLast(raw)

            while (recentDistances.size > 5) {
                recentDistances.removeFirst()
            }

            val smoothed = recentDistances.average()
            listener.onDistance(raw, smoothed, totalSamples)
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            activeRole?.let(listener::onRangingStopped)
        }

        override fun onClosed(reason: Int) {
            listener.onClosed(reason)
            cleanup()
        }
    }

    fun stop() {
        starting = false

        try {
            session?.stop()
        } catch (_: Exception) {
        }

        try {
            session?.close()
        } catch (_: Exception) {
        }

        cleanup()
    }

    private fun fail(message: String) {
        listener.onError(message)
        stop()
    }

    private fun unregisterCapabilityCallback() {
        try {
            capabilityCallback?.let { callback ->
                rangingManager?.unregisterCapabilitiesCallback(callback)
            }
        } catch (_: Exception) {
        }

        capabilityCallback = null
    }

    private fun cleanup() {
        unregisterCapabilityCallback()
        sessionCancellation = null
        session = null
        activeRole = null
        recentDistances.clear()
        totalSamples = 0
    }

    private fun reasonToText(reason: Int): String = when (reason) {
        RangingSession.Callback.REASON_UNKNOWN -> "UNKNOWN"
        RangingSession.Callback.REASON_LOCAL_REQUEST -> "LOCAL_REQUEST"
        RangingSession.Callback.REASON_REMOTE_REQUEST -> "REMOTE_REQUEST"
        RangingSession.Callback.REASON_UNSUPPORTED -> "UNSUPPORTED"
        RangingSession.Callback.REASON_SYSTEM_POLICY -> "SYSTEM_POLICY"
        RangingSession.Callback.REASON_NO_PEERS_FOUND -> "NO_PEERS_FOUND"
        else -> "UNDEFINED"
    }

    private fun hasRangingPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RANGING
        ) == PackageManager.PERMISSION_GRANTED
}
