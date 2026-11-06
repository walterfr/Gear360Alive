package com.simoneluconi.revive360.stitch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import com.simoneluconi.revive360.ui.PanoramaView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * Renders a dual-fisheye (or equirectangular) `GL_TEXTURE_EXTERNAL_OES` video/live stream onto the
 * 360° sphere or as a flat dual-fisheye preview at 60fps, with optional FlowState horizon lock.
 */
class OesSphereRenderer(var model: DualFisheye = DualFisheye(), var nadirPatch: Boolean = false) {
    private var sphereProg = 0
    private var rawProg = 0
    private var equirectOutProg = 0

    var oesTexId = 0
        private set

    fun create(): Int {
        sphereProg = Gl.program(Gl.FULLSCREEN_VERTEX, SPHERE_FRAGMENT)
        rawProg = Gl.program(Gl.FULLSCREEN_VERTEX, RAW_FRAGMENT)
        equirectOutProg = Gl.program(Gl.FULLSCREEN_VERTEX, EQUIRECT_OUT_FRAGMENT)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTexId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return oesTexId
    }

    fun release() {
        if (oesTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexId), 0)
        if (sphereProg != 0) GLES30.glDeleteProgram(sphereProg)
        if (rawProg != 0) GLES30.glDeleteProgram(rawProg)
        if (equirectOutProg != 0) GLES30.glDeleteProgram(equirectOutProg)
        oesTexId = 0
        sphereProg = 0
        rawProg = 0
        equirectOutProg = 0
    }

    /** Draws the current OES frame as a 360° / Little Planet / Rectilinear view. */
    fun drawSphere(width: Int, height: Int, view: ViewState, texMatrix: FloatArray, flipY: Boolean = false) {
        if (sphereProg == 0 || oesTexId == 0) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(sphereProg)
        bindOesAndModel(sphereProg, texMatrix)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(sphereProg, "uViewport"), width.toFloat(), height.toFloat())
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(sphereProg, "uView"), 1, false, view.viewMatrix(), 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(sphereProg, "uLevel"), 1, false, view.level, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sphereProg, "uScale"), view.scale())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(sphereProg, "uProjection"), view.projection.ordinal)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sphereProg, "uFlipY"), if (flipY) -1f else 1f)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(sphereProg, "uWindow"), 0f, 0f, width.toFloat() / height.coerceAtLeast(1), 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Draws the current OES frame as a full 2:1 equirectangular frame (for 360° video export). */
    fun drawEquirectangular(width: Int, height: Int, orientation: FloatArray, texMatrix: FloatArray) {
        if (equirectOutProg == 0 || oesTexId == 0) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(equirectOutProg)
        bindOesAndModel(equirectOutProg, texMatrix)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(equirectOutProg, "uOutSize"), width.toFloat(), height.toFloat())
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(equirectOutProg, "uOrient"), 1, false, orientation, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Draws the raw dual-fisheye frame side-by-side without spherical projection. */
    fun drawRaw(width: Int, height: Int, texMatrix: FloatArray) {
        if (rawProg == 0 || oesTexId == 0) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(rawProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rawProg, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(rawProg, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(rawProg, "uViewport"), width.toFloat(), height.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun bindOesAndModel(prog: Int, texMatrix: FloatArray) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFocal"), model.focal)
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(prog, "uCenters"),
            model.frontCenterX, model.frontCenterY, model.backCenterX, model.backCenterY,
        )
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(prog, "uBackRot"), 1, false, model.backMatrix(), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uMaxTheta"), model.maxTheta)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uVignette"), model.vignetteStrength, model.vignettePower)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uNadirPatch"), if (nadirPatch) 1f else 0f)
    }

    companion object {
        private const val OES_HEADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform float uFocal;
uniform vec4 uCenters;
uniform mat3 uBackRot;
uniform float uMaxTheta;
uniform vec2 uVignette;
uniform float uNadirPatch;

vec3 sampleHalf(vec2 uv, float halfIdx) {
    vec2 fullUv = vec2((uv.x + halfIdx) * 0.5, uv.y);
    vec2 tUv = (uTexMatrix * vec4(fullUv.x, 1.0 - fullUv.y, 0.0, 1.0)).xy;
    return texture(uTex, tUv).rgb;
}

float lensPointOes(vec3 v, vec2 center, out vec2 uv, out float vignette) {
    float th = acos(clamp(v.z, -1.0, 1.0));
    vignette = exp(uVignette.x * pow(min(th, uMaxTheta) / 1.57079633, uVignette.y));
    float len = length(v.xy);
    vec2 dir = len > 1e-6 ? v.xy / len : vec2(1.0, 0.0);
    uv = center + uFocal * th * vec2(dir.x, -dir.y);
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)) / uFocal;
    return min(uMaxTheta - th, edge);
}

