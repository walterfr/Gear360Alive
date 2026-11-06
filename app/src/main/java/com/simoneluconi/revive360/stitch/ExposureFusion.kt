package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Insta360 "PureShot HDR" (Mertens Exposure Fusion) & "Starlapse" (Max-Light Stacking).
 *
 * Implements Mertens, Kautz & Van Reeth (2007) Exposure Fusion combining local Laplacian contrast,
 * RGB saturation, and Gaussian well-exposedness without requiring HDR radiance calibration, plus
 * Max-Light stacking for night-sky star trails.
 */
object ExposureFusion {
    private const val SIGMA_SQ_2 = 2f * 0.20f * 0.20f

    /**
     * Fuses multiple exposure-bracketed frames (`ARGB_8888` [IntArray]s of size `width * height`)
     * using Mertens Exposure Fusion with spatially smoothed weight maps.
     */
    fun fuseMertens(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        contrastPower: Float = 1f,
        saturationPower: Float = 1f,
        exposednessPower: Float = 1f,
    ): IntArray {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val total = width * height
        if (frames.size == 1) return frames.first().clone()

        val numFrames = frames.size
        val weights = Array(numFrames) { FloatArray(total) }
        val luma = FloatArray(total)

        for (k in 0 until numFrames) {
            val px = frames[k]
            val wk = weights[k]
            for (i in 0 until total) {
                val c = px[i]
                val r = (c shr 16 and 0xFF) / 255f
                val g = (c shr 8 and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            for (y in 0 until height) {
                val row = y * width
                val yUp = if (y > 0) (y - 1) * width else row
                val yDn = if (y + 1 < height) (y + 1) * width else row
                for (x in 0 until width) {
                    val i = row + x
                    val xLt = if (x > 0) x - 1 else x
                    val xRt = if (x + 1 < width) x + 1 else x

                    // 1. Contrast: 4-neighbor Laplacian magnitude on luminance
                    val lap = abs(
                        4f * luma[i] - luma[row + xLt] - luma[row + xRt] - luma[yUp + x] - luma[yDn + x],
                    )
                    val c = px[i]
                    val r = (c shr 16 and 0xFF) / 255f
                    val g = (c shr 8 and 0xFF) / 255f
                    val b = (c and 0xFF) / 255f

                    // 2. Saturation: standard deviation of R, G, B
                    val mean = (r + g + b) / 3f
                    val dr = r - mean
                    val dg = g - mean
                    val db = b - mean
                    val sat = sqrt((dr * dr + dg * dg + db * db) / 3f)

                    // 3. Well-exposedness: Gaussian centered at 0.5 per channel
                    val er = r - 0.5f
                    val eg = g - 0.5f
                    val eb = b - 0.5f
                    val expWeight = exp(-(er * er + eg * eg + eb * eb) / SIGMA_SQ_2)

                    val wC = if (contrastPower == 1f) (lap + 0.02f) else (lap + 0.02f).pow(contrastPower)
                    val wS = if (saturationPower == 1f) (sat + 0.02f) else (sat + 0.02f).pow(saturationPower)
                    val wE = if (exposednessPower == 1f) expWeight else expWeight.pow(exposednessPower)
                    wk[i] = wC * wS * wE + 1e-6f
                }
            }
            // Smooth weight map with 3x3 separable box pass to prevent seam halos
            smoothWeights3x3(wk, luma, width, height)
        }

        val out = IntArray(total)
        for (i in 0 until total) {
            var sumW = 0f
            for (k in 0 until numFrames) sumW += weights[k][i]
            val invW = if (sumW > 1e-9f) 1f / sumW else 1f / numFrames
            var accR = 0f
            var accG = 0f
            var accB = 0f
            for (k in 0 until numFrames) {
                val nw = weights[k][i] * invW
                val c = frames[k][i]
                accR += (c shr 16 and 0xFF) * nw
                accG += (c shr 8 and 0xFF) * nw
                accB += (c and 0xFF) * nw
            }
            val ir = accR.toInt().coerceIn(0, 255)
            val ig = accG.toInt().coerceIn(0, 255)
            val ib = accB.toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
        }
        return out
    }

    /**
     * Zero-copy in-place Mertens Exposure Fusion over virtual EV brackets (`stops`).
     * Uses only a 2-row rolling window (`2 * width` ints = ~31 KB for 3888px) so full 30MP C200
     * dual-fisheye images can be fused at 100% native resolution without `OutOfMemoryError`.
     */
    fun fuseSingleShotMertensInPlace(
        px: IntArray,
        width: Int,
        height: Int,
        stops: FloatArray = floatArrayOf(-1.1f, 0f, 1.1f),
    ) {
        val kCount = stops.size
        val lutI = Array(kCount) { k -> buildStopLut(stops[k]) }
        val lutF = Array(kCount) { k -> FloatArray(256) { v -> lutI[k][v] / 255f } }
        val lutExp = Array(kCount) { k ->
            FloatArray(256) { v ->
                val d = lutF[k][v] - 0.5f
                exp(-(d * d) / SIGMA_SQ_2)
            }
        }

        val prevRow = px.copyOfRange(0, width)
        val curRow = IntArray(width)

        fun lumaOf(c: Int, fLut: FloatArray): Float {
            return 0.299f * fLut[c shr 16 and 0xFF] +
                0.587f * fLut[c shr 8 and 0xFF] +
                0.114f * fLut[c and 0xFF]
        }

        for (y in 0 until height) {
            val rowOff = y * width
            System.arraycopy(px, rowOff, curRow, 0, width)
            val nextOff = if (y + 1 < height) (y + 1) * width else rowOff

            for (x in 0 until width) {
                val c = curRow[x]
                val cLt = curRow[if (x > 0) x - 1 else 0]
                val cRt = curRow[if (x + 1 < width) x + 1 else width - 1]
                val cUp = prevRow[x]
                val cDn = px[nextOff + x]

                val r8 = c shr 16 and 0xFF
                val g8 = c shr 8 and 0xFF
                val b8 = c and 0xFF

                var sumW = 0f
                var accR = 0f
                var accG = 0f
                var accB = 0f

                for (k in 0 until kCount) {
                    val fL = lutF[k]
                    val iL = lutI[k]
                    val eL = lutExp[k]

                    val r = fL[r8]
                    val g = fL[g8]
                    val b = fL[b8]
                    val lCenter = 0.299f * r + 0.587f * g + 0.114f * b
                    val lap = abs(
                        4f * lCenter - lumaOf(cLt, fL) - lumaOf(cRt, fL) - lumaOf(cUp, fL) - lumaOf(cDn, fL),
                    )
                    val mean = (r + g + b) * 0.33333334f
                    val dr = r - mean
                    val dg = g - mean
                    val db = b - mean
                    val sat = sqrt((dr * dr + dg * dg + db * db) * 0.33333334f)
                    val expW = eL[r8] * eL[g8] * eL[b8]

                    val wk = (lap + 0.03f) * (sat + 0.03f) * expW + 1e-6f
                    sumW += wk
                    accR += iL[r8] * wk
                    accG += iL[g8] * wk
                    accB += iL[b8] * wk
                }

                val invW = 1f / sumW
                val outR = (accR * invW).toInt().coerceIn(0, 255)
                val outG = (accG * invW).toInt().coerceIn(0, 255)
                val outB = (accB * invW).toInt().coerceIn(0, 255)
                px[rowOff + x] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
            System.arraycopy(curRow, 0, prevRow, 0, width)
        }
    }

    /**
     * Starlapse Max-Light stacking: combines sequential night-sky frames by taking per-channel
     * maxima while suppressing low-amplitude sensor dark noise below [noiseFloor].
     */
    fun stackStarlapseMaxLight(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        noiseFloor: Int = 8,
    ): IntArray {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val total = width * height
        val out = frames.first().clone()
        for (k in 1 until frames.size) {
            val fk = frames[k]
            for (i in 0 until total) {
                val c0 = out[i]
                val c1 = fk[i]
                val r0 = c0 shr 16 and 0xFF
                val g0 = c0 shr 8 and 0xFF
                val b0 = c0 and 0xFF
                val r1 = c1 shr 16 and 0xFF
                val g1 = c1 shr 8 and 0xFF
                val b1 = c1 and 0xFF
                val luma1 = (r1 * 77 + g1 * 150 + b1 * 29) shr 8
                if (luma1 <= noiseFloor) continue
                val rm = max(r0, r1)
                val gm = max(g0, g1)
                val bm = max(b0, b1)
                out[i] = (0xFF shl 24) or (rm shl 16) or (gm shl 8) or bm
            }
        }
        return out
    }

    /**
     * Incrementally stacks [next] onto [accumulator] in-place using Starlapse Max-Light so an arbitrary
     * number of high-resolution photos can be stacked without holding all frames in RAM simultaneously.
     */
    fun accumulateStarlapseMaxLightInPlace(
        accumulator: IntArray,
        next: IntArray,
        noiseFloor: Int = 8,
    ) {
        val len = minOf(accumulator.size, next.size)
        for (i in 0 until len) {
            val c1 = next[i]
            val r1 = c1 shr 16 and 0xFF
            val g1 = c1 shr 8 and 0xFF
            val b1 = c1 and 0xFF
            val luma1 = (r1 * 77 + g1 * 150 + b1 * 29) shr 8
            if (luma1 <= noiseFloor) continue
            val c0 = accumulator[i]
            val rm = max(c0 shr 16 and 0xFF, r1)
            val gm = max(c0 shr 8 and 0xFF, g1)
            val bm = max(c0 and 0xFF, b1)
            accumulator[i] = (0xFF shl 24) or (rm shl 16) or (gm shl 8) or bm
        }
    }

    /**
     * Generates virtual exposure brackets (in stops, e.g. `-1.1 EV`, `0 EV`, `+1.1 EV`) from a single
     * buffer in linear light so Mertens Exposure Fusion can recover shadow detail and highlight roll-off.
     */
    fun synthesizeVirtualBracket(
        pixels: IntArray,
        stops: FloatArray = floatArrayOf(-1.1f, 0f, 1.1f),
    ): List<IntArray> {
        return stops.map { ev ->
            if (abs(ev) < 1e-4f) {
                pixels.clone()
            } else {
                val lut = buildStopLut(ev)
                IntArray(pixels.size) { i ->
                    val c = pixels[i]
                    val r = lut[c shr 16 and 0xFF]
                    val g = lut[c shr 8 and 0xFF]
                    val b = lut[c and 0xFF]
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    /**
     * Applies Mertens PureShot HDR fusion to a [Bitmap] at 100% native resolution using the
     * zero-copy rolling-window `fuseSingleShotMertensInPlace` implementation.
     */
    fun enhanceBitmapPureShot(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        fuseSingleShotMertensInPlace(px, w, h)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Applies Mertens PureShot HDR to both lenses of a [DualFisheyeImage] at native resolution. */
    fun enhanceDualFisheyePureShot(image: DualFisheyeImage): DualFisheyeImage {
        val frontHdr = enhanceBitmapPureShot(image.front)
        val backHdr = enhanceBitmapPureShot(image.back)
        return DualFisheyeImage(frontHdr, backHdr, image.gains, image.model)
    }

    /**
     * Fuses a real multi-shot EV bracket of dual-fisheye files (`-EV`, `0 EV`, `+EV`) using Mertens
     * Exposure Fusion on each hemisphere.
     */
    fun fuseDualFisheyeBracketFiles(files: List<File>, model: DualFisheye, maxSide: Int = 2048): DualFisheyeImage {
        require(files.isNotEmpty()) { "Bracket files must not be empty" }
        val loaded = files.map { DualFisheyeImage.load(it, model, maxSide) }
        try {
            val side = loaded.first().side
            val fronts = loaded.map { img ->
                IntArray(side * side).also { img.front.getPixels(it, 0, side, 0, 0, side, side) }
            }
            val backs = loaded.map { img ->
                IntArray(side * side).also { img.back.getPixels(it, 0, side, 0, 0, side, side) }
            }
            val fusedFrontPx = fuseMertens(fronts, side, side)
            val fusedBackPx = fuseMertens(backs, side, side)
            val frontBmp = Bitmap.createBitmap(fusedFrontPx, side, side, Bitmap.Config.ARGB_8888)
            val backBmp = Bitmap.createBitmap(fusedBackPx, side, side, Bitmap.Config.ARGB_8888)
            val mid = loaded[loaded.size / 2]
            return DualFisheyeImage(frontBmp, backBmp, mid.gains, mid.model)
        } finally {
            loaded.forEach { it.recycle() }
        }
    }

    private fun buildStopLut(ev: Float): IntArray {
        if (abs(ev) < 1e-4f) return IntArray(256) { it }
        val gain = 2.0.pow(ev.toDouble()).toFloat()
        return IntArray(256) { v ->
            val lin = (v / 255f).pow(2.2f) * gain
            val mapped = lin / (1f + 0.25f * max(0f, lin - 0.8f))
            (mapped.pow(1f / 2.2f) * 255f).toInt().coerceIn(0, 255)
        }
    }

    private fun smoothWeights3x3(w: FloatArray, tmp: FloatArray, width: Int, height: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val xl = if (x > 0) x - 1 else x
                val xr = if (x + 1 < width) x + 1 else x
                tmp[row + x] = (w[row + xl] + w[row + x] * 2f + w[row + xr]) * 0.25f
            }
        }
        for (y in 0 until height) {
            val row = y * width
            val yu = if (y > 0) (y - 1) * width else row
            val yd = if (y + 1 < height) (y + 1) * width else row
            for (x in 0 until width) {
                w[row + x] = (tmp[yu + x] + tmp[row + x] * 2f + tmp[yd + x]) * 0.25f
            }
        }
    }
}
