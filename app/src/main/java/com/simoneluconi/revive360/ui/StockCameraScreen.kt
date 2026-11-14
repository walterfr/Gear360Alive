package com.simoneluconi.revive360.ui

import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.view.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.simoneluconi.revive360.media.PhoneItem
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

    // The gallery's session belongs to the controller; leaving the screen ends it (after a download in progress)
    DisposableEffect(Unit) { onDispose { controller.closeMedia() } }

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
                    }
                }
                Chip(stringResource(R.string.stock_mode_osc), selected = isOsc) {
                    if (!isOsc) {
                        controller.setMode(StockMode.OSC)
                        shotMessage = null
                        shotError = null
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
                    val currentItems = state.media
                    val downloadingName = state.downloading
                    if (currentItems == null) {
                        PillButton(
                            stringResource(if (state.mediaLoading) R.string.stock_gallery_loading else R.string.stock_gallery_btn),
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.Gallery,
                            style = ButtonStyle.SECONDARY,
                            // Only from idle: the camera holds one session at a time
                            enabled = session is SessionLayerState.Idle && !state.mediaLoading,
                        ) { controller.openMedia() }
                        if (downloadingName != null) {
                            Spacer(Modifier.height(8.dp))
                            Label(
                                stringResource(R.string.stock_gallery_downloading, downloadingName),
                                size = 13.sp,
                                color = colors.accent
                            )
                        }
                    } else {
                        Label(
                            stringResource(R.string.stock_gallery_title, currentItems.size),
                            size = 14.sp,
                            weight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // ponytail: one Browse page (100 files); page with StartingIndex when a card holds more
                            currentItems.forEach { item ->
                                // Already on the phone: marked, and not saved a second time
                                val isDownloaded = PhoneItem.savedKey(item.title) in state.saved
                                val isCurDownloading = downloadingName == item.title
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = downloadingName == null && !isDownloaded) { controller.download(item) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StockMediaThumbnail(controller, item, saved = isDownloaded)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (isDownloaded) Icon(AppIcons.Check, color = colors.good, size = 16.dp)
                                            Label(
                                                if (isCurDownloading) stringResource(R.string.stock_gallery_downloading, item.title) else item.title,
                                                size = 14.sp,
                                                color = when {
                                                    isCurDownloading -> colors.accent
                                                    isDownloaded -> colors.good
                                                    else -> colors.text
                                                },
                                                weight = if (isDownloaded) FontWeight.Bold else FontWeight.SemiBold,
                                            )
                                        }
                                        Label(
                                            listOfNotNull(
                                                item.sizeBytes.takeIf { it > 0 }?.let { "${it / (1024 * 1024)} MB" },
                                                if (isDownloaded) stringResource(R.string.stock_gallery_already_saved) else null,
                                            ).joinToString(" · "),
                                            size = 12.sp,
                                            color = if (isDownloaded) colors.good else colors.text2
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        PillButton(
                            stringResource(R.string.stock_gallery_close),
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.Close,
                        ) { controller.closeMedia() }
                    }
                    val msg = state.lastDownloaded?.let { stringResource(R.string.stock_gallery_downloaded, it, MediaSaver.FOLDER) }
                    val err = state.mediaError
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

            val cameraSession = controller.cameraSession
            if (Features.EXPERIMENTAL && camera != null && cameraSession != null) {
                if (isLive) {
                    // The link's session stays: closing the preview only stops the stream
                    StockPreview(camera, cameraSession) {
                        controller.setSessionState(SessionLayerState.Idle)
                    }
                } else if (session is SessionLayerState.Idle) {
                    // Only from idle: never over an open gallery
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

        if (isConnected && !isLive && !shooting) {
            PillButton(
                stringResource(R.string.stock_disconnect),
                Modifier.fillMaxWidth(),
                icon = AppIcons.Close,
            ) { controller.disconnect() }
            if (!isOsc) {
                Label(stringResource(R.string.stock_disconnect_note), size = 13.sp, color = colors.text2)
            }
        }
    }
}

/** The camera's live picture over its original firmware: one session, never reopened by itself. */
@Composable
private fun StockPreview(camera: StockClient, cameraSession: StockClient.Session, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var frames by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var raw by rememberSaveable { mutableStateOf(false) }
    val session = remember { StockLiveSession(camera, cameraSession) }
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

private class StockLiveSession(private val camera: StockClient, private val cameraSession: StockClient.Session) {
    private val stream = AtomicReference<LiveStream?>(null)
    private var worker: Thread? = null
    @Volatile private var stopping = false

    fun start(surface: Surface, onSize: (Int, Int) -> Unit, onFrame: (Long) -> Unit, onError: (String) -> Unit) {
        if (worker != null) return
        worker = thread(name = "stock-live") {
            try {
                val opened = camera.openLive(cameraSession)
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
private fun StockMediaThumbnail(controller: StockCameraController, item: StockMediaItem, saved: Boolean) {
    val colors = LocalAppColors.current
    var bitmap by remember(item.thumbUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.thumbUrl) {
        // The controller fetches them one at a time and keeps them while the gallery is open
        val bytes = controller.thumbnail(item)
        if (bytes != null) {
            bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface2),
        contentAlignment = Alignment.Center
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                if (item.kind == FileKind.VIDEO) AppIcons.Video else AppIcons.Photo,
                color = colors.text2,
                size = 20.dp
            )
        }
        if (saved) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.good),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Check, color = Color.White, size = 12.dp)
            }
        }
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
