package com.example.blecsreflector.ble

import java.util.UUID

object RasConstants {
    val RAS_SERVICE_UUID: UUID =
        UUID.fromString("0000185b-0000-1000-8000-00805f9b34fb")

    val RAS_OOB_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("00002c01-0000-1000-8000-00805f9b34fb")

    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}