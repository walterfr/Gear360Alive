package com.simoneluconi.revive360.media

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Reads the device's declared limits and a file's video track for [ProcessingPolicy]. */
object DeviceCodecs {
    /** Secure codecs are left out: they cannot read the app's files. */
    private fun capabilities(mime: String, encoder: Boolean): List<CodecCapability> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encoder && !it.name.endsWith(".secure") && mime in it.supportedTypes }
            .mapNotNull { it.getCapabilitiesForType(mime).videoCapabilities }
            .map { caps ->
                CodecCapability(
                    supportsSize = { w, h -> caps.isSizeSupported(w, h) },
                    supportsRate = { w, h, fps -> caps.areSizeAndRateSupported(w, h, fps.toDouble()) },
                )
            }

    fun decide(file: File, targetWidth: Int?, targetHeight: Int?, alreadyStitched: Boolean): ProcessingDecision {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            return ProcessingPolicy.decide(
                VideoInfo(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), fps),
                capabilities(format.getString(MediaFormat.KEY_MIME)!!, encoder = false),
                // The export always encodes H.264 (VideoStitcher)
                capabilities(MediaFormat.MIMETYPE_VIDEO_AVC, encoder = true),
                targetWidth,
                targetHeight,
                alreadyStitched,
            )
        } finally {
            extractor.release()
        }
    }
}
