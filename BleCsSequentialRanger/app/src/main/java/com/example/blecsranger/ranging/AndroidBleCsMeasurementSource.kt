package com.example.blecsranger.ranging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.SensorFusionParams
import android.ranging.SessionConfig
import android.ranging.RangingSession
import android.ranging.ble.cs.BleCsRangingCapabilities
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import com.example.blecsranger.model.BoardConfig
import com.example.blecsranger.model.MeasurementResult
import com.example.blecsranger.model.RawMeasurementObservation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Compatibility-first one-peer sessions, proven stable with the target phone and reflectors. */
class AndroidBleCsMeasurementSource(context: Context) : MeasurementSource {
    private val appContext = context.applicationContext
    private val rangingManager = appContext.getSystemService(RangingManager::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val requests = mutableSetOf<SessionRequest>()

    override fun prepare(
        boards: List<BoardConfig>,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
    ): MeasurementSource.Request {
        if (appContext.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) {
            onError("RANGING 권한이 없습니다")
            return EmptyRequest
        }
        onReady("안정 순차 모드 · RP별 BLE CS 세션")
        return EmptyRequest
    }

    override fun measureOnce(
        board: BoardConfig,
        onObservation: (RawMeasurementObservation) -> Unit,
        onResult: (MeasurementResult) -> Unit,
        onError: (String) -> Unit,
    ): MeasurementSource.Request {
        if (appContext.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) {
            onError("RANGING 권한이 없습니다")
            return EmptyRequest
        }

        val request = SessionRequest(onObservation, onResult, onError)
        synchronized(requests) { requests += request }
        try {
            val session = rangingManager.createRangingSession(executor, request.callback)
                ?: error("RangingSession을 만들 수 없습니다")
            request.attach(session)

            val rangingDevice = RangingDevice.Builder()
                .setUuid(board.rangingId)
                .build()
            val csParams = BleCsRangingParams.Builder(board.macAddress)
                .setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_FREQUENT)
                .setLocationType(BleCsRangingParams.LOCATION_TYPE_INDOOR)
                .setSightType(BleCsRangingParams.SIGHT_TYPE_UNKNOWN)
                .setSecurityLevel(BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE)
                .build()
            val rawDevice = RawRangingDevice.Builder()
                .setRangingDevice(rangingDevice)
                .setCsRangingParams(csParams)
                .build()
            val config = RawInitiatorRangingConfig.Builder()
                .addRawRangingDevice(rawDevice)
                .build()
            val sessionConfig = SessionConfig.Builder()
                .setRangingMeasurementsLimit(0)
                .setAngleOfArrivalNeeded(false)
                .setSensorFusionParams(
                    SensorFusionParams.Builder()
                        .setSensorFusionEnabled(true)
                        .build(),
                )
                .build()
            val preference = RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_INITIATOR,
                config,
            )
                .setSessionConfig(sessionConfig)
                .build()
            session.start(preference)
        } catch (error: Throwable) {
            request.fail(error.message ?: error.javaClass.simpleName)
        }
        return request
    }

    private inner class SessionRequest(
        private val onObservation: (RawMeasurementObservation) -> Unit,
        private val onResult: (MeasurementResult) -> Unit,
        private val onError: (String) -> Unit,
    ) : MeasurementSource.Request {
        private val finished = AtomicBoolean(false)
        @Volatile private var session: RangingSession? = null
        private var invalidResultCount = 0

        val callback = object : RangingSession.Callback {
            override fun onOpened() = Unit

            override fun onOpenFailed(reason: Int) {
                fail("BLE CS 세션 열기 실패(reason=$reason)")
            }

            override fun onStarted(peer: RangingDevice, technology: Int) = Unit

            override fun onStopped(peer: RangingDevice, technology: Int) = Unit

            override fun onClosed(reason: Int) {
                if (!finished.get()) fail("BLE CS 세션 종료(reason=$reason)")
            }

            override fun onResults(peer: RangingDevice, data: RangingData) {
                val distance = data.distance
                val standardDeviation = readDistanceStandardDeviation(data)
                val rssi = if (data.hasRssi()) data.rssi else null
                if (distance == null) {
                    invalidResultCount += 1
                    onObservation(
                        RawMeasurementObservation(
                            receivedAtEpochMs = System.currentTimeMillis(),
                            rangingTimestampElapsedMs = data.timestampMillis,
                            outcome = "MISSING_DISTANCE",
                            rawDistanceMeters = null,
                            acceptedDistanceMeters = null,
                            distanceConfidence = null,
                            distanceStandardDeviationMeters = standardDeviation,
                            rssiDbm = rssi,
                            detail = "RangingData.distance가 null",
                        ),
                    )
                    if (invalidResultCount >= MAX_INVALID_RESULTS) {
                        fail("거리값 누락 반복")
                    }
                    return
                }
                val distanceMeters = distance.measurement
                if (!distanceMeters.isFinite() ||
                    distanceMeters < 0.0 ||
                    distanceMeters > MAX_VALID_DISTANCE_METERS
                ) {
                    invalidResultCount += 1
                    onObservation(
                        RawMeasurementObservation(
                            receivedAtEpochMs = System.currentTimeMillis(),
                            rangingTimestampElapsedMs = data.timestampMillis,
                            outcome = "INVALID_DISTANCE",
                            rawDistanceMeters = distanceMeters,
                            acceptedDistanceMeters = null,
                            distanceConfidence = distance.confidence,
                            distanceStandardDeviationMeters = standardDeviation,
                            rssiDbm = rssi,
                            detail = "허용 범위 0~$MAX_VALID_DISTANCE_METERS m 밖의 값",
                        ),
                    )
                    if (invalidResultCount >= MAX_INVALID_RESULTS) {
                        fail("비정상 거리값 반복(마지막 값=$distanceMeters m)")
                    }
                    return
                }
                onObservation(
                    RawMeasurementObservation(
                        receivedAtEpochMs = System.currentTimeMillis(),
                        rangingTimestampElapsedMs = data.timestampMillis,
                        outcome = "VALID_DISTANCE",
                        rawDistanceMeters = distanceMeters,
                        acceptedDistanceMeters = distanceMeters,
                        distanceConfidence = distance.confidence,
                        distanceStandardDeviationMeters = standardDeviation,
                        rssiDbm = rssi,
                        detail = null,
                    ),
                )
                succeed(
                    MeasurementResult(
                        distanceMeters = distanceMeters,
                        distanceConfidence = distance.confidence,
                        distanceStandardDeviationMeters = standardDeviation,
                        rssiDbm = rssi,
                        rangingTechnology = data.rangingTechnology,
                        rangingTimestampElapsedMs = data.timestampMillis,
                        azimuthDegrees = data.azimuth?.measurement,
                        azimuthConfidence = data.azimuth?.confidence,
                        elevationDegrees = data.elevation?.measurement,
                        elevationConfidence = data.elevation?.confidence,
                    ),
                )
            }
        }

        fun attach(value: RangingSession) {
            session = value
            if (finished.get()) releaseSession()
        }

        fun succeed(result: MeasurementResult) {
            if (!finished.compareAndSet(false, true)) return
            releaseSession()
            synchronized(requests) { requests -= this }
            onResult(result)
        }

        fun fail(message: String) {
            if (!finished.compareAndSet(false, true)) return
            releaseSession()
            synchronized(requests) { requests -= this }
            onError(message)
        }

        override fun cancel() {
            if (!finished.compareAndSet(false, true)) return
            releaseSession()
            synchronized(requests) { requests -= this }
        }

        private fun releaseSession() {
            val active = session ?: return
            session = null
            runCatching { active.stop() }
            runCatching { active.close() }
        }
    }

    override fun close() {
        val snapshot = synchronized(requests) { requests.toList() }
        snapshot.forEach { it.cancel() }
        executor.shutdownNow()
    }

    private fun readDistanceStandardDeviation(data: RangingData): Double? = runCatching {
        val hasValue = data.javaClass
            .getMethod("hasDistanceStandardDeviation")
            .invoke(data) as Boolean
        if (!hasValue) return@runCatching null
        (data.javaClass
            .getMethod("getDistanceStandardDeviationMeters")
            .invoke(data) as Double)
            .takeIf { it.isFinite() && it >= 0.0 }
    }.getOrNull()

    private object EmptyRequest : MeasurementSource.Request {
        override fun cancel() = Unit
    }

    companion object {
        private const val MAX_VALID_DISTANCE_METERS = 100.0
        private const val MAX_INVALID_RESULTS = 3
    }
}
