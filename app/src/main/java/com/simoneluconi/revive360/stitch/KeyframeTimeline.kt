package com.simoneluconi.revive360.stitch

import kotlin.math.PI

/**
 * A camera orientation keyframe on a 360° video timeline (Insta360 Reframe style).
 */
data class Keyframe(
    val timeUs: Long,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val fov: Float = 90f,
    val projection: Projection = Projection.RECTILINEAR,
    /** TimeShift playback/export speed multiplier (0.25x .. 16x). */
    val speed: Float = 1f,
)

/**
 * Automated Insta360 "Shot Lab / FlashCut" camera motion & TimeShift templates.
 */
enum class ShotTemplate(val label: String) {
    ASTEROID_INTRO("Planet Intro"),
    ORBIT_360("360° Orbit"),
    TIMESHIFT_4X("TimeShift 4x"),
    AUTO_TRACK("Auto-Track"),
    ME_MODE("Me Mode");
}

/**
 * Target aspect ratios for Reframe video exports (TikTok/Reels 9:16, YouTube 16:9, Instagram 1:1).
 */
enum class AspectPreset(val label: String, val width: Int, val height: Int) {
    VERTICAL_9_16("9:16", 1080, 1920),
    HORIZONTAL_16_9("16:9", 1920, 1080),
    SQUARE_1_1("1:1", 1080, 1080),
    /** The export follows the source's own size ([com.simoneluconi.revive360.media.ProcessingPolicy]). */
    EQUIRECT_360("360°", 3840, 1920);

    val ratio: Float get() = width.toFloat() / height
}

/**
 * Smoothly interpolates [ViewState] across sorted [Keyframe]s using Hermite smoothstep (`3t² - 2t³`)
 * and shortest-arc angular wrapping for `yaw` and `roll`.
 */
object KeyframeTimeline {
    fun evaluate(
        keyframes: List<Keyframe>,
        timeUs: Long,
        fallback: ViewState = ViewState(),
        level: FloatArray = Mat3.identity(),
    ): ViewState {
        if (keyframes.isEmpty()) return fallback.copy(level = level)
        val sorted = if (keyframes.size > 1) keyframes.sortedBy { it.timeUs } else keyframes
        if (timeUs <= sorted.first().timeUs) return sorted.first().toViewState(level)
        if (timeUs >= sorted.last().timeUs) return sorted.last().toViewState(level)

        var idx = 0
        while (idx + 1 < sorted.size && sorted[idx + 1].timeUs < timeUs) idx++
        val a = sorted[idx]
        val b = sorted[idx + 1]
        val span = (b.timeUs - a.timeUs).coerceAtLeast(1L)
        val linearT = ((timeUs - a.timeUs).toFloat() / span).coerceIn(0f, 1f)
        // Hermite smoothstep easing for natural camera motion
        val t = linearT * linearT * (3f - 2f * linearT)

        return ViewState(
            yaw = lerpAngle(a.yaw, b.yaw, t),
            pitch = a.pitch + (b.pitch - a.pitch) * t,
            roll = lerpAngle(a.roll, b.roll, t),
            fov = a.fov + (b.fov - a.fov) * t,
            projection = if (t < 0.5f) a.projection else b.projection,
            level = level,
        )
    }

    /** Inserts or replaces a keyframe within [mergeWindowUs] of [kf.timeUs], returning the sorted list. */
    fun upsert(keyframes: List<Keyframe>, kf: Keyframe, mergeWindowUs: Long = 250_000L): List<Keyframe> {
        val filtered = keyframes.filterNot { kotlin.math.abs(it.timeUs - kf.timeUs) <= mergeWindowUs }
        return (filtered + kf).sortedBy { it.timeUs }
    }

    /** Evaluates the smoothly interpolated TimeShift speed multiplier at [timeUs]. */
    fun evaluateSpeed(keyframes: List<Keyframe>, timeUs: Long, defaultSpeed: Float = 1f): Float {
        if (keyframes.isEmpty()) return defaultSpeed
        val sorted = if (keyframes.size > 1) keyframes.sortedBy { it.timeUs } else keyframes
        if (timeUs <= sorted.first().timeUs) return sorted.first().speed
        if (timeUs >= sorted.last().timeUs) return sorted.last().speed
        var idx = 0
        while (idx + 1 < sorted.size && sorted[idx + 1].timeUs < timeUs) idx++
        val a = sorted[idx]
        val b = sorted[idx + 1]
        val span = (b.timeUs - a.timeUs).coerceAtLeast(1L)
        val linearT = ((timeUs - a.timeUs).toFloat() / span).coerceIn(0f, 1f)
        val t = linearT * linearT * (3f - 2f * linearT)
        return (a.speed + (b.speed - a.speed) * t).coerceIn(0.25f, 16f)
    }