vec3 rawSphereColorOes(vec3 d) {
    vec2 uvF;
    vec2 uvB;
    float vF;
    float vB;
    float mF = lensPointOes(d, uCenters.xy, uvF, vF);
    float mB = lensPointOes(uBackRot * d, uCenters.zw, uvB, vB);
    if (mB <= 0.0) return mF > 0.0 ? clamp(sampleHalf(uvF, 0.0) * vF, 0.0, 1.0) : vec3(0.0);
    if (mF <= 0.0) return clamp(sampleHalf(uvB, 1.0) * vB, 0.0, 1.0);
    vec3 f = sampleHalf(uvF, 0.0) * vF;
    vec3 b = sampleHalf(uvB, 1.0) * vB;
    // Narrow the seam in the lower hemisphere (d.y < -0.35) for Invisible Selfie Stick / Nadir clean cut
    float seamHalf = mix(0.08, 0.018, smoothstep(-0.35, -0.80, d.y));
    float w = smoothstep(-seamHalf, seamHalf, mF - mB);
    return clamp(mix(b, f, w), 0.0, 1.0);
}

vec3 sphereColorOes(vec3 d) {
    vec3 baseCol = rawSphereColorOes(d);
    if (uNadirPatch > 0.5 && d.y < -0.88) {
        // Radial polar inpainting from the clean ring at y = -0.88 to cover tripod base / nadir
        float ringY = -0.88;
        float ringR = sqrt(1.0 - ringY * ringY);
        float lenXZ = length(d.xz);
        vec2 h = lenXZ > 1e-5 ? d.xz / lenXZ : vec2(0.0, 1.0);
        vec3 ringD1 = vec3(h.x * ringR, ringY, h.y * ringR);
        vec3 ringD2 = vec3(-h.x * ringR, ringY, -h.y * ringR);
        vec3 patchCol = mix(rawSphereColorOes(ringD1), rawSphereColorOes(ringD2), smoothstep(-0.92, -0.995, d.y) * 0.5);
        return mix(baseCol, patchCol, smoothstep(-0.88, -0.94, d.y));
    }
    return baseCol;
}
"""

        private const val SPHERE_FRAGMENT = OES_HEADER + """
uniform vec2 uViewport;
uniform mat3 uView;
uniform mat3 uLevel;
uniform float uScale;
uniform int uProjection;
uniform float uFlipY;
uniform vec4 uWindow;
out vec4 color;

void main() {
    vec2 l = gl_FragCoord.xy / uViewport * 2.0 - 1.0;
    vec2 p = (uWindow.xy + vec2(l.x, l.y * uFlipY) * uWindow.zw) * uScale;
    vec3 r;
    if (uProjection == 1) {
        float q = dot(p, p);
        r = vec3(2.0 * p, 1.0 - q) / (1.0 + q);
    } else {
        r = normalize(vec3(p, 1.0));
    }
    vec3 d = uLevel * (uView * r);
    color = vec4(sphereColorOes(d), 1.0);
}
"""

        private const val EQUIRECT_OUT_FRAGMENT = OES_HEADER + """
uniform vec2 uOutSize;
uniform mat3 uOrient;
out vec4 color;

void main() {
    float lon = gl_FragCoord.x / uOutSize.x * 6.28318531 - 3.14159265;
    float lat = gl_FragCoord.y / uOutSize.y * 3.14159265 - 1.57079633;
    vec3 d = uOrient * vec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon));
    color = vec4(sphereColorOes(d), 1.0);
}
"""

        private const val RAW_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec2 uViewport;
out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy / uViewport;
    vec2 tUv = (uTexMatrix * vec4(uv.x, uv.y, 0.0, 1.0)).xy;
    color = vec4(texture(uTex, tUv).rgb, 1.0);
}
"""
    }
}

/**
 * Interactive 360° GLSurfaceView backed by an internal [SurfaceTexture] for live streams or video players.
 */
