package com.example.blecsranger.ranging

import android.os.SystemClock
import com.example.blecsranger.model.AttemptLogRecord
import com.example.blecsranger.model.BoardMeasurementRecord
import com.example.blecsranger.model.BoardConfig
import com.example.blecsranger.model.CycleMeasurementRecord
import com.example.blecsranger.model.MeasurementResult
import com.example.blecsranger.model.RawMeasurementObservation
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CycleRangingController(
    private val source: MeasurementSource,
    private val listener: Listener,
    private val perBoardTimeoutMs: Long = 1_200L,
) : AutoCloseable {
    interface Listener {
        fun onRunningChanged(running: Boolean)
        fun onSessionReady(detail: String)
        fun onSessionError(message: String)
        fun onBoardStarted(board: BoardConfig)
        fun onBoardResult(board: BoardConfig, distanceMeters: Double, elapsedMs: Double)
        fun onBoardMeasurement(cycleNumber: Long, record: BoardMeasurementRecord)
        fun onBoardError(board: BoardConfig, message: String, elapsedMs: Double)
        fun onAttemptEvent(record: AttemptLogRecord)
        fun onCycleCompleted(record: CycleMeasurementRecord)
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val runToken = AtomicLong(0L)
    @Volatile private var running = false
    private var boards: List<BoardConfig> = emptyList()
    private var prepareRequest: MeasurementSource.Request? = null
    private var currentRequest: MeasurementSource.Request? = null
    private var timeoutTask: ScheduledFuture<*>? = null
    private var cycleStartedNs = 0L
    private var cycleStartedEpochMs = 0L
    private var boardStartedNs = 0L
    private var boardStartedEpochMs = 0L
    private var cycleNumber = 0L
    private var boardIndex = 0
    private var boardAttempt = 0
    private var attemptEventSequence = 0
    private val measurements = linkedMapOf<Int, BoardMeasurementRecord>()

    @Synchronized
    fun start(newBoards: List<BoardConfig>) {
        stopInternal(notify = false)
        boards = newBoards.sortedBy { it.index }
        running = true
        cycleNumber = 0L
        val token = runToken.incrementAndGet()
        listener.onRunningChanged(true)
        scheduler.execute {
            if (!isActive(token)) return@execute
            prepareRequest = source.prepare(
                boards = boards,
                onReady = { detail ->
                    scheduler.execute {
                        if (isActive(token)) {
                            prepareRequest = null
                            listener.onSessionReady(detail)
                            beginCycle(token)
                        }
                    }
                },
                onError = { message ->
                    scheduler.execute {
                        if (isActive(token)) {
                            running = false
                            prepareRequest = null
                            source.close()
                            listener.onSessionError(message)
                            listener.onRunningChanged(false)
                        }
                    }
                },
            )
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(notify = true)
    }

    private fun stopInternal(notify: Boolean) {
        val wasRunning = running
        running = false
        runToken.incrementAndGet()
        timeoutTask?.cancel(false)
        timeoutTask = null
        prepareRequest?.cancel()
        prepareRequest = null
        currentRequest?.cancel()
        currentRequest = null
        if (notify && wasRunning) listener.onRunningChanged(false)
    }

    private fun beginCycle(token: Long) {
        if (!isActive(token)) return
        cycleStartedNs = SystemClock.elapsedRealtimeNanos()
        cycleStartedEpochMs = System.currentTimeMillis()
        boardIndex = 0
        boardAttempt = 0
        measurements.clear()
        measureCurrentBoard(token)
    }

    private fun measureCurrentBoard(token: Long) {
        if (!isActive(token)) return
        if (boardIndex >= boards.size) {
            finishCycle(token)
            return
        }
        val board = boards[boardIndex]
        boardAttempt += 1
        attemptEventSequence = 0
        boardStartedNs = SystemClock.elapsedRealtimeNanos()
        boardStartedEpochMs = System.currentTimeMillis()
        listener.onBoardStarted(board)
        emitAttemptEvent(
            board = board,
            outcome = "ATTEMPT_STARTED",
            detail = "보드 측정 시도 시작",
        )
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        currentRequest = source.measureOnce(
            board = board,
            onObservation = { observation: RawMeasurementObservation ->
                scheduler.execute {
                    if (!settled.get() && isActive(token)) {
                        emitObservation(board, observation)
                    }
                }
            },
            onResult = { result: MeasurementResult ->
                scheduler.execute {
                    if (settled.compareAndSet(false, true) && isActive(token)) {
                        timeoutTask?.cancel(false)
                        val completedAtEpochMs = System.currentTimeMillis()
                        val completedAtElapsedMs = SystemClock.elapsedRealtime()
                        val elapsedMs = nsToMs(SystemClock.elapsedRealtimeNanos() - boardStartedNs)
                        measurements[board.index] = BoardMeasurementRecord(
                            board = board,
                            startedAtEpochMs = boardStartedEpochMs,
                            completedAtEpochMs = completedAtEpochMs,
                            measuredAtEpochMs = result.rangingTimestampElapsedMs
                                ?.let { completedAtEpochMs - completedAtElapsedMs + it },
                            elapsedMs = elapsedMs,
                            status = "OK",
                            result = result,
                            errorDetail = null,
                        )
                        listener.onBoardResult(board, result.distanceMeters, elapsedMs)
                        listener.onBoardMeasurement(cycleNumber + 1, checkNotNull(measurements[board.index]))
                        currentRequest = null
                        boardIndex += 1
                        boardAttempt = 0
                        scheduler.schedule(
                            { measureCurrentBoard(token) },
                            INTER_BOARD_GUARD_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            },
            onError = { message ->
                scheduler.execute {
                    if (settled.compareAndSet(false, true) && isActive(token)) {
                        timeoutTask?.cancel(false)
                        currentRequest = null
                        retryOrRecordFailure(token, board, "ERROR", message)
                    }
                }
            },
        )
        timeoutTask = scheduler.schedule({
            if (settled.compareAndSet(false, true) && isActive(token)) {
                currentRequest?.cancel()
                currentRequest = null
                val message = "${perBoardTimeoutMs}ms 타임아웃"
                retryOrRecordFailure(token, board, "TIMEOUT", message)
            }
        }, perBoardTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun retryOrRecordFailure(
        token: Long,
        board: BoardConfig,
        status: String,
        message: String,
    ) {
        if (!isActive(token)) return
        emitAttemptEvent(
            board = board,
            outcome = status,
            detail = message,
        )
        if (boardAttempt < MAX_ATTEMPTS_PER_BOARD) {
            emitAttemptEvent(
                board = board,
                outcome = "RETRY_SCHEDULED",
                detail = "${RETRY_GUARD_MS}ms 후 ${boardAttempt + 1}번째 시도",
            )
            scheduler.schedule(
                { measureCurrentBoard(token) },
                RETRY_GUARD_MS,
                TimeUnit.MILLISECONDS,
            )
            return
        }

        val completedAtEpochMs = System.currentTimeMillis()
        val elapsedMs = nsToMs(SystemClock.elapsedRealtimeNanos() - boardStartedNs)
        measurements[board.index] = BoardMeasurementRecord(
            board = board,
            startedAtEpochMs = boardStartedEpochMs,
            completedAtEpochMs = completedAtEpochMs,
            measuredAtEpochMs = null,
            elapsedMs = elapsedMs,
            status = status,
            result = null,
            errorDetail = "$message (${boardAttempt}회 시도)",
        )
        listener.onBoardError(board, "$message (${boardAttempt}회 시도)", elapsedMs)
        boardIndex += 1
        boardAttempt = 0
        scheduler.schedule(
            { measureCurrentBoard(token) },
            INTER_BOARD_GUARD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun emitObservation(board: BoardConfig, observation: RawMeasurementObservation) {
        val epochOffsetMs = observation.receivedAtEpochMs - SystemClock.elapsedRealtime()
        val eventAtEpochMs = observation.rangingTimestampElapsedMs
            ?.let { epochOffsetMs + it }
            ?: observation.receivedAtEpochMs
        emitAttemptEvent(
            board = board,
            outcome = observation.outcome,
            rawDistanceMeters = observation.rawDistanceMeters,
            acceptedDistanceMeters = observation.acceptedDistanceMeters,
            distanceConfidence = observation.distanceConfidence,
            distanceStandardDeviationMeters = observation.distanceStandardDeviationMeters,
            rssiDbm = observation.rssiDbm,
            detail = observation.detail,
            eventAtEpochMs = eventAtEpochMs,
        )
    }

    private fun emitAttemptEvent(
        board: BoardConfig,
        outcome: String,
        rawDistanceMeters: Double? = null,
        acceptedDistanceMeters: Double? = null,
        distanceConfidence: Int? = null,
        distanceStandardDeviationMeters: Double? = null,
        rssiDbm: Int? = null,
        detail: String? = null,
        eventAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        attemptEventSequence += 1
        listener.onAttemptEvent(
            AttemptLogRecord(
                cycleNumber = cycleNumber + 1,
                board = board,
                attemptNumber = boardAttempt,
                eventSequence = attemptEventSequence,
                attemptStartedAtEpochMs = boardStartedEpochMs,
                eventAtEpochMs = eventAtEpochMs,
                elapsedMs = nsToMs(SystemClock.elapsedRealtimeNanos() - boardStartedNs),
                outcome = outcome,
                rawDistanceMeters = rawDistanceMeters,
                acceptedDistanceMeters = acceptedDistanceMeters,
                distanceConfidence = distanceConfidence,
                distanceStandardDeviationMeters = distanceStandardDeviationMeters,
                rssiDbm = rssiDbm,
                detail = detail,
            ),
        )
    }

    private fun finishCycle(token: Long) {
        if (!isActive(token)) return
        cycleNumber += 1
        val completedAtEpochMs = System.currentTimeMillis()
        val elapsedMs = nsToMs(SystemClock.elapsedRealtimeNanos() - cycleStartedNs)
        listener.onCycleCompleted(CycleMeasurementRecord(
            cycleNumber = cycleNumber,
            startedAtEpochMs = cycleStartedEpochMs,
            completedAtEpochMs = completedAtEpochMs,
            elapsedMs = elapsedMs,
            boardMeasurements = boards.mapNotNull { measurements[it.index] },
        ))
        // Board results have already been submitted to CSV; start the next cycle.
        beginCycle(token)
    }

    private fun isActive(token: Long): Boolean = running && runToken.get() == token

    override fun close() {
        stopInternal(notify = false)
        source.close()
        scheduler.shutdownNow()
    }

    private fun nsToMs(ns: Long): Double = ns / 1_000_000.0

    companion object {
        private const val MAX_ATTEMPTS_PER_BOARD = 2
        private const val INTER_BOARD_GUARD_MS = 25L
        private const val RETRY_GUARD_MS = 100L
    }
}
