package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Insta360 "Deep Track 360" & "Me Mode" visual subject tracker on the 360° dual-fisheye sphere.
 *
 * Projects a gnomonic (rectilinear) patch around `(yaw, pitch)` directly from the dual-fisheye frame
 * using the exact shader view convention (`d = level * (Ry(yaw) * Rx(pitch) * r)`), computes a
 * zero-mean unit-variance luminance template + 16-bin chromaticity histogram, and tracks the target
 * across frames via coarse-to-fine Normalized Cross-Correlation (NCC) + histogram intersection.
 */
object DeepTracker360 {
    private const val PATCH_SIDE = 24
    private const val HIST_BINS = 16

    data class PatchDescriptor(
        val lumaNorm: FloatArray,
        val hist: FloatArray,
        val rawLumaStd: Float = 0f,
        val meanSaturation: Float = 0f,
    )

    /**
     * Extracts a [PATCH_SIDE]x[PATCH_SIDE] gnomonic patch centered at `(yaw, pitch)` (radians)
     * with angular span [angularSpanRad] from a dual-fisheye pixel buffer (`width = 2 * side, height = side`).
     */
    fun extractDescriptor(
        pixels: IntArray,
        width: Int,
        height: Int,
        model: DualFisheye,
        yaw: Float,
        pitch: Float,
        angularSpanRad: Float = 0.38f,
        level: FloatArray = Mat3.identity(),
    ): PatchDescriptor {
        val n = PATCH_SIDE * PATCH_SIDE
        val luma = FloatArray(n)
        val hist = FloatArray(HIST_BINS)
        // Match ViewState.viewMatrix(): Ry(yaw) * Rx(pitch), then apply world horizon lock (level)
        val viewRot = Mat3.multiply(Mat3.rotation(yaw, 0f, 0f), Mat3.rotation(0f, pitch, 0f))
        val rot = Mat3.multiply(level, viewRot)
        val backRot = model.backMatrix()
        val halfSpan = kotlin.math.tan(angularSpanRad * 0.5).toFloat()

        var sumL = 0f
        var sumSat = 0f
        var histSum = 0f
        var idx = 0
        for (py in 0 until PATCH_SIDE) {
            val ny = (1f - 2f * (py + 0.5f) / PATCH_SIDE) * halfSpan
            for (px in 0 until PATCH_SIDE) {
                val nx = (2f * (px + 0.5f) / PATCH_SIDE - 1f) * halfSpan
                val invLen = 1f / sqrt(nx * nx + ny * ny + 1f)
                val d = Mat3.apply(rot, nx * invLen, ny * invLen, invLen)
                val rgb = sampleDualFisheye(pixels, width, height, model, backRot, d[0], d[1], d[2])
                val r = (rgb shr 16 and 0xFF) / 255f
                val g = (rgb shr 8 and 0xFF) / 255f
                val b = (rgb and 0xFF) / 255f
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                luma[idx++] = y
                sumL += y

                // Chromaticity bin (angle in RG-opponent space weighted by saturation)
                val rg = r - g
                val by = 0.5f * (r + g) - b
                val sat = hypot(rg, by)
                sumSat += sat
                val angle = ((atan2(by, rg) + PI) / (2.0 * PI)).toFloat().coerceIn(0f, 0.9999f)
                val bin = (angle * HIST_BINS).toInt().coerceIn(0, HIST_BINS - 1)
                val w = 0.02f + sat
                hist[bin] += w
                histSum += w
            }
        }

        val mean = sumL / n
        var varSum = 0f
        for (i in 0 until n) {
            val d = luma[i] - mean
            luma[i] = d
            varSum += d * d
        }
        val rawStd = sqrt(varSum / n)
        val invStd = if (varSum > 1e-5f) 1f / sqrt(varSum) else 0f
        for (i in 0 until n) luma[i] *= invStd
        if (histSum > 1e-5f) {
            for (i in 0 until HIST_BINS) hist[i] /= histSum
        }
        return PatchDescriptor(luma, hist, rawLumaStd = rawStd, meanSaturation = sumSat / n)
    }

    /** Combined NCC + histogram intersection similarity in `[-1, 1]`. */
    fun similarity(a: PatchDescriptor, b: PatchDescriptor): Float {
        var ncc = 0f
        for (i in a.lumaNorm.indices) ncc += a.lumaNorm[i] * b.lumaNorm[i]
        var histInt = 0f
        for (i in 0 until HIST_BINS) histInt += min(a.hist[i], b.hist[i])
        return 0.65f * ncc + 0.35f * histInt
    }

