package com.example.blecsranger.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.blecsranger.model.AttemptLogRecord
import com.example.blecsranger.model.BoardMeasurementRecord
import com.example.blecsranger.model.ImuSample
import java.io.BufferedWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class CsvMeasurementLogger(context: Context) : AutoCloseable {
    data class SessionInfo(
        val cycleSummaryFileName: String,
        val attemptLogFileName: String,
        val relativePath: String,
    )

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private var activeSession: SessionTarget? = null

    private data class SessionTarget(
        val cycleSummaryUri: Uri,
        val attemptLogUri: Uri,
        val sessionId: String,
        val sourceType: String,
        val experimentCondition: String,
        var cycleSummaryWriter: BufferedWriter? = null,
        var attemptLogWriter: BufferedWriter? = null,
        var unflushedImuRows: Int = 0,
    )

    @Synchronized
    fun startSession(simulated: Boolean, experimentCondition: String): SessionInfo {
        endSession()
        val sessionId = UUID.randomUUID().toString()
        val sourceType = if (simulated) "SIMULATION" else "BLE_CS"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val cycleSummaryFileName = "ble_cs_${timestamp}_cycle_summary.csv"
        val attemptLogFileName = "ble_cs_${timestamp}_attempt_log.csv"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY_NAME"
        val cycleSummaryUri = createCsv(cycleSummaryFileName, CYCLE_SUMMARY_HEADER, relativePath)
        val attemptLogUri = try {
            createCsv(attemptLogFileName, ATTEMPT_LOG_HEADER, relativePath)
        } catch (error: Throwable) {
            resolver.delete(cycleSummaryUri, null, null)
            throw error
        }
        activeSession = SessionTarget(
            cycleSummaryUri,
            attemptLogUri,
            sessionId,
            sourceType,
            experimentCondition,
        )
        return SessionInfo(cycleSummaryFileName, attemptLogFileName, relativePath)
    }

    private fun createCsv(fileName: String, header: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val createdUri = checkNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        ) { "CSV 파일을 만들 수 없습니다" }
        try {
            resolver.openOutputStream(createdUri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append('\uFEFF')
                writer.appendLine(header)
            }
        } catch (error: Throwable) {
            resolver.delete(createdUri, null, null)
            throw error
        }
        return createdUri
    }

    @Synchronized
    fun appendBoardMeasurement(
        cycleNumber: Long,
        record: BoardMeasurementRecord,
        onCompleted: (Throwable?) -> Unit,
    ) {
        val session = synchronized(this) {
            checkNotNull(activeSession) { "CSV 기록 세션이 시작되지 않았습니다" }
        }
        writerExecutor.execute {
            val error = runCatching { writeBoardMeasurement(session, cycleNumber, record) }.exceptionOrNull()
            onCompleted(error)
        }
    }

    fun appendAttempt(
        record: AttemptLogRecord,
        onCompleted: (Throwable?) -> Unit = {},
    ) {
        val session = synchronized(this) { activeSession }
        if (session == null) {
            onCompleted(IllegalStateException("CSV 기록 세션이 시작되지 않았습니다"))
            return
        }
        writerExecutor.execute {
            val error = runCatching { writeAttempt(session, record) }.exceptionOrNull()
            onCompleted(error)
        }
    }

    fun appendImuSample(
        sample: ImuSample,
        onCompleted: (Throwable?) -> Unit = {},
    ) {
        val session = synchronized(this) { activeSession }
        if (session == null) {
            onCompleted(IllegalStateException("CSV 기록 세션이 시작되지 않았습니다"))
            return
        }
        writerExecutor.execute {
            val error = runCatching { writeImuSample(session, sample) }.exceptionOrNull()
            onCompleted(error)
        }
    }

    private fun writeBoardMeasurement(
        session: SessionTarget,
        cycleNumber: Long,
        record: BoardMeasurementRecord,
    ) {
        val result = checkNotNull(record.result) { "유효한 거리 측정값이 없습니다" }
        require(record.board.index in 0..3)
        val values = mutableListOf<Any?>(
            isoUtc(record.measuredAtEpochMs ?: record.completedAtEpochMs),
            session.sessionId,
            session.experimentCondition,
            session.sourceType,
            cycleNumber,
            null, // A complete cycle duration is not available at board measurement time.
            null, // Cycle validity cannot be determined from a single board.
        )
        for (index in 0..3) {
            values += if (index == record.board.index) record.board.macAddress else null
            values += if (index == record.board.index) formatNumber(result.distanceMeters) else null
        }
        repeat(IMU_COLUMN_COUNT) { values += null }
        val row = values.joinToString(",", postfix = "\n") { csvEscape(it?.toString().orEmpty()) }
        cycleSummaryWriter(session).apply {
            append(row)
            flush()
        }
        session.unflushedImuRows = 0
    }

    private fun writeAttempt(session: SessionTarget, record: AttemptLogRecord) {
        val values = listOf<Any?>(
            session.sessionId,
            session.experimentCondition,
            session.sourceType,
            record.cycleNumber,
            record.board.index + 1,
            record.board.label,
            record.board.macAddress,
            record.attemptNumber,
            record.eventSequence,
            isoUtc(record.attemptStartedAtEpochMs),
            isoUtc(record.eventAtEpochMs),
            formatNumber(record.elapsedMs),
            record.outcome,
            record.rawDistanceMeters?.let(::formatNumber),
            record.acceptedDistanceMeters?.let(::formatNumber),
            record.distanceConfidence,
            record.distanceStandardDeviationMeters?.let(::formatNumber),
            record.rssiDbm,
            record.detail,
        )
        val row = values.joinToString(",", postfix = "\n") { csvEscape(it?.toString().orEmpty()) }
        attemptLogWriter(session).apply {
            append(row)
            flush()
        }
    }

    private fun writeImuSample(session: SessionTarget, sample: ImuSample) {
        val values = mutableListOf<Any?>(
            isoUtc(sample.absoluteTimeEpochMs),
            session.sessionId,
            session.experimentCondition,
            "IMU_50HZ",
        )
        repeat(CYCLE_DATA_COLUMN_COUNT) { values += null }
        values.addAll(listOf(
            formatNumber(sample.accelerationX),
            formatNumber(sample.accelerationY),
            formatNumber(sample.accelerationZ),
            formatNumber(sample.gyroscopeX),
            formatNumber(sample.gyroscopeY),
            formatNumber(sample.gyroscopeZ),
            formatNumber(sample.rotationW),
            formatNumber(sample.rotationX),
            formatNumber(sample.rotationY),
            formatNumber(sample.rotationZ),
            formatNumber(sample.magneticX),
            formatNumber(sample.magneticY),
            formatNumber(sample.magneticZ),
        ))
        val writer = cycleSummaryWriter(session)
        writer.append(values.joinToString(",", postfix = "\n") { csvEscape(it?.toString().orEmpty()) })
        session.unflushedImuRows += 1
        if (session.unflushedImuRows >= IMU_FLUSH_ROW_COUNT) {
            writer.flush()
            session.unflushedImuRows = 0
        }
    }

    private fun cycleSummaryWriter(session: SessionTarget): BufferedWriter =
        session.cycleSummaryWriter ?: checkNotNull(
            resolver.openOutputStream(session.cycleSummaryUri, "wa"),
        ) { "Cycle CSV 출력 스트림을 열 수 없습니다" }
            .bufferedWriter(Charsets.UTF_8)
            .also { session.cycleSummaryWriter = it }

    private fun attemptLogWriter(session: SessionTarget): BufferedWriter =
        session.attemptLogWriter ?: checkNotNull(
            resolver.openOutputStream(session.attemptLogUri, "wa"),
        ) { "Attempt CSV 출력 스트림을 열 수 없습니다" }
            .bufferedWriter(Charsets.UTF_8)
            .also { session.attemptLogWriter = it }

    @Synchronized
    fun endSession() {
        val session = activeSession ?: return
        activeSession = null
        writerExecutor.execute {
            runCatching { session.cycleSummaryWriter?.close() }
            runCatching { session.attemptLogWriter?.close() }
            session.cycleSummaryWriter = null
            session.attemptLogWriter = null
        }
    }

    override fun close() {
        endSession()
        writerExecutor.shutdown()
    }

    private fun isoUtc(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    private fun formatNumber(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    companion object {
        private const val DIRECTORY_NAME = "BleCsSequentialRanger"
        private const val CYCLE_DATA_COLUMN_COUNT = 11
        private const val IMU_COLUMN_COUNT = 13
        private const val IMU_FLUSH_ROW_COUNT = 10
        private const val CYCLE_SUMMARY_HEADER =
            "absolute_time_utc,session_id,experiment_condition,source_type," +
                "cycle,cycle_elapsed_ms,cycle_valid," +
                "rp1_mac,rp1_distance_m," +
                "rp2_mac,rp2_distance_m," +
                "rp3_mac,rp3_distance_m," +
                "rp4_mac,rp4_distance_m," +
                "accel_x_mps2,accel_y_mps2,accel_z_mps2," +
                "gyro_x_radps,gyro_y_radps,gyro_z_radps," +
                "rotation_w,rotation_x,rotation_y,rotation_z," +
                "magnetic_x_ut,magnetic_y_ut,magnetic_z_ut"
        private const val ATTEMPT_LOG_HEADER =
            "session_id,experiment_condition,source_type,cycle,rp_number,rp_label,board_mac," +
                "attempt,event_sequence,attempt_started_utc,event_utc,elapsed_ms,outcome," +
                "raw_distance_m,accepted_distance_m,distance_confidence," +
                "distance_stddev_m,rssi_dbm,detail"
    }
}
