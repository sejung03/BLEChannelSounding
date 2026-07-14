package com.example.blecsreflector.ranging

data class OobFrameHeader(
    val version: Int,
    val messageId: Int,
    val messageName: String,
    val size: Int
)

object OobFrameInspector {
    private val messageNames = mapOf(
        0x00 to "Capability request",
        0x01 to "Capability response",
        0x02 to "Ranging configuration",
        0x03 to "Configuration response",
        0x06 to "Stop ranging",
        0x07 to "Stop response"
    )

    fun inspect(data: ByteArray): OobFrameHeader? {
        if (data.size < 2) return null
        val version = data[0].toInt() and 0xFF
        val messageId = data[1].toInt() and 0xFF
        return OobFrameHeader(
            version = version,
            messageId = messageId,
            messageName = messageNames[messageId] ?: "Unknown 0x${messageId.toString(16).padStart(2, '0')}",
            size = data.size
        )
    }

    fun summarize(direction: String, data: ByteArray): String {
        val header = inspect(data)
        val label = if (header == null) {
            "Malformed OOB frame"
        } else {
            "OOB v${header.version} ${header.messageName}"
        }
        return "$direction $label (${data.size} B) ${data.toHex()}"
    }

    fun ByteArray.toHex(maxBytes: Int = 32): String {
        val visible = take(maxBytes).joinToString(" ") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
        return if (size > maxBytes) "$visible ..." else visible
    }
}