    /**
     * Searches around `(lastYaw, lastPitch)` in a two-stage coarse-to-fine grid to locate [ref].
     */
    fun trackStep(
        pixels: IntArray,
        width: Int,
        height: Int,
        model: DualFisheye,
        ref: PatchDescriptor,
        lastYaw: Float,
        lastPitch: Float,
        searchRadiusRad: Float = 0.36f,
        level: FloatArray = Mat3.identity(),
    ): Pair<Float, Float> {
        var bestYaw = lastYaw
        var bestPitch = lastPitch
        var bestScore = -2f

        // Stage 1: Coarse 7x5 grid
        val coarseStepsYaw = 3
        val coarseStepsPitch = 2
        val stepYaw = searchRadiusRad / coarseStepsYaw
        val stepPitch = (searchRadiusRad * 0.7f) / coarseStepsPitch
        for (dy in -coarseStepsPitch..coarseStepsPitch) {
            val candPitch = (lastPitch + dy * stepPitch).coerceIn(-1.25f, 1.25f)
            for (dx in -coarseStepsYaw..coarseStepsYaw) {
                val candYaw = wrapAngle(lastYaw + dx * stepYaw)
                val desc = extractDescriptor(pixels, width, height, model, candYaw, candPitch, level = level)
                // Slight motion-prior regularization so flat areas don't drift wildly
                val penalty = 0.02f * (dx * dx + dy * dy) / (coarseStepsYaw * coarseStepsYaw)
                val score = similarity(ref, desc) - penalty
                if (score > bestScore) {
                    bestScore = score
                    bestYaw = candYaw
                    bestPitch = candPitch
                }
            }
        }

        // Stage 2: Fine 3x3 refinement around best coarse candidate
        val fineStep = stepYaw * 0.35f
        val centerYaw = bestYaw
        val centerPitch = bestPitch
        for (dy in -1..1) {
            val candPitch = (centerPitch + dy * fineStep).coerceIn(-1.25f, 1.25f)
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val candYaw = wrapAngle(centerYaw + dx * fineStep)
                val desc = extractDescriptor(pixels, width, height, model, candYaw, candPitch, level = level)
                val score = similarity(ref, desc)
                if (score > bestScore) {
                    bestScore = score
                    bestYaw = candYaw
                    bestPitch = candPitch
                }
            }
        }

