package com.example.ble6_channelsounding

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One combined BLE/CS event and distance CSV per ranging session. */
class CsCsvLogger(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var uri: Uri? = null
    private var role = ""
    private var peerAddress = ""
    private var startedAtMillis = 0L

    @Synchronized
    fun start(role: String, peerAddress: String?): Uri? {
        close()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${stamp}_CS_${role.uppercase(Locale.US)}.csv")
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneCS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return try {
            val created = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val stream = context.contentResolver.openOutputStream(created, "w") ?: run {
                context.contentResolver.delete(created, null, null)
                return null
            }
            uri = created
            this.role = role.uppercase(Locale.US)
            this.peerAddress = peerAddress.orEmpty()
            startedAtMillis = now
            writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).also {
                it.write("\uFEFF") // Excel-compatible UTF-8 BOM for Korean messages.
                it.write("timestamp,elapsed_ms,role,record_type,source,event,raw_distance_m,smoothed_distance_m,sample_count,peer_address\n")
                it.flush()
            }
            created
        } catch (_: Exception) {
            close()
            null
        }
    }

    @Synchronized
    fun event(source: String, message: String) =
        writeRow("EVENT", source, message, null, null, null)

    @Synchronized
    fun distance(source: String, raw: Double, smoothed: Double, samples: Int) =
        writeRow("DISTANCE", source, "distance measured", raw, smoothed, samples)

    private fun writeRow(type: String, source: String, event: String, raw: Double?, smoothed: Double?, samples: Int?) {
        val output = writer ?: return
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(now))
        val fields = listOf(
            timestamp, (now - startedAtMillis).toString(), role, type, source, event,
            raw?.let { String.format(Locale.US, "%.6f", it) }.orEmpty(),
            smoothed?.let { String.format(Locale.US, "%.6f", it) }.orEmpty(),
            samples?.toString().orEmpty(), peerAddress
        )
        try {
            output.write(fields.joinToString(",") { escape(it) })
            output.newLine()
            output.flush()
        } catch (_: Exception) {
            // Ranging must continue if storage becomes unavailable.
        }
    }

    @Synchronized
    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) { }
        writer = null
        uri?.let { completed ->
            try {
                context.contentResolver.update(completed, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            } catch (_: Exception) { }
        }
        uri = null
        role = ""
        peerAddress = ""
        startedAtMillis = 0L
    }

    private fun escape(value: String) = "\"${value.replace("\"", "\"\"")}\""
}
