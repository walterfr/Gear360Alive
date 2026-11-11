package com.simoneluconi.revive360.ui

import android.net.wifi.WifiManager
import android.view.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.CameraHealthState
import com.simoneluconi.revive360.link.SessionLayerState
import com.simoneluconi.revive360.link.StockCameraController
import com.simoneluconi.revive360.link.StockMode
import com.simoneluconi.revive360.link.WifiLayerState
import com.simoneluconi.revive360.media.LiveViewDecoder
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.LiveStream
import com.simoneluconi.revive360.protocol.OscStatus
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.protocol.StockClient
import com.simoneluconi.revive360.protocol.StockMediaItem
import com.simoneluconi.revive360.protocol.StockStatus
import com.simoneluconi.revive360.stitch.CameraMetadata
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.SphereVideoView
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A camera on its original firmware: joins the network the camera's menu started ("Connect to iOS"
 * for status, live preview and wireless media download, or "Connect to Street View" for remote 360° photo capture via OSC v1).
 */
@Composable
fun StockCameraScreen() {
    val nav = LocalActions.current
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val settings = nav.app.settings
    val controller = nav.app.stockController

    val state by controller.state.collectAsStateWithLifecycle()

    var iosCode by rememberSaveable { mutableStateOf(settings.stockPassphrase.orEmpty()) }
    var oscCode by rememberSaveable { mutableStateOf(settings.oscPassphrase.orEmpty()) }

    var shooting by remember { mutableStateOf(false) }
    var shotMessage by remember { mutableStateOf<String?>(null) }
    var shotError by remember { mutableStateOf<String?>(null) }

    var mediaList by remember { mutableStateOf<List<StockMediaItem>?>(null) }
    var loadingMedia by remember { mutableStateOf(false) }
    var downloadingName by remember { mutableStateOf<String?>(null) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val activeMode = state.mode
    val isOsc = activeMode == StockMode.OSC
    val wifi = state.wifi
    val session = state.session

    val isConnected = wifi is WifiLayerState.Connected
    val isLive = session is SessionLayerState.LivePreview

    ScreenScaffold(title = stringResource(R.string.stock_title)) {
        if (!isLive && !shooting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.stock_mode_ios), selected = !isOsc) {
                    if (isOsc) {
                        controller.setMode(StockMode.IOS)
                        shotMessage = null
                        shotError = null
                        mediaList = null
                    }
                }
                Chip(stringResource(R.string.stock_mode_osc), selected = isOsc) {
                    if (!isOsc) {
                        controller.setMode(StockMode.OSC)
                        shotMessage = null
                        shotError = null
                        mediaList = null
                    }
                }
            }
        }

        val activeCode = if (isOsc) oscCode else iosCode

        if (!isConnected) {
            Card {
                Label(
                    stringResource(if (isOsc) R.string.stock_osc_steps else R.string.stock_steps),
                    color = colors.text2,
                )
                Spacer(Modifier.height(14.dp))
                TextInput(
                    value = activeCode,
                    onChange = { next ->
                        val cleaned = next.filter { c -> c.code in 0x20..0x7E }.take(Protocol.PASSPHRASE_MAX)
                        if (isOsc) {
                            oscCode = cleaned
                            settings.oscPassphrase = cleaned
                        } else {
                            iosCode = cleaned
                            settings.stockPassphrase = cleaned
                        }
                    },
                    placeholder = stringResource(R.string.stock_code),
                    mono = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(14.dp))
                val isConnecting = wifi is WifiLayerState.Connecting
                PillButton(
                    stringResource(if (isConnecting) R.string.stock_joining else R.string.stock_connect),
                    Modifier.fillMaxWidth(),
                    icon = AppIcons.Wifi,
                    style = ButtonStyle.PRIMARY,
                    enabled = Protocol.isValidPassphrase(activeCode) && !isConnecting,
                ) {
                    shotMessage = null
                    shotError = null
                    mediaList = null
                    controller.connect()
                }

                val problem = when (wifi) {
                    is WifiLayerState.Lost -> stringResource(R.string.stock_lost)
                    is WifiLayerState.Unavailable -> stringResource(R.string.stock_not_found)
                    else -> null
                }
                if (problem != null) {
                    Spacer(Modifier.height(12.dp))
                    Label(problem, size = 14.sp, color = colors.bad)
                }
            }
        } else if (isOsc) {
            Card {
                when (val health = state.health) {
                    is CameraHealthState.HealthyOsc -> {
                        val current = health.status
                        StatusLine(stringResource(R.string.stock_battery), current.batteryPercent?.let { "$it%" } ?: "?")
                        StatusLine(stringResource(R.string.info_model), current.model ?: "?")
                        StatusLine(stringResource(R.string.stock_firmware), current.firmware ?: "?")
                    }
                    is CameraHealthState.Unresponsive -> {
                        Label(stringResource(R.string.stock_no_answer), size = 14.sp, color = colors.bad)
                    }
                    else -> {
                        Label(stringResource(R.string.stock_reading), color = colors.text2)
                    }
                }
            }

            val camera = controller.oscClient
            if (camera != null) {
                Card {
                    PillButton(
                        stringResource(if (shooting) R.string.stock_osc_shooting else R.string.stock_osc_shoot),
                        Modifier.fillMaxWidth(),
                        icon = AppIcons.Camera360,
                        style = ButtonStyle.PRIMARY,
                        enabled = !shooting,
                    ) {
                        shooting = true
                        controller.setSessionState(SessionLayerState.CapturingPhoto)
                        shotMessage = null
                        shotError = null
                        scope.launch {
                            val tmp = File(context.cacheDir, "osc_shot_${System.currentTimeMillis()}.jpg")
                            try {
                                val name = withContext(Dispatchers.IO) {
                                    val fileName = camera.shoot(tmp)
                                    MediaSaver.publish(
                                        context,
                                        fileName,
                                        FileKind.PHOTO,
                                        "image/jpeg",
                                        System.currentTimeMillis() / 1000L,
                                        tmp,
                                    )
                                    fileName
                                }
                                EventLog.log("OSC photo saved: $name")
                                shotMessage = context.getString(R.string.stock_osc_saved, MediaSaver.FOLDER, name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                EventLog.log("OSC photo failed: ${e.message}")
                                shotError = e.message ?: e.javaClass.simpleName
                            } finally {
                                tmp.delete()
                                shooting = false
                                controller.setSessionState(SessionLayerState.Idle)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val err = shotError
                    val saved = shotMessage
                    when {
                        err != null -> Label(err, size = 14.sp, color = colors.bad)
                        saved != null -> Label(saved, size = 14.sp, color = colors.good)
                        else -> Label(stringResource(R.string.stock_osc_note), size = 14.sp, color = colors.text2)
                    }
                }
            }
        } else {
            Card {
                when (val health = state.health) {
                    is CameraHealthState.HealthyIos -> {
                        val current = health.status
                        StatusLine(stringResource(R.string.stock_battery), batteryText(current))
                        StatusLine(stringResource(R.string.stock_card), cardText(current))
                        StatusLine(stringResource(R.string.stock_mode), current.mode ?: "?")
                        StatusLine(stringResource(R.string.stock_auto_off), current.autoPowerOff ?: "?")
                        StatusLine(stringResource(R.string.stock_firmware), current.firmware ?: "?")
                    }
                    is CameraHealthState.Unresponsive -> {
                        Label(stringResource(R.string.stock_no_answer), size = 14.sp, color = colors.bad)
                    }
                    else -> {
                        Label(stringResource(R.string.stock_reading), color = colors.text2)
                    }
                }
            }

            val camera = controller.stockClient
            if (camera != null) {
                // Card for Wireless Media Gallery / Download
                Card {
                    val currentItems = mediaList
                    if (currentItems == null) {
                        PillButton(
                            stringResource(if (loadingMedia) R.string.stock_gallery_loading else R.string.stock_gallery_btn),
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.Gallery,
                            style = ButtonStyle.SECONDARY,
                            enabled = !loadingMedia && !isLive,
                        ) {
                            loadingMedia = true
                            downloadMessage = null
                            downloadError = null
                            scope.launch {
                                try {
                                    val items = withContext(Dispatchers.IO) { camera.listMedia() }
                                    mediaList = items
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    EventLog.log("Stock list media error: ${e.message}")
                                    downloadError = e.message ?: e.javaClass.simpleName
                                } finally {
                                    loadingMedia = false
                                }
                            }
                        }
                    } else {
                        Label(
                            stringResource(R.string.stock_gallery_title, currentItems.size),
                            size = 14.sp,
                            weight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            currentItems.take(15).forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = downloadingName == null) {
                                            downloadingName = item.title
                                            downloadMessage = null
                                            downloadError = null
                                            scope.launch {
                                                val tmp = File(context.cacheDir, "stock_dl_${item.title}")
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        camera.download(item.mediaUrl, tmp)
                                                        MediaSaver.publish(
                                                            context,
                                                            item.title,
                                                            item.kind,
                                                            if (item.kind == FileKind.VIDEO) "video/mp4" else "image/jpeg",
                                                            System.currentTimeMillis() / 1000L,
                                                            tmp
                                                        )
                                                    }
                                                    downloadMessage = context.getString(
                                                        R.string.stock_gallery_downloaded,
                                                        item.title,
                                                        MediaSaver.FOLDER
                                                    )
                                                } catch (e: Exception) {
                                                    if (e is CancellationException) throw e
                                                    downloadError = e.message ?: e.javaClass.simpleName
                                                } finally {
                                                    tmp.delete()
                                                    downloadingName = null
                                                }
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isCurDownloading = downloadingName == item.title
                                    Label(
                                        if (isCurDownloading) stringResource(R.string.stock_gallery_downloading, item.title) else item.title,
                                        modifier = Modifier.weight(1f),
                                        size = 14.sp,
                                        color = if (isCurDownloading) colors.accent else colors.text
                                    )
                                    Label(
                                        if (item.sizeBytes > 0) "${item.sizeBytes / (1024 * 1024)} MB" else "",
                                        size = 12.sp,
                                        color = colors.text2
                                    )
                                }
                            }
                        }
                    }
                    val msg = downloadMessage
                    val err = downloadError
                    if (msg != null) {
                        Spacer(Modifier.height(8.dp))
                        Label(msg, size = 13.sp, color = colors.good)
                    }
                    if (err != null) {
                        Spacer(Modifier.height(8.dp))
                        Label(err, size = 13.sp, color = colors.bad)
                    }
                }
            }

            if (Features.EXPERIMENTAL && camera != null) {
                if (isLive) {
                    StockPreview(camera) {
                        controller.startCooldown(seconds = 60)
                    }
                } else if (session is SessionLayerState.CoolingDown) {
                    Card {
                        Label(
                            stringResource(R.string.stock_cooldown_countdown, session.secondsRemaining),
                            size = 14.sp,
                            color = colors.text2
                        )
                    }
                } else {
                    Card {
                        PillButton(
                            stringResource(R.string.stock_preview),
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.Live,
                            style = ButtonStyle.PRIMARY,
                        ) {
                            controller.setSessionState(SessionLayerState.LivePreview)
                        }
                        Spacer(Modifier.height(10.dp))
                        Label(
                            stringResource(R.string.stock_preview_note),
                            size = 14.sp,
                            color = colors.text2,
                        )
                    }
                }
            }
        }
    }
}

