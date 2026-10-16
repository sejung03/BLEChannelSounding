package com.example.blecsranger.model

data class ImuSample(
    val absoluteTimeEpochMs: Long,
    val accelerationX: Double,
    val accelerationY: Double,
    val accelerationZ: Double,
    val gyroscopeX: Double,
    val gyroscopeY: Double,
    val gyroscopeZ: Double,
    val rotationW: Double,
    val rotationX: Double,
    val rotationY: Double,
    val rotationZ: Double,
    val magneticX: Double,
    val magneticY: Double,
    val magneticZ: Double,
)
