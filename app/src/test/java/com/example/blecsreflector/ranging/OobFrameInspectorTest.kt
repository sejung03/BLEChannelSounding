package com.example.blecsreflector.ranging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFrameInspectorTest {
    @Test
    fun capabilityRequestHeaderIsParsed() {
        val frame = byteArrayOf(0x01, 0x00, 0x02, 0x00)

        val header = OobFrameInspector.inspect(frame)

        assertEquals(1, header?.version)
        assertEquals(0, header?.messageId)
        assertEquals("Capability request", header?.messageName)
        assertEquals(4, header?.size)
    }

    @Test
    fun shortFrameIsRejected() {
        assertNull(OobFrameInspector.inspect(byteArrayOf(0x01)))
    }

    @Test
    fun summaryContainsDirectionAndHex() {
        val summary = OobFrameInspector.summarize("RX", byteArrayOf(0x01, 0x06, 0x02, 0x00))

        assertTrue(summary.contains("RX OOB v1 Stop ranging"))
        assertTrue(summary.endsWith("01 06 02 00"))
    }
}
