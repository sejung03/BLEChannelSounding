package com.example.blecsreflector.bluetooth

import java.util.UUID

object ReflectorGattContract {
    const val SERVICE_UUID_TEXT = "8e400001-f315-4f60-9fb8-838830daea50"
    const val RX_UUID_TEXT = "8e400002-f315-4f60-9fb8-838830daea50"
    const val TX_UUID_TEXT = "8e400003-f315-4f60-9fb8-838830daea50"
    const val STATUS_UUID_TEXT = "8e400004-f315-4f60-9fb8-838830daea50"

    val SERVICE_UUID: UUID = UUID.fromString(SERVICE_UUID_TEXT)
    val RX_UUID: UUID = UUID.fromString(RX_UUID_TEXT)
    val TX_UUID: UUID = UUID.fromString(TX_UUID_TEXT)
    val STATUS_UUID: UUID = UUID.fromString(STATUS_UUID_TEXT)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val ADVERTISING_SERVICE_DATA: ByteArray = byteArrayOf('C'.code.toByte(), 'S'.code.toByte(), 0x01)
}