/** The camera's live picture over its original firmware: one session, never reopened by itself. */
@Composable
private fun StockPreview(camera: StockClient, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var frames by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var raw by rememberSaveable { mutableStateOf(false) }
    val session = remember { StockLiveSession(camera) }
    val view = remember { AtomicReference<SphereVideoView?>(null) }
    val model = remember { DualFisheye(focal = CameraMetadata.R210_FOCAL, lensPoly = CameraMetadata.R210_POLY) }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "gear360alive:preview")
        runCatching { lock?.acquire() }.onFailure { EventLog.log("Wi-Fi low latency lock: ${it.message}") }
        onDispose {
            session.stop()
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            delay(1_000)
            fps = (frames - last).toInt()
            last = frames
        }
    }

    Card {
        AndroidView(
            factory = { ctx ->
                SphereVideoView(
                    context = ctx,
                    model = model,
                    onSurfaceReady = { surface ->
                        session.start(
                            surface,
                            onSize = { w, h ->
                                view.get()?.setSourceSize(w, h)
                                size = w to h
                            },
                            onFrame = { frames = it },
                        ) { error = it }
                    },
                    onSurfaceDestroyed = {
                        session.stop()
                        onClose()
                    },
                ).also {
                    it.keepScreenOn = true
                    view.set(it)
                }
            },
            update = { it.rawMode = raw },
            modifier = Modifier.fillMaxWidth().aspectRatio(if (raw) 2f else 16f / 10f).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("360°", selected = !raw) { raw = false }
            Chip("Dual Fisheye", selected = raw) { raw = true }
        }
        Spacer(Modifier.height(10.dp))
        val problem = error
        Label(
            when {
                problem != null -> stringResource(R.string.liveview_failed, problem)
                frames == 0L -> stringResource(R.string.stock_preview_opening)
                else -> size?.let { (w, h) -> stringResource(R.string.liveview_info, w, h, fps) }.orEmpty()
            },
            size = 14.sp,
            color = if (problem != null) colors.bad else colors.text2,
        )
        Spacer(Modifier.height(10.dp))
        PillButton(stringResource(R.string.stock_preview_stop), Modifier.fillMaxWidth(), icon = AppIcons.Close) { onClose() }
    }
}

