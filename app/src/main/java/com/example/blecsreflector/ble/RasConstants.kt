package com.example.blecsreflector.ble

import java.util.UUID

object RasConstants {
    fun uuid16(value: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", value))

    val RANGING_SERVICE_UUID: UUID = uuid16(0x185B)

    val FEATURES_UUID: UUID = uuid16(0x2C14)
    val REALTIME_RD_UUID: UUID = uuid16(0x2C15)
    val ONDEMAND_RD_UUID: UUID = uuid16(0x2C16)
    val CONTROL_POINT_UUID: UUID = uuid16(0x2C17)
    val RD_READY_UUID: UUID = uuid16(0x2C18)
    val RD_OVERWRITTEN_UUID: UUID = uuid16(0x2C19)

    val CCCD_UUID: UUID = uuid16(0x2902)

    const val RAS_FEATURE_REALTIME_RD: Int = 0x00000001
}