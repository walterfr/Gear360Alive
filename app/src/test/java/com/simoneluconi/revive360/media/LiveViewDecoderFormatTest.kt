package com.simoneluconi.revive360.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewDecoderFormatTest {
    @Test
    fun tellsTheR210JpegFramesFromHevc() {
        // The first bytes of a real SM-R210 livestream_high.avi video chunk
        assertTrue(LiveViewDecoder.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte())))
        assertFalse(LiveViewDecoder.isJpeg(byteArrayOf(0, 0, 0, 1, 0x40, 1)))
        assertFalse(LiveViewDecoder.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