@SuppressLint("ViewConstructor")
class SphereVideoView(
    context: Context,
    model: DualFisheye = DualFisheye(),
    private val onSurfaceReady: (Surface) -> Unit,
    private val onSurfaceDestroyed: () -> Unit = {},
) : GLSurfaceView(context) {
    private val renderer = OesSphereRenderer(model)
    private var surfaceTexture: SurfaceTexture? = null
    var currentSurface: Surface? = null
        private set
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile private var frameAvailable = false

    @Volatile var view: ViewState = ViewState()
        private set
    @Volatile var home: ViewState = ViewState()
    /** True while the user's finger is touching/dragging the sphere, or after manual drag until resumed. */
    @Volatile var userInteracting: Boolean = false
    @Volatile var manualOverride: Boolean = false
    @Volatile var rawMode: Boolean = false
        set(value) {
            field = value
            requestRender()
        }

    var onViewChanged: ((ViewState) -> Unit)? = null
    /** Callback invoked on a background worker with JPEG-compressed stitched frames for OBS `/live.mjpeg`. */
    @Volatile var onStitchedFrame: ((ByteArray) -> Unit)? = null
    @Volatile var captureStitchedFrames: Boolean = false

    private var captureFbo = 0
    private var captureTex = 0
    private var captureBuf: ByteBuffer? = null
    private var lastCaptureMs = 0L
    private val encodingJpeg = AtomicBoolean(false)
    private val jpegExecutor = Executors.newSingleThreadExecutor()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                val texId = renderer.create()
                val fbos = IntArray(1)
                val texs = IntArray(1)
                GLES30.glGenTextures(1, texs, 0)
                captureTex = texs[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTex)
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, STREAM_W, STREAM_H)
                GLES30.glGenFramebuffers(1, fbos, 0)
                captureFbo = fbos[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, captureTex, 0,
                )
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

                val st = SurfaceTexture(texId).apply {
                    setOnFrameAvailableListener {
                        frameAvailable = true
                        requestRender()
                    }
                }
                surfaceTexture = st
                val s = Surface(st)
                currentSurface = s
                post { onSurfaceReady(s) }
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

            override fun onDrawFrame(gl: GL10?) {
                val st = surfaceTexture ?: return
                if (frameAvailable) {
                    frameAvailable = false
                    runCatching { st.updateTexImage() }
                    st.getTransformMatrix(texMatrix)
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                if (rawMode) {
                    renderer.drawRaw(width, height, texMatrix)
                } else {
                    renderer.drawSphere(width, height, view, texMatrix)
                }

                // If OBS / browser clients are watching `/live.mjpeg`, capture stitched frame at ~18fps
                val cb = onStitchedFrame
                val now = SystemClock.elapsedRealtime()
                if (captureStitchedFrames && cb != null && captureFbo != 0 &&
                    now - lastCaptureMs >= 55L && encodingJpeg.compareAndSet(false, true)
                ) {
                    lastCaptureMs = now
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    renderer.drawSphere(STREAM_W, STREAM_H, view, texMatrix, flipY = true)
                    val buf = captureBuf ?: ByteBuffer.allocateDirect(STREAM_W * STREAM_H * 4)
                        .order(ByteOrder.nativeOrder()).also { captureBuf = it }
                    buf.position(0)
                    GLES30.glReadPixels(0, 0, STREAM_W, STREAM_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    GLES30.glViewport(0, 0, width, height)
                    buf.position(0)
                    jpegExecutor.execute {
                        try {
                            val bmp = Bitmap.createBitmap(STREAM_W, STREAM_H, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.copyPixelsFromBuffer(buf)
                                val out = ByteArrayOutputStream(48 * 1024)
                                bmp.compress(Bitmap.CompressFormat.JPEG, 76, out)
                                cb(out.toByteArray())
                            } finally {
                                bmp.recycle()
                            }
                        } catch (_: Exception) {
                        } finally {
                            encodingJpeg.set(false)
                        }
                    }
                }
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setModel(next: DualFisheye) {
        renderer.model = next
        requestRender()
    }

    fun setNadirPatch(enabled: Boolean) {
        if (renderer.nadirPatch != enabled) {
            renderer.nadirPatch = enabled
            requestRender()
        }
    }

    fun setView(state: ViewState) {
        view = state
        onViewChanged?.invoke(state)
        requestRender()
    }

    override fun onDetachedFromWindow() {
        onSurfaceDestroyed()
        currentSurface?.release()
        currentSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        runCatching { jpegExecutor.shutdownNow() }
        super.onDetachedFromWindow()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (rawMode) return false
            manualOverride = true
            val (min, max) = PanoramaView.fovRange(view.projection)
            setView(view.copy(fov = (view.fov / detector.scaleFactor).coerceIn(min, max)))
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (rawMode) return false
            manualOverride = true
            val v = view
            val perPixel = 2f * tan(Math.toRadians(minOf(v.fov, 120f) / 2.0).toFloat()) / height.coerceAtLeast(1)
            val maxPitch = if (v.projection == Projection.STEREOGRAPHIC) HALF_PI else MAX_PITCH
            setView(v.copy(yaw = v.yaw + dx * perPixel, pitch = (v.pitch + dy * perPixel).coerceIn(-maxPitch, maxPitch)))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!rawMode) {
                manualOverride = false
                setView(home.copy(level = view.level))
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rawMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> userInteracting = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userInteracting = false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event)
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
        val MAX_PITCH = Math.toRadians(89.0).toFloat()
        const val STREAM_W = 800
        const val STREAM_H = 450
    }
}