    /** Generates a complete set of [Keyframe]s for an Insta360 [ShotTemplate]. */
    fun buildTemplate(
        template: ShotTemplate,
        durationUs: Long,
        baseYaw: Float = 0f,
        gyroSamples: List<GyroSample> = emptyList(),
    ): List<Keyframe> {
        val dur = durationUs.coerceAtLeast(1_000_000L)
        return when (template) {
            ShotTemplate.ASTEROID_INTRO -> listOf(
                Keyframe(0L, yaw = baseYaw, pitch = (PI / 2.0).toFloat(), roll = -PI.toFloat(), fov = 265f, projection = Projection.STEREOGRAPHIC),
                Keyframe((dur * 0.38).toLong(), yaw = baseYaw, pitch = 0f, roll = 0f, fov = 92f, projection = Projection.RECTILINEAR),
                Keyframe(dur, yaw = baseYaw, pitch = 0f, roll = 0f, fov = 88f, projection = Projection.RECTILINEAR),
            )
            ShotTemplate.ORBIT_360 -> listOf(
                Keyframe(0L, yaw = baseYaw, pitch = 0.08f, fov = 95f),
                Keyframe(dur / 3, yaw = baseYaw + (2.0 * PI / 3.0).toFloat(), pitch = 0.05f, fov = 95f),
                Keyframe((dur * 2) / 3, yaw = baseYaw + (4.0 * PI / 3.0).toFloat(), pitch = 0.05f, fov = 95f),
                Keyframe(dur, yaw = baseYaw, pitch = 0.08f, fov = 95f),
            )
            ShotTemplate.TIMESHIFT_4X -> listOf(
                Keyframe(0L, yaw = baseYaw, pitch = 0f, fov = 92f, speed = 1f),
                Keyframe((dur * 0.15).toLong(), yaw = baseYaw + 0.35f, pitch = 0f, fov = 105f, speed = 4f),
                Keyframe((dur * 0.85).toLong(), yaw = baseYaw - 0.35f, pitch = 0f, fov = 105f, speed = 4f),
                Keyframe(dur, yaw = baseYaw, pitch = 0f, fov = 92f, speed = 1f),
            )
            ShotTemplate.AUTO_TRACK -> {
                if (gyroSamples.size < 2) {
                    listOf(
                        Keyframe(0L, yaw = baseYaw, pitch = 0.05f, fov = 100f),
                        Keyframe(dur / 2, yaw = baseYaw + 0.4f, pitch = 0f, fov = 90f),
                        Keyframe(dur, yaw = baseYaw, pitch = 0.05f, fov = 100f),
                    )
                } else {
                    val steps = 6
                    val startYaw = gyroSamples.first().yaw
                    (0..steps).map { i ->
                        val tUs = (dur * i) / steps
                        val g = CameraMetadata.sampleGyroAt(gyroSamples, tUs)
                        val deltaYaw = if (g != null) (g.yaw - startYaw) * 0.6f else 0f
                        Keyframe(tUs, yaw = baseYaw + deltaYaw, pitch = 0.04f, fov = 95f)
                    }
                }
            }
            ShotTemplate.ME_MODE -> {
                // Locks a wide selfie angle onto the stick holder (baseYaw), counter-steering stick sway
                val steps = 8
                val startPitch = gyroSamples.firstOrNull()?.pitch ?: 0f
                (0..steps).map { i ->
                    val tUs = (dur * i) / steps
                    val g = CameraMetadata.sampleGyroAt(gyroSamples, tUs)
                    val swayPitch = if (g != null) ((g.pitch - startPitch) * 0.35f).coerceIn(-0.25f, 0.25f) else 0f
                    Keyframe(tUs, yaw = baseYaw, pitch = -0.08f - swayPitch, fov = 108f)
                }
            }
        }
    }

    private fun Keyframe.toViewState(level: FloatArray) = ViewState(
        yaw = yaw,
        pitch = pitch,
        roll = roll,
        fov = fov,
        projection = projection,
        level = level,
    )

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var diff = (b - a) % twoPi
        if (diff > PI.toFloat()) diff -= twoPi
        if (diff < -PI.toFloat()) diff += twoPi
        return a + diff * t
    }
}
