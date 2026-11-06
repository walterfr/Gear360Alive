package com.simoneluconi.revive360.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.simoneluconi.revive360.stitch.AspectPreset
import com.simoneluconi.revive360.stitch.CameraMetadata
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.GyroSample
import com.simoneluconi.revive360.stitch.Keyframe
import com.simoneluconi.revive360.stitch.KeyframeTimeline
import com.simoneluconi.revive360.stitch.Mat3
import com.simoneluconi.revive360.stitch.OesSphereRenderer
import com.simoneluconi.revive360.stitch.ViewState
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 100% GPU hardware-accelerated video stitcher and Reframe exporter.
 *
 * Pipeline: `MediaExtractor` -> `MediaCodec` (Decoder) -> `SurfaceTexture` (OES) ->
 * `OesSphereRenderer` (applying factory OPAX calibration, `vrot` FlowState stabilization, and
 * interpolated [Keyframe] camera path) -> `EGLSurface` (`MediaCodec.createInputSurface()`) ->
 * `MediaMuxer` (with original audio track copied directly, and Spherical Video V1 UUID metadata
 * injected when exporting 360° equirectangular video).
 */
object VideoStitcher {
    data class ExportConfig(
        val input: File,
        val output: File,
        val preset: AspectPreset = AspectPreset.VERTICAL_9_16,
        val keyframes: List<Keyframe> = emptyList(),
        val defaultView: ViewState = ViewState(),
        val flowStateStabilization: Boolean = true,
        val nadirPatch: Boolean = false,
        val hudTelemetry: Boolean = false,
        val gpxTrack: List<TelemetryHud.GpxPoint> = emptyList(),
        val bitrate: Int = if (preset == AspectPreset.EQUIRECT_360) 28_000_000 else 14_000_000,
    )

