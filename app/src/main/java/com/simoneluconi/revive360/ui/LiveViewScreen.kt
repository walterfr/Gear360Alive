package com.simoneluconi.revive360.ui

import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.CameraController
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.media.LiveStreamer
import com.simoneluconi.revive360.media.LiveViewDecoder
import com.simoneluconi.revive360.protocol.LiveStream
import com.simoneluconi.revive360.stitch.CameraMetadata
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.GyroSample
import com.simoneluconi.revive360.stitch.Mat3
import com.simoneluconi.revive360.stitch.Projection
import com.simoneluconi.revive360.stitch.SphereVideoView
import com.simoneluconi.revive360.stitch.ViewState
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private enum class LiveProjectionMode(val label: String) {
    SPHERE_360("360°"),
    FRONT("Front"),
    REAR("Rear"),
    PLANET("Little Planet"),
    RAW("Dual Fisheye"),
}

/** The camera's live picture (protocol §5.14), rendered interactively in 360° or Dual Fisheye. */
@Composable
fun LiveViewScreen() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var frames by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(LiveProjectionMode.SPHERE_360) }
    var flowState by rememberSaveable { mutableStateOf(true) }
    var nadirPatch by rememberSaveable { mutableStateOf(false) }
    var obsEnabled by rememberSaveable { mutableStateOf(false) }
    var obsUrl by remember { mutableStateOf("") }
    var sphereView by remember { mutableStateOf<SphereVideoView?>(null) }
    val streamer = remember { LiveStreamer() }
    val session = remember { LiveSession(nav.app.controller, streamer) }
    val linkState by nav.app.controller.state.collectAsStateWithLifecycle()
    val isR210 = (linkState as? LinkState.Connected)?.info?.model?.contains("R210", ignoreCase = true) == true
    val liveModel = remember(isR210) {
        if (isR210) DualFisheye(focal = CameraMetadata.R210_FOCAL) else DualFisheye()
    }
    val handled = remember { intArrayOf(0) }
    var full by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(Unit) {
        onDispose {
            session.stop()
            streamer.stop()
        }
    }
    BackHandler(enabled = full) { full = false }
    LaunchedEffect(error) { if (error != null) full = false }
    DisposableEffect(full) {
        systemBars(view, show = !full)
        onDispose { systemBars(view, show = true) }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            delay(1_000)
            fps = (frames - last).toInt()
            last = frames
        }
    }

    fun applyMode(next: LiveProjectionMode) {
        mode = next
        val v = sphereView ?: return
        v.rawMode = next == LiveProjectionMode.RAW
        val preset = when (next) {
            LiveProjectionMode.SPHERE_360, LiveProjectionMode.FRONT ->
                ViewState(yaw = 0f, pitch = 0f, fov = 95f, projection = Projection.RECTILINEAR, level = v.view.level)
            LiveProjectionMode.REAR ->
                ViewState(yaw = Math.PI.toFloat(), pitch = 0f, fov = 95f, projection = Projection.RECTILINEAR, level = v.view.level)
            LiveProjectionMode.PLANET ->
                ViewState(yaw = 0f, pitch = (Math.PI / 2).toFloat(), fov = 260f, projection = Projection.STEREOGRAPHIC, level = v.view.level)
            LiveProjectionMode.RAW -> v.view
        }
        v.home = preset
        v.setView(preset)
    }

    fun startLiveOn(surface: Surface) {
        session.start(
            target = surface,
            onSize = { w, h -> size = w to h },
            onGyro = { gyro ->
                val sv = sphereView ?: return@start
                val level = if (flowState) gyro.horizonLockMatrix() else Mat3.identity()
                sv.setView(sv.view.copy(level = level))
            },
            onFrame = {
                frames = it
                sphereView?.captureStitchedFrames = obsEnabled && streamer.hasMjpegClients
            },
            onError = { error = it },
        )
    }

    ScreenScaffold(title = stringResource(R.string.liveview_title), scroll = false, bare = full) {
        val ratio = if (mode == LiveProjectionMode.RAW) (size?.let { (w, h) -> w.toFloat() / h } ?: 2f) else 16f / 10f
        Box(
            (if (full) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxWidth().weight(1f))
                .clip(RoundedCornerShape(if (full) 0.dp else 20.dp))
                .background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    SphereVideoView(
                        context = context,
                        model = liveModel,
                        onSurfaceReady = { surface -> startLiveOn(surface) },
                        onSurfaceDestroyed = { session.stop() },
                    ).also {
                        it.onStitchedFrame = { jpeg ->
                            if (streamer.isRunning) streamer.broadcastStitchedJpeg(jpeg)
                        }
                        sphereView = it
                    }
                },
                update = { sv ->
                    sv.setModel(liveModel)
                    sv.setNadirPatch(nadirPatch)
                    sv.captureStitchedFrames = obsEnabled && streamer.hasMjpegClients
                    if (!flowState && !sv.view.level.contentEquals(Mat3.identity())) {
                        sv.setView(sv.view.copy(level = Mat3.identity()))
                    }
                    if (attempt > handled[0] && !session.running) {
                        val s = sv.currentSurface
                        if (s != null) {
                            handled[0] = attempt
                            error = null
                            startLiveOn(s)
                        }
                    }
                },
                modifier = if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(ratio),
            )
            if (frames == 0L && error == null) Spinner()
            RoundIconButton(
                if (full) AppIcons.Close else AppIcons.Expand,
                label = stringResource(R.string.pano_full),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                background = Color.Black.copy(alpha = 0.45f),
                tint = Color.White,
                size = 40.dp,
            ) { full = !full }
        }
        if (full) return@ScreenScaffold

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveProjectionMode.entries.forEach { m ->
                Chip(m.label, selected = mode == m) { applyMode(m) }
            }
            Chip("FlowState", selected = flowState) { flowState = !flowState }
            if (Features.EXPERIMENTAL) {
            Chip("Nadir Patch", selected = nadirPatch) { nadirPatch = !nadirPatch }
            Chip("OBS Stream", selected = obsEnabled) {
                val next = !obsEnabled
                if (next) {
                    runCatching {
                        streamer.start()
                        obsUrl = LiveStreamer.localStreamUrl(streamer.boundPort)
                        obsEnabled = true
                        EventLog.log("OBS Live Stream started at $obsUrl")
                        nav.message("OBS Stream active: $obsUrl", MessageKind.GOOD)
                    }.onFailure {
                        nav.message("OBS Stream error: ${it.message}", MessageKind.BAD)
                    }
                } else {
                    streamer.stop()
                    obsEnabled = false
                    obsUrl = ""
                }
            }
            }
        }
        if (obsEnabled && obsUrl.isNotEmpty()) {
            Label(
                "OBS / VLC URL: $obsUrl (${streamer.clientCount} connected)",
                Modifier.fillMaxWidth(),
                size = 12.sp,
                color = colors.accent,
                align = TextAlign.Center,
                mono = true,
            )
        }

        val problem = error
        if (problem != null) {
            Card(color = colors.soft(colors.bad)) {
                Label(resources.getString(R.string.liveview_failed, problem), size = 14.sp)
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.action_retry), icon = AppIcons.Refresh) { attempt++ }
            }
        } else {
            Label(
                when {
                    frames == 0L -> stringResource(R.string.liveview_connecting)
                    else -> size?.let { (w, h) -> stringResource(R.string.liveview_info, w, h, fps) } ?: ""
                },
                Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2, align = TextAlign.Center,
            )
        }
        Label(stringResource(R.string.pano_hint), Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2,
            align = TextAlign.Center)
    }
}