        return bestYaw to bestPitch
    }

    /**
     * Detects the most salient subject hemisphere for Insta360 "Me Mode" (Selfie / Subject auto-framing).
     * Evaluates local luminance contrast + chromatic saturation on the front (`0`) vs rear (`PI`) hemisphere.
     */
    fun detectMeModeOrientation(
        pixels: IntArray,
        width: Int,
        height: Int,
        model: DualFisheye,
    ): Pair<Float, Float> {
        val candidates = listOf(
            0f to -0.06f,
            PI.toFloat() to -0.06f,
            0f to -0.18f,
            PI.toFloat() to -0.18f,
        )
        var best = candidates.first()
        var bestScore = -1f
        for (cand in candidates) {
            val desc = extractDescriptor(pixels, width, height, model, cand.first, cand.second, angularSpanRad = 0.65f)
            val score = desc.rawLumaStd * 2.0f + desc.meanSaturation
            if (score > bestScore) {
                bestScore = score
                best = cand
            }
        }
        return best
    }

    /**
     * Tracks a visual target starting at `(startYaw, startPitch)` at `startTimeUs` across `videoFile`,
     * returning smoothed [Keyframe]s ready for [KeyframeTimeline].
     */
    fun trackVideo(
        videoFile: File,
        model: DualFisheye,
        startYaw: Float,
        startPitch: Float,
        fov: Float,
        durationUs: Long,
        startTimeUs: Long = 0L,
        sampleIntervalUs: Long = 500_000L,
        gyroSamples: List<GyroSample> = emptyList(),
        flowState: Boolean = true,
    ): List<Keyframe> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val dur = durationUs.coerceAtLeast(1_000_000L)
            val keyframes = mutableListOf<Keyframe>()
            var currentYaw = startYaw
            var currentPitch = startPitch
            var refDescriptor: PatchDescriptor? = null

            var tUs = startTimeUs.coerceIn(0L, dur)
            while (tUs <= dur) {
                val level = if (flowState && gyroSamples.isNotEmpty()) {
                    CameraMetadata.sampleGyroAt(gyroSamples, tUs)?.horizonLockMatrix() ?: Mat3.identity()
                } else Mat3.identity()

                val bmp = runCatching {
                    retriever.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, 512, 256)
                }.getOrNull() ?: retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                if (bmp != null) {
                    val scaled = if (bmp.width > 512) {
                        val h = (bmp.height * 512) / bmp.width
                        Bitmap.createScaledBitmap(bmp, 512, h.coerceAtLeast(256), true)
                    } else bmp
                    val w = scaled.width
                    val h = scaled.height
                    val px = IntArray(w * h)
                    scaled.getPixels(px, 0, w, 0, 0, w, h)
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()

                    val currentRef = refDescriptor
                    if (currentRef == null) {
                        refDescriptor = extractDescriptor(px, w, h, model, currentYaw, currentPitch, level = level)
                    } else {
                        val (nextYaw, nextPitch) = trackStep(
                            pixels = px,
                            width = w,
                            height = h,
                            model = model,
                            ref = currentRef,
                            lastYaw = currentYaw,
                            lastPitch = currentPitch,
                            level = level,
                        )
                        // EMA temporal smoothing (0.65 new + 0.35 previous)
                        currentYaw = lerpAngle(currentYaw, nextYaw, 0.65f)
                        currentPitch = currentPitch * 0.35f + nextPitch * 0.65f
                        // Gently adapt appearance template (15% update) to handle lighting changes
                        val fresh = extractDescriptor(px, w, h, model, currentYaw, currentPitch, level = level)
                        for (i in currentRef.lumaNorm.indices) {
                            currentRef.lumaNorm[i] = currentRef.lumaNorm[i] * 0.85f + fresh.lumaNorm[i] * 0.15f
                        }
                        for (i in currentRef.hist.indices) {
                            currentRef.hist[i] = currentRef.hist[i] * 0.85f + fresh.hist[i] * 0.15f
                        }
                    }
                }
                keyframes.add(Keyframe(timeUs = tUs, yaw = currentYaw, pitch = currentPitch, fov = fov))
                if (tUs == dur) break
                tUs = (tUs + sampleIntervalUs).coerceAtMost(dur)
            }
            return keyframes
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampleDualFisheye(
        pixels: IntArray,
        width: Int,
        height: Int,
        model: DualFisheye,
        backRot: FloatArray,
        dx: Float,
        dy: Float,
        dz: Float,
    ): Int {
        val halfW = width / 2
        return if (dz >= 0f) {
            val th = acos(dz.coerceIn(-1f, 1f))
            val len = hypot(dx, dy)
            val dirX = if (len > 1e-6f) dx / len else 1f
            val dirY = if (len > 1e-6f) dy / len else 0f
            val u = (model.frontCenterX + model.radius(th) * dirX).coerceIn(0f, 0.999f)
            val v = (model.frontCenterY - model.radius(th) * dirY).coerceIn(0f, 0.999f)
            val ix = (u * halfW).toInt().coerceIn(0, halfW - 1)
            val iy = (v * height).toInt().coerceIn(0, height - 1)
            pixels[iy * width + ix]
        } else {
            val b = Mat3.apply(backRot, dx, dy, dz)
            val th = acos(b[2].coerceIn(-1f, 1f))
            val len = hypot(b[0], b[1])
            val dirX = if (len > 1e-6f) b[0] / len else 1f
            val dirY = if (len > 1e-6f) b[1] / len else 0f
            val u = (model.backCenterX + model.radius(th) * dirX).coerceIn(0f, 0.999f)
            val v = (model.backCenterY - model.radius(th) * dirY).coerceIn(0f, 0.999f)
            val ix = halfW + (u * halfW).toInt().coerceIn(0, halfW - 1)
            val iy = (v * height).toInt().coerceIn(0, height - 1)
            pixels[iy * width + ix.coerceIn(0, width - 1)]
        }
    }

    internal fun wrapAngle(a: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var r = a % twoPi
        if (r > PI.toFloat()) r -= twoPi
        if (r < -PI.toFloat()) r += twoPi
        return r
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        return wrapAngle(a + wrapAngle(b - a) * t)
    }
}
