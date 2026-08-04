package com.example.ble6_channelsounding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object CsProtocol {
    val SERVICE_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A01")

    val CONTROL_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A02")

    val STATUS_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A03")

    /*
     * Initiator가 Android Ranging API에서 받은 거리값을
     * Reflector에 다시 전달하기 위한 전용 characteristic입니다.
     */
    val DISTANCE_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A04")

    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val START = "START"
    const val STOP = "STOP"

    const val PREPARING = "PREPARING"
    const val READY = "READY"
    const val STOPPED = "STOPPED"

    const val ERROR_PREFIX = "ERROR:"

    private const val DISTANCE_PACKET_SIZE = 12

    data class DistancePacket(
        val rawMeters: Double,
        val smoothedMeters: Double,
        val sampleCount: Int
    )

    /*
     * 기본 ATT payload 크기에서도 안전하게 들어가도록 12바이트 바이너리 패킷 사용:
     *   rawMeters      : Float, 4 bytes
     *   smoothedMeters : Float, 4 bytes
     *   sampleCount    : Int,   4 bytes
     */
    fun encodeDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ): ByteArray = ByteBuffer
        .allocate(DISTANCE_PACKET_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(rawMeters.toFloat())
        .putFloat(smoothedMeters.toFloat())
        .putInt(sampleCount)
        .array()

    fun decodeDistance(value: ByteArray): DistancePacket? {
        if (value.size != DISTANCE_PACKET_SIZE) return null

        return try {
            val buffer = ByteBuffer
                .wrap(value)
                .order(ByteOrder.LITTLE_ENDIAN)

            val raw = buffer.float
            val smoothed = buffer.float
            val count = buffer.int

            if (!raw.isFinite() || !smoothed.isFinite() || count < 0) {
                null
            } else {
                DistancePacket(
                    rawMeters = raw.toDouble(),
                    smoothedMeters = smoothed.toDouble(),
                    sampleCount = count
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
