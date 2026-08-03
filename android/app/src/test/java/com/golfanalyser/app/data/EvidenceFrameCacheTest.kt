package com.golfanalyser.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceFrameCacheTest {
    @Test
    fun acceptsCompleteJpegMarkers() {
        assertTrue(
            isUsableEvidenceJpeg(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte()),
            ),
        )
    }

    @Test
    fun rejectsEmptyTruncatedAndNonJpegFiles() {
        assertFalse(isUsableEvidenceJpeg(byteArrayOf()))
        assertFalse(isUsableEvidenceJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
        assertFalse(isUsableEvidenceJpeg(byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())))
    }
}