private class StockLiveSession(private val camera: StockClient) {
    private val stream = AtomicReference<LiveStream?>(null)
    private var worker: Thread? = null
    @Volatile private var stopping = false

    fun start(surface: Surface, onSize: (Int, Int) -> Unit, onFrame: (Long) -> Unit, onError: (String) -> Unit) {
        if (worker != null) return
        worker = thread(name = "stock-live") {
            try {
                val opened = camera.openLive()
                stream.set(opened)
                if (stopping) return@thread
                EventLog.log("Stock live view: ${opened.contentType}")
                LiveViewDecoder(input = opened.input, surface = surface, onSize = onSize, onFrame = onFrame).run()
                if (!stopping) onError("the camera ended the stream")
            } catch (e: Exception) {
                if (!stopping) {
                    EventLog.log("Stock live view failed: ${e.message}")
                    onError(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                stream.getAndSet(null)?.close()
            }
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        stream.getAndSet(null)?.let { thread(isDaemon = true) { it.close() } }
        worker?.join(STOP_WAIT_MS)
    }

    private companion object {
        const val STOP_WAIT_MS = 2_000L
    }
}

@Composable
private fun StatusLine(title: String, value: String) {
    val colors = LocalAppColors.current
    Label(title, size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
    Label(value)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun batteryText(status: StockStatus): String {
    val percent = status.batteryPercent?.let { "$it%" } ?: "?"
    return if (status.charging) stringResource(R.string.stock_charging, percent) else percent
}

@Composable
private fun cardText(status: StockStatus): String {
    val free = status.freeMb ?: return "?"
    val total = status.totalMb ?: return "?"
    return stringResource(R.string.stock_card_free, free / 1024f, total / 1024f)
}
