package com.simoneluconi.revive360.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLUtils
import com.simoneluconi.revive360.stitch.CameraMetadata
import com.simoneluconi.revive360.stitch.Gl
import com.simoneluconi.revive360.stitch.GyroSample
import java.time.Instant
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Insta360 "GPS Stats Dashboard" & `vrot` G-Force telemetry parser and OpenGL ES 3.0 HUD overlay renderer.
 */
object TelemetryHud {
    data class GpxPoint(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val ele: Double,
        val speedKmh: Float,
    )

    private val TRKPT_REGEX = Regex(
        """<trkpt\s+[^>]*?lat=["']([0-9.+\-]+)["'][^>]*?lon=["']([0-9.+\-]+)["'][^>]*>(.*?)</trkpt>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ELE_REGEX = Regex("""<ele>\s*([0-9.+\-]+)\s*</ele>""", RegexOption.IGNORE_CASE)
    private val TIME_REGEX = Regex("""<time>\s*([^<\s]+)\s*</time>""", RegexOption.IGNORE_CASE)

    /**
     * Parses a standard GPX 1.1 XML string (from Strava, Garmin, Smartwatch, or phone logger)
     * and computes ground speed in km/h via the Haversine formula.
     */
    fun parseGpx(xml: String): List<GpxPoint> {
        val raw = mutableListOf<GpxPoint>()
        for (m in TRKPT_REGEX.findAll(xml)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            val body = m.groupValues[3]
            val ele = ELE_REGEX.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val timeStr = TIME_REGEX.find(body)?.groupValues?.get(1)
            val timeMs = timeStr?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (raw.size * 1_000L)
            val prev = raw.lastOrNull()
            val speedKmh = if (prev != null && timeMs > prev.timeMs) {
                val distM = haversineMeters(prev.lat, prev.lon, lat, lon)
                val dtSec = (timeMs - prev.timeMs) / 1000.0
                ((distM / dtSec) * 3.6).toFloat().coerceIn(0f, 400f)
            } else 0f
            raw.add(GpxPoint(timeMs, lat, lon, ele, speedKmh))
        }
        return raw
    }

    /** Samples/interpolates GPX telemetry at [offsetUs] from the beginning of [track]. */
    fun sampleGpxAt(track: List<GpxPoint>, offsetUs: Long): GpxPoint? {
        if (track.isEmpty()) return null
        if (track.size == 1) return track.first()
        val startMs = track.first().timeMs
        val targetMs = startMs + offsetUs / 1_000L
        if (targetMs <= startMs) return track.first()
        if (targetMs >= track.last().timeMs) return track.last()

        var idx = 0
        while (idx + 1 < track.size && track[idx + 1].timeMs < targetMs) idx++
        val a = track[idx]
        val b = track[idx + 1]
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
        val t = ((targetMs - a.timeMs).toDouble() / span).coerceIn(0.0, 1.0)
        return GpxPoint(
            timeMs = targetMs,
            lat = a.lat + (b.lat - a.lat) * t,
            lon = a.lon + (b.lon - a.lon) * t,
            ele = a.ele + (b.ele - a.ele) * t,
            speedKmh = (a.speedKmh + (b.speedKmh - a.speedKmh) * t.toFloat()),
        )
    }

    /**
     * Computes instantaneous G-load (`1.0 G` baseline + angular/centripetal load from `vrot` samples)
     * around [timeUs].
     */
    fun computeAngularGForce(samples: List<GyroSample>, timeUs: Long): Float {
        if (samples.isEmpty()) return 1.0f
        val dtUs = 100_000L // 100 ms window
        val s0 = CameraMetadata.sampleGyroAt(samples, (timeUs - dtUs).coerceAtLeast(0L)) ?: return 1.0f
        val s1 = CameraMetadata.sampleGyroAt(samples, timeUs + dtUs) ?: return 1.0f
        val spanSec = ((s1.timestampUs - s0.timestampUs).coerceAtLeast(50_000L)) / 1_000_000f
        val omegaPitch = (s1.pitch - s0.pitch) / spanSec
        val omegaRoll = (s1.roll - s0.roll) / spanSec
        val omegaYaw = (s1.yaw - s0.yaw) / spanSec
        val omegaMag = sqrt(omegaPitch * omegaPitch + omegaRoll * omegaRoll + omegaYaw * omegaYaw)
        val gCur = CameraMetadata.sampleGyroAt(samples, timeUs)
        val tiltLoad = if (gCur != null) hypot(sin(gCur.pitch), sin(gCur.roll)) * 0.35f else 0f
        return (1.0f + omegaMag * 0.28f + tiltLoad).coerceIn(0.5f, 6.0f)
    }

    internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /**
     * Renders the HUD dashboard into a reusable ARGB_8888 [Bitmap].
     */
    fun renderHudBitmap(
        bitmap: Bitmap,
        gyro: GyroSample?,
        gForce: Float,
        gpx: GpxPoint?,
        speedMult: Float,
    ) {
        bitmap.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 12, 16, 24)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 229, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 20f, 20f, bgPaint)
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 20f, 20f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255)
            textSize = 22f
            isFakeBoldText = true
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 205, 220)
            textSize = 20f
        }

        val gStr = String.format(Locale.US, "%.2f G", gForce)
        val speedTag = if (kotlin.math.abs(speedMult - 1f) > 0.05f) {
            String.format(Locale.US, "  •  %.1fx", speedMult)
        } else ""
        c.drawText("GEAR360 TELEMETRY$speedTag", 22f, 34f, titlePaint)

        if (gpx != null) {
            val kmh = String.format(Locale.US, "%.1f km/h", gpx.speedKmh)
            val ele = String.format(Locale.US, "ALT %.0f m", gpx.ele)
            c.drawText("$kmh   |   $gStr", 22f, 70f, valPaint)
            val rollDeg = Math.toDegrees((gyro?.roll ?: 0f).toDouble()).toInt()
            val pitchDeg = Math.toDegrees((gyro?.pitch ?: 0f).toDouble()).toInt()
            c.drawText("$ele   •   PITCH ${pitchDeg}°   ROLL ${rollDeg}°", 22f, 100f, subPaint)
        } else {
            val rollDeg = Math.toDegrees((gyro?.roll ?: 0f).toDouble()).toInt()
            val pitchDeg = Math.toDegrees((gyro?.pitch ?: 0f).toDouble()).toInt()
            val yawDeg = Math.toDegrees((gyro?.yaw ?: 0f).toDouble()).toInt()
            c.drawText("$gStr   |   ROLL ${rollDeg}°", 22f, 70f, valPaint)
            c.drawText("PITCH ${pitchDeg}°   •   YAW ${yawDeg}°", 22f, 100f, subPaint)
        }
    }

    /**
     * OpenGL ES 3.0 alpha-blended overlay quad for burning the HUD badge into exported MP4 frames.
     */
    class GlHudOverlay {
        private var program = 0
        private var vao = 0
        private var texId = 0
        private var uRectLoc = -1
        private val bitmap = Bitmap.createBitmap(460, 118, Bitmap.Config.ARGB_8888)
        private var lastUpdateUs = -1_000_000L

        fun create() {
            program = Gl.program(
                """#version 300 es
                uniform vec4 uRect; // x0, y0, x1, y1 in NDC (-1..1)
                out vec2 vUv;
                void main() {
                    vec2 pos = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1);
                    vUv = vec2(pos.x, 1.0 - pos.y);
                    gl_Position = vec4(mix(uRect.xy, uRect.zw, pos), 0.0, 1.0);
                }
                """,
                """#version 300 es
                precision mediump float;
                in vec2 vUv;
                uniform sampler2D uTex;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(uTex, vUv);
                }
                """,
            )
            uRectLoc = GLES30.glGetUniformLocation(program, "uRect")

            val vaos = IntArray(1)
            GLES30.glGenVertexArrays(1, vaos, 0)
            vao = vaos[0]

            val texs = IntArray(1)
            GLES30.glGenTextures(1, texs, 0)
            texId = texs[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        fun draw(
            outWidth: Int,
            outHeight: Int,
            ptsUs: Long,
            gyro: GyroSample?,
            gForce: Float,
            gpx: GpxPoint?,
            speedMult: Float,
        ) {
            // Refresh HUD bitmap texture at ~10 Hz to avoid per-frame CPU overhead
            if (lastUpdateUs < 0L || kotlin.math.abs(ptsUs - lastUpdateUs) >= 100_000L) {
                lastUpdateUs = ptsUs
                renderHudBitmap(bitmap, gyro, gForce, gpx, speedMult)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            }

            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(program)

            // Position badge in bottom-left corner with responsive sizing
            val badgeW = if (outWidth >= outHeight) 0.62f else 0.84f
            val badgeH = badgeW * (bitmap.height.toFloat() / bitmap.width) * (outWidth.toFloat() / outHeight)
            val x0 = -0.92f
            val y0 = -0.90f
            GLES30.glUniform4f(uRectLoc, x0, y0, x0 + badgeW, (y0 + badgeH).coerceAtMost(-0.55f))

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        fun release() {
            if (program != 0) GLES30.glDeleteProgram(program)
            if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            if (texId != 0) GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
            bitmap.recycle()
        }
    }
}
