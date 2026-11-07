package com.simoneluconi.revive360.stitch

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The layouts found in real SM-R210 files, which synthetic data had missed. */
class RealR210FormatTest {
    private fun record(): ByteArray = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, 2)
        putDouble(1 * 8, 1442.418)
        putDouble(2 * 8, 1455.952)
        putDouble(3 * 8, 1434.729)
        putDouble(4 * 8, 1452.075)
        putDouble(13 * 8, 4.115)
        putDouble(14 * 8, 20.667)
        putDouble(15 * 8, 1.136)
    }.array()

    private fun assertCalibrated(model: DualFisheye?) {
        assertNotNull(model)
        assertEquals(1442.418f / 2896f, model!!.frontCenterX, 1e-5f)
        assertEquals(1452.075f / 2896f, model.backCenterY, 1e-5f)
    }

    @Test
    fun mp4PayloadHasABigEndianVersionBeforeTheBlock() {
        val payload = byteArrayOf(0, 0, 0, 2) + record()
        assertEquals(164, payload.size)
        assertCalibrated(CameraMetadata.parseOpax(payload))
    }

    /** An APP1 segment whose tag 0x0012 offset counts from the maker note; that offset from the TIFF start holds zeros. */
    private fun app1(): ByteArray {
        val tiff = ByteBuffer.allocate(2000).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put("II".toByteArray()).putShort(42).putInt(8)
        tiff.position(8)
        tiff.putShort(1).putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(40)
        tiff.position(40)
        val makerNote = 100
        val relative = 200
        tiff.putShort(1).putShort(0x927C.toShort()).putShort(7).putInt(1000).putInt(makerNote)
        tiff.position(makerNote)
        tiff.putShort(1).putShort(0x0012).putShort(7).putInt(160).putInt(relative)
        tiff.position(makerNote + relative)
        tiff.put(record())
        return "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.array()
    }

    @Test
    fun jpegOffsetCountsFromTheMakerNote() {
        assertCalibrated(CameraMetadata.parseExifSegmentForOpax(app1()))
    }

    @Test
    fun vrotHasNoYawAndItsAnglesWrap() {
        val payload = ByteBuffer.allocate(8 + 2 * 24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(2)
            // Rows from a real recording
            putInt(0); putInt(10); putInt(-205); putInt(10); putInt(3582); putInt(10)
            putInt(0); putInt(10); putInt(486); putInt(10); putInt(-3594); putInt(10)
        }.array()
        val samples = CameraMetadata.parseVrotBox(payload, 1_000_000L)
        assertEquals(0f, samples[0].yaw, 1e-6f)
        assertEquals(Math.toRadians(-20.5).toFloat(), samples[0].pitch, 1e-5f)
        assertEquals(Math.toRadians(-1.8).toFloat(), samples[0].roll, 1e-4f)
        assertEquals(Math.toRadians(0.6).toFloat(), samples[1].roll, 1e-4f)
    }

    @Test
    fun theR210UsesItsRealLensCurve() {
        val model = CameraMetadata.parseOpax(byteArrayOf(0, 0, 0, 2) + record())!!
        // The firmware's polynomial: 971.94 of 2048 at 90 degrees
        assertEquals(971.9376f / 2048f, model.radius((Math.PI / 2).toFloat()), 1e-5f)
        // The image circle's radius is not set in this record: the curve's own limit applies
        assertEquals(Math.toRadians(101.0).toFloat(), model.maxTheta, 1e-4f)

        // The same curve in Kotlin and in the shaders' coefficients
        assertEquals(6, model.polyCoefficients().size)
        assertEquals(model.lensPoly[1], model.polyCoefficients()[1], 0f)
    }

    @Test
    fun anUnknownLensStaysEquidistant() {
        val model = DualFisheye()
        val theta = Math.toRadians(70.0).toFloat()
        assertEquals(model.focal * theta, model.radius(theta), 1e-5f)
    }

    @Test
    fun theImageCircleLimitsWhatALensSees() {
        val withCircle = record().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putDouble(5 * 8, 1434.486).putDouble(6 * 8, 1434.729)
        }
        val model = CameraMetadata.parseOpax(withCircle)!!
        assertEquals(1434.486f / 2896f, model.radius(model.maxTheta), 1e-4f)
        assertTrue(model.maxTheta in Math.toRadians(90.0).toFloat()..Math.toRadians(100.0).toFloat())
    }

    /** Runs only where the owner's samples are present: they stay out of git. */
    @Test
    fun readsTheRealFilesWhenPresent() {
        val folder = File("../100PHOTO")
        assumeTrue(folder.isDirectory)
        val mp4 = CameraMetadata.readCalibration(File(folder, "360_0287.MP4"))
        val jpg = CameraMetadata.readCalibration(File(folder, "360_0288.JPG"))
        assertEquals(1442.418f / 2896f, mp4.frontCenterX, 1e-5f)
        assertEquals(1442.418f / 2896f, jpg.frontCenterX, 1e-5f)
        val gyro = CameraMetadata.readMp4Gyro(File(folder, "360_0302.MP4"))
        assertEquals(11492, gyro.size)
        assertTrue(gyro.all { it.yaw == 0f })
    }
}
