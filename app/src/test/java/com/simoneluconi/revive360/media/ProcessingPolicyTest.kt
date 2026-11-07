package com.simoneluconi.revive360.media

import com.simoneluconi.revive360.media.ProcessingDecision.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingPolicyTest {
    /** A codec as MediaCodec declares it: a size box, either way round, and blocks of 16 per second. */
    private fun codec(longSide: Int, shortSide: Int, blocksPerSecond: Long) = CodecCapability(
        supportsSize = { w, h -> maxOf(w, h) <= longSide && minOf(w, h) <= shortSide },
        supportsRate = { w, h, fps ->
            maxOf(w, h) <= longSide && minOf(w, h) <= shortSide &&
                ((w + 15) / 16L) * ((h + 15) / 16L) * fps <= blocksPerSecond
        },
    )

    /** Lenovo TB361FU, as read over adb on 2026-11-06. */
    private val tablet = listOf(codec(2560, 1440, 489_600))
    private val strong = listOf(codec(4096, 2304, 2_000_000))

    private fun decide(w: Int, h: Int, fps: Int, codecs: List<CodecCapability> = tablet, tw: Int? = null, th: Int? = null) =
        ProcessingPolicy.decide(VideoInfo(w, h, fps), codecs, codecs, tw, th)

    @Test
    fun theTabletHandlesTheModesItDeclares() {
        for ((w, h, fps) in listOf(Triple(2560, 1280, 30), Triple(1920, 960, 30), Triple(1440, 720, 120))) {
            val d = decide(w, h, fps)
            assertEquals("$w x $h", Kind.PROCESS, d.kind)
            assertEquals(w, d.outWidth)
            assertEquals(h, d.outHeight)
            assertTrue("$w x $h at $fps", d.realtime)
        }
    }

    @Test
    fun aRateAboveTheDeclaredOneIsSlowerNotRefused() {
        val d = decide(2560, 1280, 60)
        assertEquals(Kind.PROCESS, d.kind)
        assertFalse(d.realtime)
    }

    @Test
    fun filesBeyondTheDeclaredDecoderWarnAndCanStillBeTried() {
        for ((w, h, fps) in listOf(Triple(4096, 2048, 24), Triple(2880, 1440, 30))) {
            val d = decide(w, h, fps)
            assertEquals(Kind.BEYOND_DECLARED_DECODER, d.kind)
            assertTrue(d.canExport)
            assertEquals(2560, d.outWidth)
            assertEquals(1280, d.outHeight)
        }
    }

    @Test
    fun aStrongerDeviceGetsTheCameraFullSizeWithNoChange() {
        for ((w, h, fps) in listOf(Triple(4096, 2048, 24), Triple(2880, 1440, 30), Triple(2560, 1280, 60))) {
            val d = decide(w, h, fps, strong)
            assertEquals(Kind.PROCESS, d.kind)
            assertEquals(w, d.outWidth)
            assertEquals(h, d.outHeight)
            assertTrue(d.realtime)
        }
    }

    @Test
    fun oneLensAndStitchedVideosAreNotStitched() {
        for ((w, h, fps) in listOf(Triple(1920, 1080, 60), Triple(1920, 1080, 30), Triple(1280, 720, 30))) {
            val d = decide(w, h, fps)
            assertEquals(Kind.NOT_DUAL_FISHEYE, d.kind)
            assertFalse(d.canExport)
        }
        val stitched = ProcessingPolicy.decide(VideoInfo(2560, 1280, 30), tablet, tablet, alreadyStitched = true)
        assertEquals(Kind.NOT_DUAL_FISHEYE, stitched.kind)
    }

    @Test
    fun aSmallerEncoderReducesTheOutputKeepingItsShape() {
        val d = ProcessingPolicy.decide(VideoInfo(2560, 1280, 30), tablet, listOf(codec(1920, 1088, 489_600)))
        assertEquals(Kind.REDUCED_OUTPUT, d.kind)
        assertEquals(1920, d.outWidth)
        assertEquals(960, d.outHeight)
    }

    @Test
    fun reframeSizesFitEitherWayRound() {
        val portrait = decide(2560, 1280, 30, tw = 1080, th = 1920)
        assertEquals(Kind.PROCESS, portrait.kind)
        assertEquals(1080, portrait.outWidth)
        assertEquals(1920, portrait.outHeight)
    }

    @Test
    fun noEncoderStopsTheExport() {
        val d = ProcessingPolicy.decide(VideoInfo(2560, 1280, 30), tablet, emptyList())
        assertEquals(Kind.NO_ENCODER, d.kind)
        assertFalse(d.canExport)
    }
}
