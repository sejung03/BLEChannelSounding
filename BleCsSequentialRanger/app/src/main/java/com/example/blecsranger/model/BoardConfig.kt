package com.example.blecsranger.model

import java.nio.charset.StandardCharsets
import java.util.UUID

data class BoardConfig(
    val index: Int,
    val label: String,
    val macAddress: String,
) {
    val rangingId: UUID
        get() = UUID.nameUUIDFromBytes("ble-cs-$macAddress".toByteArray(StandardCharsets.UTF_8))
}
