package com.simoneluconi.revive360.stitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraMetadataTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesR210OpaxCalibration() {
        val buf = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, 2) // version 2 (R210)
        buf.putInt(4, 0)
        buf.putDouble(1 * 8, 1450.0) // LeftCenterX
        buf.putDouble(2 * 8, 1442.0) // LeftCenterY
        buf.putDouble(3 * 8, 1446.0) // RightCenterX
        buf.putDouble(4 * 8, 1452.0) // RightCenterY
        buf.putDouble(13 * 8, 1.2)   // DiffX
        buf.putDouble(14 * 8, -0.8)  // DiffY
        buf.putDouble(15 * 8, 0.75)  // DiffRollDeg

        val parsed = CameraMetadata.parseOpax(buf.array())
        assertNotNull(parsed)
        assertEquals(1450f / 2896f, parsed!!.frontCenterX, 1e-5f)
        assertEquals(1442f / 2896f, parsed.frontCenterY, 1e-5f)
        assertEquals(1446f / 2896f, parsed.backCenterX, 1e-5f)
        assertEquals(1452f / 2896f, parsed.backCenterY, 1e-5f)
        assertEquals(Math.toRadians(0.75).toFloat(), parsed.backRoll, 1e-5f)
    }

    @Test
    fun parsesC200OpaxCalibration() {
        val buf = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, 1) // version 1 (C200)
        buf.putInt(9 * 4, 19400)  // 1940.0 px
        buf.putInt(10 * 4, 19480) // 1948.0 px
        buf.putInt(11 * 4, 500)   // 0.5 deg
        buf.putInt(22 * 4, 19420) // 1942.0 px
        buf.putInt(23 * 4, 19460) // 1946.0 px
        buf.putInt(24 * 4, -300)  // -0.3 deg

        val parsed = CameraMetadata.parseOpax(buf.array())
        assertNotNull(parsed)
        assertEquals(1940f / 3888f, parsed!!.frontCenterX, 1e-5f)
        assertEquals(1948f / 3888f, parsed.frontCenterY, 1e-5f)
        assertEquals(1942f / 3888f, parsed.backCenterX, 1e-5f)
        assertEquals(1946f / 3888f, parsed.backCenterY, 1e-5f)
        assertEquals(Math.toRadians(-0.8).toFloat(), parsed.backRoll, 1e-5f)
    }

    @Test
    fun rejectsInvalidOpaxData() {
        assertNull(CameraMetadata.parseOpax(ByteArray(32)))
        assertNull(CameraMetadata.parseOpax(ByteArray(160)))
    }

    @Test
    fun parsesMp4VrotBoxAndInterpolatesHorizonLock() {
        // 2 samples: sample 0 = (pitch=10.0 deg, roll=-5.0 deg, yaw=90.0 deg)
        //            sample 1 = (pitch=20.0 deg, roll=5.0 deg, yaw=100.0 deg)
        val payload = ByteBuffer.allocate(8 + 2 * 24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version
            putInt(2) // count = 2
            // sample 0
            putInt(100); putInt(10)
            putInt(-50); putInt(10)
            putInt(900); putInt(10)
            // sample 1
            putInt(200); putInt(10)
            putInt(50); putInt(10)
            putInt(1000); putInt(10)
        }.array()

        val samples = CameraMetadata.parseVrotBox(payload, durationUs = 1_000_000L)
        assertEquals(2, samples.size)
        assertEquals(Math.toRadians(10.0).toFloat(), samples[0].pitch, 1e-5f)
        assertEquals(Math.toRadians(-5.0).toFloat(), samples[0].roll, 1e-5f)
        assertEquals(Math.toRadians(90.0).toFloat(), samples[0].yaw, 1e-5f)

        val mid = CameraMetadata.sampleGyroAt(samples, 500_000L)
        assertNotNull(mid)
        assertEquals(Math.toRadians(15.0).toFloat(), mid!!.pitch, 1e-4f)
        assertEquals(0f, mid.roll, 1e-4f)
        assertEquals(Math.toRadians(95.0).toFloat(), mid.yaw, 1e-4f)

        // Verify horizonLockMatrix is the exact orthogonal inverse of Mat3.rotation(0f, pitch, roll)
        val combined = GyroSample(0L, yaw = 0.4f, pitch = 0.35f, roll = -0.28f)
        val product = Mat3.multiply(combined.horizonLockMatrix(), Mat3.rotation(0f, combined.pitch, combined.roll))
        val id = Mat3.identity()
        for (i in 0 until 9) {
            assertEquals(id[i], product[i], 1e-5f)
        }
    }

    @Test
    fun parsesLive00VrChunk() {
        val chunk = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(125); putInt(10)  // pitch = 12.5 deg
            putInt(-30); putInt(10)  // roll = -3.0 deg
            putInt(450); putInt(10)  // yaw = 45.0 deg
        }.array()
        val gyro = CameraMetadata.parseLiveVrotChunk(chunk, timestamp120kHz = 120_000L)
        assertNotNull(gyro)
        assertEquals(1_000_000L, gyro!!.timestampUs)
        assertEquals(Math.toRadians(12.5).toFloat(), gyro.pitch, 1e-5f)
        assertEquals(Math.toRadians(-3.0).toFloat(), gyro.roll, 1e-5f)
        assertEquals(Math.toRadians(45.0).toFloat(), gyro.yaw, 1e-5f)
    }

    @Test
    fun readsMp4NestedOpaxAndVrotAtomsFromFile() {
        val opaxPayload = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 2)
            putDouble(1 * 8, 1448.0)
            putDouble(2 * 8, 1448.0)
            putDouble(3 * 8, 1448.0)
            putDouble(4 * 8, 1448.0)
            putDouble(15 * 8, 1.5)
        }.array()
        val vrotPayload = ByteBuffer.allocate(8 + 2 * 24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(2)
            putInt(45); putInt(10)
            putInt(-15); putInt(10)
            putInt(1800); putInt(10)
            putInt(90); putInt(10)
            putInt(0); putInt(10)
            putInt(1800); putInt(10)
        }.array()
        val mvhdPayload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version 0 + flags
            putInt(0) // creation_time
            putInt(0) // modification_time
            putInt(1000) // timescale = 1000 Hz
            putInt(2500) // duration = 2500 -> 2.5 seconds (2_500_000 us)
            putInt(0)
        }.array()

        fun atom(type: String, body: ByteArray): ByteArray =
            ByteBuffer.allocate(8 + body.size).order(ByteOrder.BIG_ENDIAN)
                .putInt(8 + body.size)
                .put(type.toByteArray(Charsets.ISO_8859_1))
                .put(body)
                .array()

        val udta = atom("udta", atom("opax", opaxPayload) + atom("vrot", vrotPayload))
        val moov = atom("moov", atom("mvhd", mvhdPayload) + udta)
        val mp4 = temp.newFile("test.mp4").apply { writeBytes(moov) }

        val cal = CameraMetadata.readCalibration(mp4)
        assertEquals(Math.toRadians(1.5).toFloat(), cal.backRoll, 1e-5f)

        val gyro = CameraMetadata.readMp4Gyro(mp4)
        assertEquals(2, gyro.size)
        assertEquals(Math.toRadians(4.5).toFloat(), gyro[0].pitch, 1e-5f)
        // Auto-detected duration from mvhd = 2_500_000 us
        assertEquals(2_500_000L, gyro[1].timestampUs)
    }
}