/** Shows or hides the status and navigation bars (swipe brings them back for a moment) */
@Suppress("DEPRECATION")
internal fun systemBars(view: View, show: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = view.windowInsetsController ?: return
        if (show) {
            controller.show(WindowInsets.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        }
    } else {
        view.systemUiVisibility = if (show) View.SYSTEM_UI_FLAG_VISIBLE else
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

private class LiveSession(
    private val controller: CameraController,
    private val streamer: LiveStreamer,
) {
    private val stream = AtomicReference<LiveStream?>(null)
    private val pending = AtomicReference<HttpURLConnection?>(null)
    private var worker: Thread? = null
    private var surface: Surface? = null
    @Volatile private var stopping = false

    val running: Boolean get() = worker?.isAlive == true

    fun start(
        target: Surface,
        onSize: (Int, Int) -> Unit,
        onGyro: (GyroSample) -> Unit,
        onFrame: (Long) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) return
        stopping = false
        surface = target
        worker = thread(name = "liveview") {
            var reopenedAt = Long.MIN_VALUE / 2
            while (!stopping) {
                var played = false
                val failure = try {
                    val opened = controller.openLiveView { connection ->
                        pending.set(connection)
                        if (stopping) throw IOException("stopped")
                    }
                    pending.set(null)
                    stream.set(opened)
                    if (stopping) break
                    EventLog.log("Live view: ${opened.contentType}")
                    LiveViewDecoder(
                        input = opened.input,
                        surface = target,
                        onSize = onSize,
                        onGyro = onGyro,
                        onAccessUnit = { au -> if (streamer.isRunning) streamer.broadcastAccessUnit(au) },
                    ) { played = true; onFrame(it) }.run()
                    "the camera ended the stream"
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                } finally {
                    stream.getAndSet(null)?.close()
                }
                if (stopping) break
                val now = SystemClock.elapsedRealtime()
                if (!played || now - reopenedAt < REOPEN_GAP_MS) {
                    EventLog.log("Live view failed: $failure")
                    onError(failure)
                    break
                }
                EventLog.log("Live view: $failure, opening it again")
                reopenedAt = now
            }
        }
    }

    fun stop() {
        stopping = true
        pending.getAndSet(null)?.disconnect()
        val decoding = stream.getAndSet(null)?.also { it.close() } != null
        if (decoding) worker?.join(STOP_WAIT_MS)
        worker = null
        surface = null
    }

    private companion object {
        const val STOP_WAIT_MS = 2_000L
        const val REOPEN_GAP_MS = 30_000L
    }
}
