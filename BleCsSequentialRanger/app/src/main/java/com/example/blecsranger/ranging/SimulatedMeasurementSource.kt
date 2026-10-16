package com.example.blecsranger.ranging

import android.os.SystemClock
import com.example.blecsranger.model.BoardConfig
import com.example.blecsranger.model.MeasurementResult
import com.example.blecsranger.model.RawMeasurementObservation
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class SimulatedMeasurementSource : MeasurementSource {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val random = Random()
    private val startedAtMs = SystemClock.elapsedRealtime()

    override fun prepare(
        boards: List<BoardConfig>,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
    ): MeasurementSource.Request {
        onReady("시뮬레이션 준비 완료")
        return object : MeasurementSource.Request {
            override fun cancel() = Unit
        }
    }

    override fun measureOnce(
        board: BoardConfig,
        onObservation: (RawMeasurementObservation) -> Unit,
        onResult: (MeasurementResult) -> Unit,
        onError: (String) -> Unit,
    ): MeasurementSource.Request {
        val delayMs = 75L + random.nextInt(45)
        val task: ScheduledFuture<*> = scheduler.schedule({
            val seconds = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
            val x = 2.0 + 0.85 * cos(seconds * 0.22)
            val y = 2.0 + 0.70 * sin(seconds * 0.27)
            val anchors = arrayOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0)
            val (anchorX, anchorY) = anchors.getOrElse(board.index) { 0.0 to 0.0 }
            val noise = random.nextGaussian() * 0.025
            val result = MeasurementResult(
                distanceMeters = (hypot(x - anchorX, y - anchorY) + noise)
                    .coerceAtLeast(0.0),
                distanceConfidence = 2,
                rssiDbm = -55 - random.nextInt(12),
                rangingTimestampElapsedMs = SystemClock.elapsedRealtime(),
            )
            onObservation(
                RawMeasurementObservation(
                    receivedAtEpochMs = System.currentTimeMillis(),
                    rangingTimestampElapsedMs = result.rangingTimestampElapsedMs,
                    outcome = "VALID_DISTANCE",
                    rawDistanceMeters = result.distanceMeters,
                    acceptedDistanceMeters = result.distanceMeters,
                    distanceConfidence = result.distanceConfidence,
                    distanceStandardDeviationMeters = result.distanceStandardDeviationMeters,
                    rssiDbm = result.rssiDbm,
                    detail = null,
                ),
            )
            onResult(result)
        }, delayMs, TimeUnit.MILLISECONDS)
        return object : MeasurementSource.Request {
            override fun cancel() {
                task.cancel(false)
            }
        }
    }

    override fun close() {
        scheduler.shutdownNow()
    }
}
