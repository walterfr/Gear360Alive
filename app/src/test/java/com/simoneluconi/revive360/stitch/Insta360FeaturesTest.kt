package com.simoneluconi.revive360.stitch

import com.simoneluconi.revive360.media.LiveStreamer
import com.simoneluconi.revive360.media.TelemetryHud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket
import kotlin.math.abs
import kotlin.math.hypot

class Insta360FeaturesTest {

    @Test
    fun deepTracker360TracksMovingSubjectOnDualFisheye() {
        val side = 128
        val width = side * 2
        val height = side
        val model = DualFisheye()

        fun renderSyntheticFrame(targetCx: Int, targetCy: Int): IntArray {
            val px = IntArray(width * height) { 0xFF202530.toInt() }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val d = hypot((x - targetCx).toDouble(), (y - targetCy).toDouble())
                    if (d <= 12.0) {
                        val ring = if (d < 6.0) 0xFFFF5522.toInt() else 0xFF22EE88.toInt()
                        px[y * width + x] = ring
                    }
                }
            }
            return px
        }

        // Frame 0: Target centered in front lens (yaw = 0, pitch = 0 -> (64, 64))
        val frame0 = renderSyntheticFrame(targetCx = 64, targetCy = 64)
        val ref = DeepTracker360.extractDescriptor(frame0, width, height, model, yaw = 0f, pitch = 0f)

        // Frame 1: Target moved right and slightly down in front lens
        val frame1 = renderSyntheticFrame(targetCx = 72, targetCy = 68)
        val (trackedYaw, trackedPitch) = DeepTracker360.trackStep(
            pixels = frame1,
            width = width,
            height = height,
            model = model,
            ref = ref,
            lastYaw = 0f,
            lastPitch = 0f,
        )

        assertTrue("Expected positive yaw shift (right), got $trackedYaw", trackedYaw > 0.05f)
        assertTrue("Expected positive pitch shift (down in Mat3 convention), got $trackedPitch", trackedPitch > 0.02f)

        // Also test Me Mode hemisphere detection: front lens has salient warm target, back lens is flat
        val (meYaw, _) = DeepTracker360.detectMeModeOrientation(frame0, width, height, model)
        assertEquals(0f, meYaw, 1e-4f)

        // Test Back-Lens (yaw = PI) vertical tracking (verifying Ry(yaw) * Rx(pitch) Euler order fix!)
        val backFrame0 = renderSyntheticFrame(targetCx = 128 + 64, targetCy = 64)
        val backRef = DeepTracker360.extractDescriptor(
            backFrame0, width, height, model, yaw = Math.PI.toFloat(), pitch = 0f,
        )
        val backFrame1 = renderSyntheticFrame(targetCx = 128 + 64, targetCy = 70)
        val (_, backTrackedPitch) = DeepTracker360.trackStep(
            pixels = backFrame1,
            width = width,
            height = height,
            model = model,
            ref = backRef,
            lastYaw = Math.PI.toFloat(),
            lastPitch = 0f,
        )
        assertTrue(
            "Back lens pitch must also be positive when target moves down, got $backTrackedPitch",
            backTrackedPitch > 0.02f,
        )

        // Test +/-180° wrapAngle continuity for ViewFinder
        val nearPosPi = (Math.PI - 0.05).toFloat()
        val nearNegPi = (-Math.PI + 0.05).toFloat()
        val wrappedDelta = DeepTracker360.wrapAngle(nearNegPi - nearPosPi)
        assertEquals(0.10f, wrappedDelta, 1e-3f)
    }

    @Test
    fun mertensExposureFusionRecoversHighlightsAndShadows() {
        val w = 16
        val h = 16
        val total = w * h
        // Frame A: Left half well-exposed (mid-gray gradient), right half clipped white (255)
        val frameA = IntArray(total) { idx ->
            val x = idx % w
            if (x < w / 2) {
                val v = 110 + (x * 4)
                (0xFF shl 24) or (v shl 16) or (130 shl 8) or 90
            } else {
                0xFFFFFFFF.toInt()
            }
        }
        // Frame B: Left half underexposed black (5), right half well-exposed colorful pattern
        val frameB = IntArray(total) { idx ->
            val x = idx % w
            if (x < w / 2) {
                0xFF050505.toInt()
            } else {
                val v = 100 + ((x - w / 2) * 5)
                (0xFF shl 24) or (90 shl 16) or (v shl 8) or 140
            }
        }

        val fused = ExposureFusion.fuseMertens(listOf(frameA, frameB), w, h)
        // Check a pixel inside left half (x=3, y=8) and right half (x=12, y=8)
        val leftPx = fused[8 * w + 3]
        val rightPx = fused[8 * w + 12]
        val leftGreen = leftPx shr 8 and 0xFF
        val rightGreen = rightPx shr 8 and 0xFF

        // Both halves should have well-exposed mid-range values (not 5 and not 255)
        assertTrue("Left half should preserve Frame A detail, got $leftGreen", leftGreen in 80..180)
        assertTrue("Right half should preserve Frame B detail, got $rightGreen", rightGreen in 80..180)

        val brackets = ExposureFusion.synthesizeVirtualBracket(frameA)
        assertEquals(3, brackets.size)

        // Test zero-copy in-place single-shot Mertens fusion
        val inPlaceBuf = frameA.clone()
        ExposureFusion.fuseSingleShotMertensInPlace(inPlaceBuf, w, h)
        val inPlaceLeftGreen = inPlaceBuf[8 * w + 3] shr 8 and 0xFF
        assertTrue("In-place Mertens should preserve midtones, got $inPlaceLeftGreen", inPlaceLeftGreen in 90..170)
    }

    @Test
    fun starlapseMaxLightStacksStarsAndSuppressesNoiseFloor() {
        val w = 8
        val h = 8
        val f0 = IntArray(w * h) { 0xFF020202.toInt() }
        val f1 = IntArray(w * h) { 0xFF050505.toInt() } // Below noiseFloor = 8
        val f2 = IntArray(w * h) { 0xFF020202.toInt() }
        f0[10] = 0xFFF0E0D0.toInt()
        f2[20] = 0xFFD0E8FF.toInt()

        val stacked = ExposureFusion.stackStarlapseMaxLight(listOf(f0, f1, f2), w, h, noiseFloor = 8)
        assertEquals(0xFFF0E0D0.toInt(), stacked[10])
        assertEquals(0xFFD0E8FF.toInt(), stacked[20])
        // Pixel 0 should remain at f0's baseline (0x02) since f1 (0x05) is below noiseFloor
        assertEquals(0xFF020202.toInt(), stacked[0])
    }

    @Test
    fun telemetryHudParsesGpxAndComputesGForce() {
        val gpxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="-23.550520" lon="-46.633308">
                  <ele>760.0</ele>
                  <time>2026-11-06T12:00:00Z</time>
                </trkpt>
                <trkpt lat="-23.550000" lon="-46.633308">
                  <ele>764.0</ele>
                  <time>2026-11-06T12:00:05Z</time>
                </trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val pts = TelemetryHud.parseGpx(gpxXml)
        assertEquals(2, pts.size)
        assertEquals(760.0, pts[0].ele, 1e-3)
        assertEquals(764.0, pts[1].ele, 1e-3)
        // ~57.8 meters in 5 seconds = ~41.6 km/h
        assertTrue("Expected ~41.6 km/h, got ${pts[1].speedKmh}", pts[1].speedKmh in 38f..45f)

        val mid = TelemetryHud.sampleGpxAt(pts, 2_500_000L)
        assertNotNull(mid)
        assertEquals(762.0, mid!!.ele, 0.1)

        val samples = listOf(
            GyroSample(0L, pitch = 0f, roll = 0f, yaw = 0f),
            GyroSample(200_000L, pitch = 0.4f, roll = 0.2f, yaw = 0.8f),
        )
        val gForce = TelemetryHud.computeAngularGForce(samples, 100_000L)
        assertTrue("Expected dynamic G-Force > 1.2G during rapid rotation, got $gForce", gForce > 1.2f)
    }

    @Test
    fun liveStreamerBroadcastsCachedHeadersAndAnnexBAccessUnitsOverHttp() {
        // Synthetic Annex-B keyframe containing VPS (32), SPS (33), PPS (34), IDR (19)
        val keyAu = byteArrayOf(
            0, 0, 0, 1, (32 shl 1).toByte(), 1, 0xAA.toByte(),
            0, 0, 0, 1, (33 shl 1).toByte(), 1, 0xBB.toByte(),
            0, 0, 0, 1, (34 shl 1).toByte(), 1, 0xCC.toByte(),
            0, 0, 0, 1, (19 shl 1).toByte(), 1, 0xDD.toByte(),
        )
        val pFrameAu = byteArrayOf(
            0, 0, 0, 1, (1 shl 1).toByte(), 1, 0xEE.toByte(),
        )

        LiveStreamer(port = 0).use { streamer ->
            streamer.start()
            // Cache VPS/SPS/PPS before client connects
            streamer.broadcastAccessUnit(keyAu)

            Socket("127.0.0.1", streamer.boundPort).use { client ->
                client.soTimeout = 3_000
                val out = client.getOutputStream()
                out.write("GET /live.hevc HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                // Wait briefly for client registration then broadcast a P-frame
                var waits = 0
                while (streamer.clientCount == 0 && waits < 30) {
                    Thread.sleep(20L)
                    waits++
                }
                streamer.broadcastAccessUnit(pFrameAu)

                val inp: InputStream = client.getInputStream()
                val buf = ByteArray(512)
                var totalRead = 0
                while (totalRead < buf.size && !buf.copyOf(totalRead).any { it == 0xEE.toByte() }) {
                    val n = inp.read(buf, totalRead, buf.size - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                val text = String(buf, 0, totalRead, Charsets.ISO_8859_1)
                assertTrue("Expected HTTP 200 OK header", text.startsWith("HTTP/1.1 200 OK"))
                assertTrue("Expected video/hevc content-type", text.contains("Content-Type: video/hevc"))
                // Verify binary payload contains our cached VPS byte (0xAA) and P-frame byte (0xEE)
                val bytes = buf.copyOf(totalRead)
                assertTrue("Expected VPS payload byte 0xAA", bytes.any { it == 0xAA.toByte() })
                assertTrue("Expected P-frame payload byte 0xEE", bytes.any { it == 0xEE.toByte() })
            }

            // Also verify stitched MJPEG endpoint (/live.mjpeg) for OBS Browser/Media Source
            Socket("127.0.0.1", streamer.boundPort).use { mjpegClient ->
                mjpegClient.soTimeout = 3_000
                val out = mjpegClient.getOutputStream()
                out.write("GET /live.mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                var waits = 0
                while (!streamer.hasMjpegClients && waits < 30) {
                    Thread.sleep(20L)
                    waits++
                }
                val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0xFF.toByte(), 0xD9.toByte())
                streamer.broadcastStitchedJpeg(fakeJpeg)

                val inp = mjpegClient.getInputStream()
                val buf = ByteArray(512)
                var totalRead = 0
                while (totalRead < buf.size && !buf.copyOf(totalRead).any { it == 0xD9.toByte() }) {
                    val n = inp.read(buf, totalRead, buf.size - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                val text = String(buf, 0, totalRead, Charsets.ISO_8859_1)
                assertTrue("Expected multipart/x-mixed-replace header", text.contains("multipart/x-mixed-replace"))
                assertTrue("Expected --g360frame boundary", text.contains("--g360frame"))
            }
        }
    }
}
