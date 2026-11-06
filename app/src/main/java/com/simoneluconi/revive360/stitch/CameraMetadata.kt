package com.simoneluconi.revive360.stitch

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 3-axis camera orientation sample (radians) from MP4 `moov/udta/vrot` or live stream `00VR`.
 */
data class GyroSample(
    val timestampUs: Long,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
) {
    /**
     * World-to-camera stabilization matrix that undoes pitch and roll (FlowState horizon lock).
     * Uses the exact orthogonal inverse (`transpose`) of `rz(roll) * rx(pitch)`.
     */
    fun horizonLockMatrix(): FloatArray = Mat3.transpose(Mat3.rotation(0f, pitch, roll))
}

/**
 * Extracts factory optical calibration (`OPAX` / `MakerNote 0x0012` and `OPAI` / `0x0013`) and
 * 3-axis gyroscope stabilization tracks (`vrot` / `00VR`) from Gear 360 JPEG and MP4 files.
 */
object CameraMetadata {
    /** R210 reference half-side in pixels used by the EEPROM OPAX parameters. */
    private const val R210_OPAX_SIDE = 2896f
    /** C200 reference half-side in pixels used by the EEPROM OPAX parameters (scaled ×10). */
    private const val C200_OPAX_SIDE = 3888f

    /** R210 default focal length (equidistant approximation of OCamCalib polynomial). */
    val R210_FOCAL = (0.985 * 0.5 / Math.toRadians(97.5)).toFloat()

    /**
     * Reads factory [DualFisheye] calibration from a JPEG or MP4 [file], falling back to [defaultModel].
     */
    fun readCalibration(file: File, defaultModel: DualFisheye = DualFisheye()): DualFisheye =
        runCatching {
            val name = file.name.lowercase()
            if (name.endsWith(".mp4") || name.endsWith(".mov")) {
                readMp4Calibration(file, defaultModel)
            } else {
                readJpegCalibration(file, defaultModel)
            }
        }.getOrNull() ?: defaultModel

