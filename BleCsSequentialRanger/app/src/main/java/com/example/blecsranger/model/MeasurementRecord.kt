package com.example.blecsranger.model

data class MeasurementResult(
    val distanceMeters: Double,
    val distanceConfidence: Int? = null,
    val distanceStandardDeviationMeters: Double? = null,
    val rssiDbm: Int? = null,
    val rangingTechnology: Int? = null,
    val rangingTimestampElapsedMs: Long? = null,
    val azimuthDegrees: Double? = null,
    val azimuthConfidence: Int? = null,
    val elevationDegrees: Double? = null,
    val elevationConfidence: Int? = null,
)

data class RawMeasurementObservation(
    val receivedAtEpochMs: Long,
    val rangingTimestampElapsedMs: Long?,
    val outcome: String,
    val rawDistanceMeters: Double?,
    val acceptedDistanceMeters: Double?,
    val distanceConfidence: Int?,
    val distanceStandardDeviationMeters: Double?,
    val rssiDbm: Int?,
    val detail: String?,
)

data class AttemptLogRecord(
    val cycleNumber: Long,
    val board: BoardConfig,
    val attemptNumber: Int,
    val eventSequence: Int,
    val attemptStartedAtEpochMs: Long,
    val eventAtEpochMs: Long,
    val elapsedMs: Double,
    val outcome: String,
    val rawDistanceMeters: Double?,
    val acceptedDistanceMeters: Double?,
    val distanceConfidence: Int?,
    val distanceStandardDeviationMeters: Double?,
    val rssiDbm: Int?,
    val detail: String?,
)

data class BoardMeasurementRecord(
    val board: BoardConfig,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val measuredAtEpochMs: Long?,
    val elapsedMs: Double,
    val status: String,
    val result: MeasurementResult?,
    val errorDetail: String?,
)

data class CycleMeasurementRecord(
    val cycleNumber: Long,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val elapsedMs: Double,
    val boardMeasurements: List<BoardMeasurementRecord>,
)
