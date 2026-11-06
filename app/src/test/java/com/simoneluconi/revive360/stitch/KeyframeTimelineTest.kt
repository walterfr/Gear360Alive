package com.simoneluconi.revive360.stitch

import com.simoneluconi.revive360.media.VideoStitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

class KeyframeTimelineTest {
    @get:Rule
    val temp = TemporaryFolder()
    @Test
    fun interpolatesKeyframesWithSmoothstepAndShortestAngleWrap() {
        val kf0 = Keyframe(timeUs = 0L, yaw = (PI * 0.9).toFloat(), pitch = 0f, fov = 90f)
        val kf1 = Keyframe(timeUs = 1_000_000L, yaw = (-PI * 0.9).toFloat(), pitch = 0.5f, fov = 110f)
        val list = listOf(kf0, kf1)

        // At midpoint (t = 0.5), smoothstep(0.5) == 0.5; shortest arc from +0.9*PI to -0.9*PI crosses +PI/-PI
        val mid = KeyframeTimeline.evaluate(list, 500_000L)
        assertEquals(0.25f, mid.pitch, 1e-4f)
        assertEquals(100f, mid.fov, 1e-4f)
        assertEquals(PI.toFloat(), kotlin.math.abs(mid.yaw), 1e-3f)
    }

    @Test
    fun upsertReplacesNearbyKeyframeAndKeepsSortedOrder() {
        var list = emptyList<Keyframe>()
        list = KeyframeTimeline.upsert(list, Keyframe(timeUs = 2_000_000L, fov = 90f))
        list = KeyframeTimeline.upsert(list, Keyframe(timeUs = 500_000L, fov = 100f))
        // Within 250ms of 500_000L -> replaces first keyframe
        list = KeyframeTimeline.upsert(list, Keyframe(timeUs = 600_000L, fov = 120f))

        assertEquals(2, list.size)
        assertEquals(600_000L, list[0].timeUs)
        assertEquals(120f, list[0].fov, 1e-4f)
        assertEquals(2_000_000L, list[1].timeUs)
    }

    @Test
    fun buildsValidSphericalVideoV1UuidAtom() {
        val atom = VideoStitcher.buildSphericalUuidAtom()
        assertTrue(atom.size > 32)
        val tag = String(atom, 4, 4, Charsets.US_ASCII)
        assertEquals("uuid", tag)
        val xml = String(atom, 24, atom.size - 24, Charsets.UTF_8)
        assertTrue("<GSpherical:Spherical>true</GSpherical:Spherical>" in xml)
        assertTrue("<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>" in xml)
    }

    @Test
    fun injectsSphericalUuidInsideVideoTrakAndUpdatesStcoOffsets() {
        fun atom(type: String, body: ByteArray): ByteArray =
            ByteBuffer.allocate(8 + body.size).order(ByteOrder.BIG_ENDIAN)
                .putInt(8 + body.size)
                .put(type.toByteArray(Charsets.ISO_8859_1))
                .put(body)
                .array()

        // Build moov -> trak -> mdia -> minf -> (vmhd + stbl -> stco) followed by mdat
        val stcoBody = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(0) // version/flags
            .putInt(1) // 1 chunk offset
            .putInt(200) // points into mdat after moov
            .array()
        val minf = atom("minf", atom("vmhd", ByteArray(12)) + atom("stbl", atom("stco", stcoBody)))
        val trak = atom("trak", atom("mdia", minf))
        val moov = atom("moov", trak)
        val mdat = atom("mdat", ByteArray(64))
        val mp4 = temp.newFile("spherical.mp4").apply { writeBytes(moov + mdat) }

        val uuidSize = VideoStitcher.buildSphericalUuidAtom().size
        VideoStitcher.injectSphericalVideoV1Uuid(mp4)

        RandomAccessFile(mp4, "r").use { raf ->
            val newMoovSize = raf.readInt()
            assertEquals(moov.size + uuidSize, newMoovSize)
            raf.seek(8L)
            val newTrakSize = raf.readInt()
            assertEquals(trak.size + uuidSize, newTrakSize)
            // Verify uuid box is placed right at the end of trak
            val uuidStart = 8L + trak.size
            raf.seek(uuidStart + 4L)
            val type = ByteArray(4).also { raf.readFully(it) }
            assertEquals("uuid", String(type, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun evaluatesTimeShiftSpeedAndShotLabTemplates() {
        val kfs = KeyframeTimeline.buildTemplate(
            template = ShotTemplate.TIMESHIFT_4X,
            durationUs = 10_000_000L,
            baseYaw = 0.2f,
        )
        assertEquals(4, kfs.size)
        assertEquals(1f, KeyframeTimeline.evaluateSpeed(kfs, 0L), 1e-4f)
        assertEquals(4f, KeyframeTimeline.evaluateSpeed(kfs, 5_000_000L), 1e-4f)

        val asteroid = KeyframeTimeline.buildTemplate(ShotTemplate.ASTEROID_INTRO, 8_000_000L)
        assertEquals(Projection.STEREOGRAPHIC, asteroid.first().projection)
        assertEquals(Projection.RECTILINEAR, asteroid.last().projection)
    }
}