    /**
     * Reads the `moov/udta/vrot` gyroscope track from an MP4 [file].
     * Each sample is spaced evenly across [durationUs] (auto-detected from `moov/mvhd` if 0).
     */
    fun readMp4Gyro(file: File, durationUs: Long = 0L): List<GyroSample> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val resolvedDurationUs = if (durationUs > 0L) durationUs else readMp4DurationUs(raf)
                val vrot = findMp4Atom(raf, 0L, raf.length(), listOf("moov", "udta", "vrot")) ?: return emptyList()
                val bytes = ByteArray(vrot.second.toInt().coerceAtMost(4 * 1024 * 1024))
                raf.seek(vrot.first)
                raf.readFully(bytes)
                parseVrotBox(bytes, resolvedDurationUs)
            }
        }.getOrDefault(emptyList())

    /** Reads the movie duration in microseconds from `moov/mvhd` (supports version 0 and 1). */
    internal fun readMp4DurationUs(raf: RandomAccessFile): Long =
        runCatching {
            val mvhd = findMp4Atom(raf, 0L, raf.length(), listOf("moov", "mvhd")) ?: return 0L
            if (mvhd.second < 20L) return 0L
            val bytes = ByteArray(minOf(mvhd.second.toInt(), 36))
            raf.seek(mvhd.first)
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get(0).toInt() and 0xFF
            if (version == 1 && bytes.size >= 32) {
                val timescale = buf.getInt(20).toLong() and 0xFFFFFFFFL
                val duration = buf.getLong(24)
                if (timescale > 0L && duration > 0L) duration * 1_000_000L / timescale else 0L
            } else {
                val timescale = buf.getInt(12).toLong() and 0xFFFFFFFFL
                val duration = buf.getInt(16).toLong() and 0xFFFFFFFFL
                if (timescale > 0L && duration > 0L) duration * 1_000_000L / timescale else 0L
            }
        }.getOrDefault(0L)

    /**
     * Parses the payload of a `vrot` MP4 atom (after the 8-byte box size+type header, or including it).
     * Layout: `u32 version(0)`, `u32 count`, then `count × 24 bytes`:
     * 3 big-endian rational pairs `(num: s32, den: s32)` for pitch, roll, yaw in degrees.
     */
    fun parseVrotBox(payload: ByteArray, durationUs: Long = 0L): List<GyroSample> {
        val offset = if (payload.size >= 8 &&
            payload[4] == 'v'.code.toByte() &&
            payload[5] == 'r'.code.toByte() &&
            payload[6] == 'o'.code.toByte() &&
            payload[7] == 't'.code.toByte()
        ) 8 else 0
        if (payload.size < offset + 8) return emptyList()
        val buf = ByteBuffer.wrap(payload, offset, payload.size - offset).order(ByteOrder.BIG_ENDIAN)
        buf.int // version / flags (0)
        val count = buf.int
        if (count <= 0 || buf.remaining() < count * 24) return emptyList()
        val stepUs = if (durationUs > 0 && count > 1) durationUs / (count - 1) else 33_333L
        val out = ArrayList<GyroSample>(count)
        for (i in 0 until count) {
            val aNum = buf.int; val aDen = buf.int
            val bNum = buf.int; val bDen = buf.int
            val cNum = buf.int; val cDen = buf.int
            val pitchDeg = if (aDen != 0) aNum.toFloat() / aDen else 0f
            val rollDeg = if (bDen != 0) bNum.toFloat() / bDen else 0f
            val yawDeg = if (cDen != 0) cNum.toFloat() / cDen else 0f
            out += GyroSample(
                timestampUs = i * stepUs,
                yaw = Math.toRadians(yawDeg.toDouble()).toFloat(),
                pitch = Math.toRadians(pitchDeg.toDouble()).toFloat(),
                roll = Math.toRadians(rollDeg.toDouble()).toFloat(),
            )
        }
        return out
    }

    /**
     * Parses a single `00VR` chunk from the live `TTTS` stream (120 kHz clock).
     * Payload is 24 bytes: 3 big-endian rational pairs `(num: s32, den: s32)` for yaw/pitch/roll.
     */
    fun parseLiveVrotChunk(payload: ByteArray, timestamp120kHz: Long): GyroSample? {
        if (payload.size < 24) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val aNum = buf.int; val aDen = buf.int
        val bNum = buf.int; val bDen = buf.int
        val cNum = buf.int; val cDen = buf.int
        val pitchDeg = if (aDen != 0) aNum.toFloat() / aDen else 0f
        val rollDeg = if (bDen != 0) bNum.toFloat() / bDen else 0f
        val yawDeg = if (cDen != 0) cNum.toFloat() / cDen else 0f
        return GyroSample(
            timestampUs = timestamp120kHz * 1_000_000L / 120_000L,
            yaw = Math.toRadians(yawDeg.toDouble()).toFloat(),
            pitch = Math.toRadians(pitchDeg.toDouble()).toFloat(),
            roll = Math.toRadians(rollDeg.toDouble()).toFloat(),
        )
    }

    /**
     * Interpolates the gyro orientation at [timeUs] from a sorted list of [samples].
     */
    fun sampleGyroAt(samples: List<GyroSample>, timeUs: Long): GyroSample? {
        if (samples.isEmpty()) return null
        if (timeUs <= samples.first().timestampUs) return samples.first()
        if (timeUs >= samples.last().timestampUs) return samples.last()
        var lo = 0
        var hi = samples.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timestampUs <= timeUs) lo = mid else hi = mid
        }
        val a = samples[lo]
        val b = samples[hi]
        val span = (b.timestampUs - a.timestampUs).coerceAtLeast(1L)
        val t = ((timeUs - a.timestampUs).toFloat() / span).coerceIn(0f, 1f)
        return GyroSample(
            timestampUs = timeUs,
            yaw = lerpAngle(a.yaw, b.yaw, t),
            pitch = a.pitch + (b.pitch - a.pitch) * t,
            roll = lerpAngle(a.roll, b.roll, t),
        )
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var diff = (b - a) % (2f * Math.PI.toFloat())
        if (diff > Math.PI) diff -= 2f * Math.PI.toFloat()
        if (diff < -Math.PI) diff += 2f * Math.PI.toFloat()
        return a + diff * t
    }

    /**
     * Parses the 160-byte `OPAX` block (`MakerNote 0x0012` or MP4 `opax` payload).
     * Supports both SM-R210 (version 2: 20× float64 LE) and SM-C200 (version 1: 40× int32 LE).
     */
    fun parseOpax(bytes: ByteArray, base: DualFisheye = DualFisheye()): DualFisheye? {
        val offset = if (bytes.size >= 168 &&
            bytes[4] == 'o'.code.toByte() &&
            bytes[5] == 'p'.code.toByte() &&
            bytes[6] == 'a'.code.toByte() &&
            bytes[7] == 'x'.code.toByte()
        ) 8 else 0
        if (bytes.size - offset < 160) return null
        val buf = ByteBuffer.wrap(bytes, offset, 160).order(ByteOrder.LITTLE_ENDIAN)
        val firstInt = buf.getInt(offset)
        val secondInt = buf.getInt(offset + 4)

        // R210 format: [0] is int32 version=2 followed by 4 zero bytes, then float64 values at [1..15]
        if (firstInt == 2 && secondInt == 0) {
            val leftCx = buf.getDouble(offset + 1 * 8).toFloat()
            val leftCy = buf.getDouble(offset + 2 * 8).toFloat()
            val rightCx = buf.getDouble(offset + 3 * 8).toFloat()
            val rightCy = buf.getDouble(offset + 4 * 8).toFloat()
            val diffX = buf.getDouble(offset + 13 * 8).toFloat()
            val diffY = buf.getDouble(offset + 14 * 8).toFloat()
            val diffRollDeg = buf.getDouble(offset + 15 * 8).toFloat()
            if (leftCx !in 1000f..2000f || leftCy !in 1000f..2000f ||
                rightCx !in 1000f..2000f || rightCy !in 1000f..2000f
            ) return null
            val focalPx = R210_OPAX_SIDE * R210_FOCAL
            return base.copy(
                focal = R210_FOCAL,
                frontCenterX = leftCx / R210_OPAX_SIDE,
                frontCenterY = leftCy / R210_OPAX_SIDE,
                backCenterX = rightCx / R210_OPAX_SIDE,
                backCenterY = rightCy / R210_OPAX_SIDE,
                backYaw = atan2(diffX, focalPx).coerceIn(-0.15f, 0.15f),
                backPitch = atan2(diffY, focalPx).coerceIn(-0.15f, 0.15f),
                backRoll = Math.toRadians(diffRollDeg.toDouble()).toFloat().coerceIn(-0.25f, 0.25f),
            )
        }

        // C200 format: 40× int32 LE; symmetric lens blocks at indices 8..20 (front) and 21..33 (back)
        // Centers are stored in tenths of a pixel on a 3888×3888 half-frame (e.g. ~19440 = 1944.0 px)
        val c1x = buf.getInt(offset + 9 * 4) / 10f
        val c1y = buf.getInt(offset + 10 * 4) / 10f
        val c2x = buf.getInt(offset + 22 * 4) / 10f
        val c2y = buf.getInt(offset + 23 * 4) / 10f
        if (c1x in 1400f..2500f && c1y in 1400f..2500f && c2x in 1400f..2500f && c2y in 1400f..2500f) {
            val roll1 = buf.getInt(offset + 11 * 4) / 1000f
            val roll2 = buf.getInt(offset + 24 * 4) / 1000f
            val pitch1 = buf.getInt(offset + 12 * 4) / 1000f
            val pitch2 = buf.getInt(offset + 25 * 4) / 1000f
            val yaw1 = buf.getInt(offset + 13 * 4) / 1000f
            val yaw2 = buf.getInt(offset + 26 * 4) / 1000f
            return base.copy(
                focal = DualFisheye.C200_FOCAL,
                frontCenterX = c1x / C200_OPAX_SIDE,
                frontCenterY = c1y / C200_OPAX_SIDE,
                backCenterX = c2x / C200_OPAX_SIDE,
                backCenterY = c2y / C200_OPAX_SIDE,
                backYaw = Math.toRadians((yaw2 - yaw1).toDouble()).toFloat().coerceIn(-0.15f, 0.15f),
                backPitch = Math.toRadians((pitch2 - pitch1).toDouble()).toFloat().coerceIn(-0.15f, 0.15f),
                backRoll = Math.toRadians((roll2 - roll1).toDouble()).toFloat().coerceIn(-0.25f, 0.25f),
            )
        }
        return null
    }

    private fun readMp4Calibration(file: File, defaultModel: DualFisheye): DualFisheye {
        RandomAccessFile(file, "r").use { raf ->
            val opax = findMp4Atom(raf, 0L, raf.length(), listOf("moov", "udta", "opax")) ?: return defaultModel
            val bytes = ByteArray(opax.second.toInt().coerceAtMost(4096))
            raf.seek(opax.first)
            raf.readFully(bytes)
            return parseOpax(bytes, defaultModel) ?: defaultModel
        }
    }

    private fun readJpegCalibration(file: File, defaultModel: DualFisheye): DualFisheye {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 16 || raf.readUnsignedShort() != 0xFFD8) return defaultModel
            var pos = 2L
            while (pos + 4 < raf.length()) {
                raf.seek(pos)
                if (raf.readUnsignedByte() != 0xFF) break
                val marker = raf.readUnsignedByte()
                if (marker == 0xDA || marker == 0xD9) break // SOS or EOI
                val len = raf.readUnsignedShort()
                if (len < 2 || pos + 2 + len > raf.length()) break
                if (marker == 0xE1 && len > 16) {
                    val seg = ByteArray(len - 2)
                    raf.readFully(seg)
                    parseExifSegmentForOpax(seg, defaultModel)?.let { return it }
                }
                pos += 2 + len
            }
        }
        return defaultModel
    }

    /** Scans an APP1 Exif segment for MakerNote tag 0x0012 (OPAX, 160 bytes). */
    internal fun parseExifSegmentForOpax(app1: ByteArray, defaultModel: DualFisheye = DualFisheye()): DualFisheye? {
        if (app1.size < 14 || String(app1, 0, 4, Charsets.US_ASCII) != "Exif") return null
        val tiffStart = 6
        val order = when (String(app1, tiffStart, 2, Charsets.US_ASCII)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val buf = ByteBuffer.wrap(app1, tiffStart, app1.size - tiffStart).order(order)
        if (buf.getShort(2).toInt() != 42) return null
        val ifd0 = buf.getInt(4)
        val exifIfd = findTiffTagOffset(buf, ifd0, 0x8769) ?: return null
        val makerNote = findTiffTagSlice(buf, exifIfd, 0x927C) ?: return null
        // Samsung MakerNote is itself an IFD (either directly at makerNote.first or after a header)
        for (headerSkip in intArrayOf(0, 8, 10, 12)) {
            val base = makerNote.first + headerSkip
            if (base + 6 > buf.limit()) continue
            val slice = findMakerNoteTag0012(buf, base, makerNote.first)
            if (slice != null) {
                val raw = ByteArray(slice.second)
                System.arraycopy(app1, tiffStart + slice.first, raw, 0, slice.second)
                parseOpax(raw, defaultModel)?.let { return it }
            }
        }
        return null
    }

    private fun findTiffTagOffset(buf: ByteBuffer, ifdOffset: Int, targetTag: Int): Int? {
        if (ifdOffset < 0 || ifdOffset + 2 > buf.limit()) return null
        val count = buf.getShort(ifdOffset).toInt() and 0xFFFF
        if (ifdOffset + 2 + count * 12 > buf.limit()) return null
        for (i in 0 until count) {
            val entry = ifdOffset + 2 + i * 12
            val tag = buf.getShort(entry).toInt() and 0xFFFF
            if (tag == targetTag) return buf.getInt(entry + 8)
        }
        return null
    }

    private fun findTiffTagSlice(buf: ByteBuffer, ifdOffset: Int, targetTag: Int): Pair<Int, Int>? {
        if (ifdOffset < 0 || ifdOffset + 2 > buf.limit()) return null
        val count = buf.getShort(ifdOffset).toInt() and 0xFFFF
        if (ifdOffset + 2 + count * 12 > buf.limit()) return null
        for (i in 0 until count) {
            val entry = ifdOffset + 2 + i * 12
            val tag = buf.getShort(entry).toInt() and 0xFFFF
            if (tag == targetTag) {
                val len = buf.getInt(entry + 4)
                val off = buf.getInt(entry + 8)
                if (len > 0 && off >= 0 && off + len <= buf.limit()) return off to len
            }
        }
        return null
    }

    private fun findMakerNoteTag0012(buf: ByteBuffer, ifdOffset: Int, makerBase: Int): Pair<Int, Int>? {
        if (ifdOffset < 0 || ifdOffset + 2 > buf.limit()) return null
        val count = buf.getShort(ifdOffset).toInt() and 0xFFFF
        if (count !in 1..128 || ifdOffset + 2 + count * 12 > buf.limit()) return null
        for (i in 0 until count) {
            val entry = ifdOffset + 2 + i * 12
            val tag = buf.getShort(entry).toInt() and 0xFFFF
            if (tag == 0x0012) {
                val type = buf.getShort(entry + 2).toInt() and 0xFFFF
                val unit = when (type) {
                    1, 2, 7 -> 1
                    3 -> 2
                    4, 9, 11 -> 4
                    5, 10, 12 -> 8
                    else -> 1
                }
                val bytes = buf.getInt(entry + 4) * unit
                val rawOff = buf.getInt(entry + 8)
                for (candidate in intArrayOf(rawOff, makerBase + rawOff)) {
                    if (bytes >= 160 && candidate >= 0 && candidate + bytes <= buf.limit()) {
                        return candidate to bytes
                    }
                }
            }
        }
        return null
    }

    /** Finds a nested MP4 box path (e.g. `["moov", "udta", "vrot"]`) and returns `(payloadOffset, payloadSize)`. */
    private fun findMp4Atom(raf: RandomAccessFile, start: Long, end: Long, path: List<String>): Pair<Long, Long>? {
        var pos = start
        val target = path.first()
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { raf.readFully(it) }
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var headerSize = 8L
            val boxSize = when (size32) {
                1L -> {
                    if (pos + 16 > end) return null
                    headerSize = 16L
                    raf.readLong()
                }
                0L -> end - pos
                else -> size32
            }
            if (boxSize < headerSize || pos + boxSize > end) return null
            if (type == target) {
                val payloadStart = pos + headerSize
                val payloadEnd = pos + boxSize
                return if (path.size == 1) {
                    payloadStart to (payloadEnd - payloadStart)
                } else {
                    findMp4Atom(raf, payloadStart, payloadEnd, path.drop(1))
                }
            }
            pos += boxSize
        }
        return null
    }
}