    fun export(config: ExportConfig, onProgress: (Float) -> Unit = {}) {
        val model: DualFisheye = CameraMetadata.readCalibration(config.input)
        val extractor = MediaExtractor()
        extractor.setDataSource(config.input.path)

        var videoTrack = -1
        var audioTrack = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0) {
                videoTrack = i
                videoFormat = fmt
            } else if (mime.startsWith("audio/") && audioTrack < 0) {
                audioTrack = i
                audioFormat = fmt
            }
        }
        require(videoTrack >= 0 && videoFormat != null) { "No video track in ${config.input.name}" }
        val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        val fps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(15, 60)
        } else 30

        val gyroSamples: List<GyroSample> = if (config.flowStateStabilization || config.hudTelemetry) {
            CameraMetadata.readMp4Gyro(config.input, durationUs)
        } else emptyList()

        val outWidth = config.preset.width
        val outHeight = config.preset.height
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(config.output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var decoder: MediaCodec? = null
        var egl: EglEncoderSurface? = null
        var renderer: OesSphereRenderer? = null
        var hudOverlay: TelemetryHud.GlHudOverlay? = null
        var stThread: HandlerThread? = null
        var inputSurfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null

        try {
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            egl = EglEncoderSurface(encoderInputSurface)
            egl.makeCurrent()
            renderer = OesSphereRenderer(model, config.nadirPatch)
            val oesTex = renderer.create()
            if (config.hudTelemetry && config.preset != AspectPreset.EQUIRECT_360) {
                hudOverlay = TelemetryHud.GlHudOverlay().also { it.create() }
            }

            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            val frameSync = Object()
            var frameAvailable = false
            stThread = HandlerThread("g360-stitch-st").apply { start() }
            inputSurfaceTexture = SurfaceTexture(oesTex).apply {
                setOnFrameAvailableListener({
                    synchronized(frameSync) {
                        frameAvailable = true
                        frameSync.notifyAll()
                    }
                }, Handler(stThread.looper))
            }
            decoderSurface = Surface(inputSurfaceTexture)

            val inMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(videoFormat, decoderSurface, null, 0)
                start()
            }

            extractor.selectTrack(videoTrack)
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var muxerStarted = false
            var muxVideoTrack = -1
            var muxAudioTrack = -1
            val hasTimeShift = config.keyframes.any { kotlin.math.abs(it.speed - 1f) > 0.05f }
            val minFrameStepUs = ((1_000_000L / fps) * 85L) / 100L
            var outPtsUs = 0L
            var lastInPtsUs = -1L
            var lastEmittedOutPtsUs = -100_000L

            while (!encoderDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000L)
                    if (outIdx >= 0) {
                        val doRender = decInfo.size > 0
                        decoder.releaseOutputBuffer(outIdx, doRender)
                        if (doRender) {
                            synchronized(frameSync) {
                                var waits = 0
                                while (!frameAvailable && waits < 50) {
                                    frameSync.wait(10L)
                                    waits++
                                }
                                frameAvailable = false
                            }
                            inputSurfaceTexture.updateTexImage()
                            inputSurfaceTexture.getTransformMatrix(texMatrix)
                            val ptsUs = decInfo.presentationTimeUs
                            val speed = KeyframeTimeline.evaluateSpeed(config.keyframes, ptsUs)
                            if (lastInPtsUs >= 0L && ptsUs > lastInPtsUs) {
                                outPtsUs += ((ptsUs - lastInPtsUs) / speed).toLong().coerceAtLeast(1_000L)
                            }
                            lastInPtsUs = ptsUs

                            if (!hasTimeShift || outPtsUs - lastEmittedOutPtsUs >= minFrameStepUs) {
                                lastEmittedOutPtsUs = outPtsUs
                                val curGyro = if (gyroSamples.isNotEmpty()) {
                                    CameraMetadata.sampleGyroAt(gyroSamples, ptsUs)
                                } else null
                                val level = if (config.flowStateStabilization && curGyro != null) {
                                    curGyro.horizonLockMatrix()
                                } else Mat3.identity()

                                GLES30.glClearColor(0f, 0f, 0f, 1f)
                                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                                val view = KeyframeTimeline.evaluate(config.keyframes, ptsUs, config.defaultView, level)
                                if (config.preset == AspectPreset.EQUIRECT_360) {
                                    // Combina nivelamento de horizonte (vrot) com azimute inicial/keyframe (yaw)
                                    val orient = Mat3.multiply(level, Mat3.rotation(view.yaw, 0f, 0f))
                                    renderer.drawEquirectangular(outWidth, outHeight, orient, texMatrix)
                                } else {
                                    renderer.drawSphere(outWidth, outHeight, view, texMatrix)
                                    hudOverlay?.draw(
                                        outWidth = outWidth,
                                        outHeight = outHeight,
                                        ptsUs = ptsUs,
                                        gyro = curGyro,
                                        gForce = TelemetryHud.computeAngularGForce(gyroSamples, ptsUs),
                                        gpx = TelemetryHud.sampleGpxAt(config.gpxTrack, ptsUs),
                                        speedMult = speed,
                                    )
                                }
                                val finalPtsUs = if (hasTimeShift) outPtsUs else ptsUs
                                egl.setPresentationTime(finalPtsUs * 1_000L)
                                egl.swapBuffers()
                            }
                            if (durationUs > 0) onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 0.98f))
                        }
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                while (true) {
                    val encIdx = encoder.dequeueOutputBuffer(encInfo, if (decoderDone) 10_000L else 0L)
                    if (encIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null && !hasTimeShift) {
                            muxAudioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    } else if (encIdx >= 0) {
                        val encoded = encoder.getOutputBuffer(encIdx)
                        if (encoded != null && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0 && muxerStarted) {
                            encoded.position(encInfo.offset)
                            encoded.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxVideoTrack, encoded, encInfo)
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }

            // Copy original audio track directly without re-encoding
            if (muxerStarted && audioTrack >= 0 && muxAudioTrack >= 0) {
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val audioBuf = ByteBuffer.allocateDirect(256 * 1024)
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    val n = extractor.readSampleData(audioBuf, 0)
                    if (n < 0) break
                    audioInfo.offset = 0
                    audioInfo.size = n
                    audioInfo.presentationTimeUs = extractor.sampleTime
                    audioInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxAudioTrack, audioBuf, audioInfo)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { inputSurfaceTexture?.release() }
            runCatching { stThread?.quitSafely() }
            runCatching { hudOverlay?.release() }
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }

        if (config.preset == AspectPreset.EQUIRECT_360) {
            runCatching { injectSphericalVideoV1Uuid(config.output) }
        }
        onProgress(1f)
    }

    /**
     * Builds the Google/YouTube/Meta Spherical Video V1 `uuid` XML atom (`ffcc8263-f855-4a93-8814-587a02521fdd`).
     */
    internal fun buildSphericalUuidAtom(): ByteArray {
        val uuid = byteArrayOf(
            0xff.toByte(), 0xcc.toByte(), 0x82.toByte(), 0x63.toByte(),
            0xf8.toByte(), 0x55.toByte(), 0x4a.toByte(), 0x93.toByte(),
            0x88.toByte(), 0x14.toByte(), 0x58.toByte(), 0x7a.toByte(),
            0x02.toByte(), 0x52.toByte(), 0x1f.toByte(), 0xdd.toByte(),
        )
        val xml = """<?xml version="1.0"?>
<rdf:SphericalVideo
xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
<GSpherical:Spherical>true</GSpherical:Spherical>
<GSpherical:Stitched>true</GSpherical:Stitched>
<GSpherical:StitchingSoftware>Gear360Alive</GSpherical:StitchingSoftware>
<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
</rdf:SphericalVideo>""".toByteArray(Charsets.UTF_8)
        val total = 8 + uuid.size + xml.size
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            .putInt(total)
            .put("uuid".toByteArray(Charsets.US_ASCII))
            .put(uuid)
            .put(xml)
            .array()
    }

    /**
     * Injects the Spherical Video V1 `uuid` box inside the MP4's video `moov -> trak` box per RFC,
     * updating `trak` and `moov` sizes and shifting any `stco`/`co64` chunk offsets located after the insertion point.
     */
    internal fun injectSphericalVideoV1Uuid(mp4: File) {
        val atom = buildSphericalUuidAtom()
        RandomAccessFile(mp4, "rw").use { raf ->
            val len = raf.length()
            val moov = findBox(raf, 0L, len) { type, _, _ -> type == "moov" } ?: return
            val moovHeader = moov.headerSize
            val moovEnd = moov.start + moov.size
            // Find the video trak (containing mdia -> minf -> vmhd), or fall back to first trak
            val videoTrak = findBox(raf, moov.start + moovHeader, moovEnd) { type, start, size ->
                type == "trak" && hasNestedBox(raf, start + 8L, start + size, listOf("mdia", "minf", "vmhd"))
            } ?: findBox(raf, moov.start + moovHeader, moovEnd) { type, _, _ -> type == "trak" } ?: return

            val insertPos = videoTrak.start + videoTrak.size
            val delta = atom.size
            // Shift any stco / co64 chunk offsets that point at or after insertPos
            shiftChunkOffsets(raf, moov.start + moovHeader, moovEnd, insertPos, delta.toLong())

            // Read tail after insertPos, write uuid atom, then write tail back
            val tailLen = (len - insertPos).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(insertPos)
            raf.readFully(tail)
            raf.seek(insertPos)
            raf.write(atom)
            raf.write(tail)

            // Update videoTrak size and moov size
            raf.seek(videoTrak.start)
            raf.writeInt((videoTrak.size + delta).toInt())
            raf.seek(moov.start)
            raf.writeInt((moov.size + delta).toInt())
        }
    }

    private data class BoxSpan(val type: String, val start: Long, val size: Long, val headerSize: Int)

    private fun findBox(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        predicate: (type: String, start: Long, size: Long) -> Boolean,
    ): BoxSpan? {
        var pos = start
        val typeBytes = ByteArray(4)
        while (pos + 8 <= end) {
            raf.seek(pos)
            val s32 = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var hdr = 8
            val boxSize = when (s32) {
                1L -> {
                    if (pos + 16 > end) return null
                    hdr = 16
                    raf.readLong()
                }
                0L -> end - pos
                else -> s32
            }
            if (boxSize < hdr || pos + boxSize > end) return null
            if (predicate(type, pos, boxSize)) return BoxSpan(type, pos, boxSize, hdr)
            pos += boxSize
        }
        return null
    }

    private fun hasNestedBox(raf: RandomAccessFile, start: Long, end: Long, path: List<String>): Boolean {
        val target = path.first()
        val found = findBox(raf, start, end) { type, _, _ -> type == target } ?: return false
        return if (path.size == 1) true
        else hasNestedBox(raf, found.start + found.headerSize, found.start + found.size, path.drop(1))
    }

    private fun shiftChunkOffsets(raf: RandomAccessFile, start: Long, end: Long, threshold: Long, delta: Long) {
        var pos = start
        val typeBytes = ByteArray(4)
        while (pos + 8 <= end) {
            raf.seek(pos)
            val s32 = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val hdr = if (s32 == 1L) 16 else 8
            val boxSize = when (s32) {
                1L -> raf.readLong()
                0L -> end - pos
                else -> s32
            }
            if (boxSize < hdr || pos + boxSize > end) return
            when (type) {
                "trak", "mdia", "minf", "stbl" -> shiftChunkOffsets(raf, pos + hdr, pos + boxSize, threshold, delta)
                "stco" -> {
                    if (boxSize >= hdr + 8) {
                        raf.seek(pos + hdr + 4)
                        val count = raf.readInt()
                        for (i in 0 until count) {
                            val entryPos = pos + hdr + 8L + i * 4L
                            if (entryPos + 4 > pos + boxSize) break
                            raf.seek(entryPos)
                            val off = raf.readInt().toLong() and 0xFFFFFFFFL
                            if (off >= threshold) {
                                raf.seek(entryPos)
                                raf.writeInt((off + delta).toInt())
                            }
                        }
                    }
                }
                "co64" -> {
                    if (boxSize >= hdr + 8) {
                        raf.seek(pos + hdr + 4)
                        val count = raf.readInt()
                        for (i in 0 until count) {
                            val entryPos = pos + hdr + 8L + i * 8L
                            if (entryPos + 8 > pos + boxSize) break
                            raf.seek(entryPos)
                            val off = raf.readLong()
                            if (off >= threshold) {
                                raf.seek(entryPos)
                                raf.writeLong(off + delta)
                            }
                        }
                    }
                }
            }
            pos += boxSize
        }
    }

    private class EglEncoderSurface(private val surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "EGL init failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No recordable EGLConfig" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            surface.release()
        }
    }
}
