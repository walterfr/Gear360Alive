package com.simoneluconi.revive360.media

/** What a video file is, as its container says. */
data class VideoInfo(val width: Int, val height: Int, val fps: Int)

/** What one device codec declares. Functions, so tests can state any device. */
class CodecCapability(
    val supportsSize: (width: Int, height: Int) -> Boolean,
    val supportsRate: (width: Int, height: Int, fps: Int) -> Boolean,
)

/**
 * What the app does with a video on this device. Nothing here depends on one camera mode or device:
 * a device that declares more gets more, with no code change.
 */
data class ProcessingDecision(
    val kind: Kind,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    /** False: the device declares less than this speed, so the export takes longer than the video. */
    val realtime: Boolean = true,
) {
    enum class Kind {
        /** Goes ahead at the size asked for. */
        PROCESS,

        /** Goes ahead at a smaller size: the encoder declares less than the size asked for. */
        REDUCED_OUTPUT,

        /** The decoder declares less than this file. It may still work: warn, and let the user try. */
        BEYOND_DECLARED_DECODER,

        /** One lens or already stitched: there is nothing to stitch. */
        NOT_DUAL_FISHEYE,

        /** No encoder fits any size the app would use. */
        NO_ENCODER,
    }

    val canExport: Boolean get() = kind != Kind.NOT_DUAL_FISHEYE && kind != Kind.NO_ENCODER
}

object ProcessingPolicy {
    /** Longest sides to fall back to, largest first. */
    private val LONG_SIDES = listOf(3840, 2880, 2560, 1920, 1440, 1280)

    /** [targetWidth] and [targetHeight] null: the 360 export, at the source's own size. */
    fun decide(
        source: VideoInfo,
        decoders: List<CodecCapability>,
        encoders: List<CodecCapability>,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        alreadyStitched: Boolean = false,
    ): ProcessingDecision {
        if (alreadyStitched || source.width != source.height * 2) {
            return ProcessingDecision(ProcessingDecision.Kind.NOT_DUAL_FISHEYE)
        }
        val wantedWidth = targetWidth ?: source.width
        val wantedHeight = targetHeight ?: source.height
        val (outWidth, outHeight, encoder) = sizes(wantedWidth, wantedHeight).firstNotNullOfOrNull { (w, h) ->
            encoders.firstOrNull { it.supportsSize(w, h) }?.let { Triple(w, h, it) }
        } ?: return ProcessingDecision(ProcessingDecision.Kind.NO_ENCODER)

        val decoderFits = decoders.filter { it.supportsSize(source.width, source.height) }
        val realtime = decoderFits.any { it.supportsRate(source.width, source.height, source.fps) } &&
            encoder.supportsRate(outWidth, outHeight, source.fps)
        val kind = when {
            decoderFits.isEmpty() -> ProcessingDecision.Kind.BEYOND_DECLARED_DECODER
            outWidth != wantedWidth || outHeight != wantedHeight -> ProcessingDecision.Kind.REDUCED_OUTPUT
            else -> ProcessingDecision.Kind.PROCESS
        }
        return ProcessingDecision(kind, outWidth, outHeight, realtime)
    }

    /** The size asked for, then the same shape at each smaller long side, always even. */
    private fun sizes(width: Int, height: Int): List<Pair<Int, Int>> {
        val longest = maxOf(width, height)
        return listOf(width to height) + LONG_SIDES.filter { it < longest }.map { side ->
            even(width * side / longest) to even(height * side / longest)
        }
    }

    private fun even(value: Int): Int = value and 1.inv()
}
