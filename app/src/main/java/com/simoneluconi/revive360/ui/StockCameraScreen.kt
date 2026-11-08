package com.simoneluconi.revive360.ui

import android.net.Network
import android.net.wifi.WifiManager
import android.view.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.CameraNetwork
import com.simoneluconi.revive360.media.LiveViewDecoder
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.Connector
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.LiveStream
import com.simoneluconi.revive360.protocol.OscClient
import com.simoneluconi.revive360.protocol.OscStatus
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.protocol.StockClient
import com.simoneluconi.revive360.protocol.StockStatus
import com.simoneluconi.revive360.stitch.CameraMetadata
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.SphereVideoView
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StockLink { IDLE, JOINING, CONNECTED, LOST, NOT_FOUND }

private const val POLL_MS = 20_000L

/**
 * A camera on its original firmware: joins the network the camera's menu started ("Connect to iOS"
 * for status and live preview, or "Connect to Street View" for remote 360° photo capture via OSC v1).
 */
// ponytail: the network is released when the screen closes, not when the app goes to the background
@Composable
fun StockCameraScreen() {
    val nav = LocalActions.current
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val settings = nav.app.settings
    var oscMode by rememberSaveable { mutableStateOf(false) }
    var iosCode by rememberSaveable { mutableStateOf(settings.stockPassphrase.orEmpty()) }
    var oscCode by rememberSaveable { mutableStateOf(settings.oscPassphrase.orEmpty()) }
    var link by remember { mutableStateOf(StockLink.IDLE) }
    var client by remember { mutableStateOf<StockClient?>(null) }
    var oscClient by remember { mutableStateOf<OscClient?>(null) }
    var status by remember { mutableStateOf<StockStatus?>(null) }
    var oscStatus by remember { mutableStateOf<OscStatus?>(null) }
    var unanswered by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var afterLive by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    var shotMessage by remember { mutableStateOf<String?>(null) }
    var shotError by remember { mutableStateOf<String?>(null) }
    val network = remember { CameraNetwork(context) }
    DisposableEffect(Unit) { onDispose { network.release() } }

    fun resetLink() {
        network.release()
        link = StockLink.IDLE
        client = null
        oscClient = null
        status = null
        oscStatus = null
        unanswered = false
        live = false
        afterLive = false
        shotMessage = null
        shotError = null
    }

    // No status queries during a live view: that combination was never tested
    LaunchedEffect(client, live) {
        val camera = client ?: return@LaunchedEffect
        if (live) return@LaunchedEffect
        while (true) {
            try {
                status = withContext(Dispatchers.IO) { camera.status() }
                unanswered = false
            } catch (e: IOException) {
                EventLog.log("Stock camera: no answer (${e.message})")
                unanswered = true
            }
            delay(POLL_MS)
        }
    }

    // No status queries while an OSC photo is being taken
    LaunchedEffect(oscClient, shooting) {
        val camera = oscClient ?: return@LaunchedEffect
        if (shooting) return@LaunchedEffect
        while (true) {
            try {
                oscStatus = withContext(Dispatchers.IO) { camera.status() }
                unanswered = false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                EventLog.log("OSC camera: no answer (${e.message})")
                unanswered = true
            }
            delay(POLL_MS)
        }
    }

    fun connect() {
        val activeCode = if (oscMode) oscCode else iosCode
        if (oscMode) settings.oscPassphrase = activeCode else settings.stockPassphrase = activeCode
        status = null
        oscStatus = null
        client = null
        oscClient = null
        unanswered = false
        shotMessage = null
        shotError = null
        link = StockLink.JOINING
        network.requestStock(activeCode, object : CameraNetwork.Listener {
            override fun onAvailable(network: Network, host: String) {
                val connector = Connector { url -> network.openConnection(url) as HttpURLConnection }
                if (oscMode) {
                    oscClient = OscClient(host, connector = connector)
                } else {
                    client = StockClient(
                        host,
                        connector,
                        openSocket = { network.socketFactory.createSocket() },
                    )
                }
                link = StockLink.CONNECTED
            }

            override fun onLost() {
                client = null
                oscClient = null
                link = StockLink.LOST
            }

            override fun onUnavailable() {
                client = null
                oscClient = null
                link = StockLink.NOT_FOUND
            }
        })
    }

    ScreenScaffold(title = stringResource(R.string.stock_title)) {
        if (!live && !shooting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.stock_mode_ios), selected = !oscMode) {
                    if (oscMode) {
                        oscMode = false
                        resetLink()
                    }
                }
                Chip(stringResource(R.string.stock_mode_osc), selected = oscMode) {
                    if (!oscMode) {
                        oscMode = true
                        resetLink()
                    }
                }
            }
        }
        val activeCode = if (oscMode) oscCode else iosCode
        if (link != StockLink.CONNECTED) {
            Card {
                Label(
                    stringResource(if (oscMode) R.string.stock_osc_steps else R.string.stock_steps),
                    color = colors.text2,
                )
                Spacer(Modifier.height(14.dp))
                TextInput(
                    value = activeCode,
                    onChange = { next ->
                        val cleaned = next.filter { c -> c.code in 0x20..0x7E }.take(Protocol.PASSPHRASE_MAX)
                        if (oscMode) oscCode = cleaned else iosCode = cleaned
                    },
                    placeholder = stringResource(R.string.stock_code),
                    mono = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(14.dp))
                PillButton(
                    stringResource(if (link == StockLink.JOINING) R.string.stock_joining else R.string.stock_connect),
                    Modifier.fillMaxWidth(),
                    icon = AppIcons.Wifi,
                    style = ButtonStyle.PRIMARY,
                    enabled = Protocol.isValidPassphrase(activeCode) && link != StockLink.JOINING,
                ) { connect() }
                val problem = when (link) {
                    StockLink.LOST -> stringResource(R.string.stock_lost)
                    StockLink.NOT_FOUND -> stringResource(R.string.stock_not_found)
                    else -> null
                }
                if (problem != null) {
                    Spacer(Modifier.height(12.dp))
                    Label(problem, size = 14.sp, color = colors.bad)
                }
            }
        } else if (oscMode) {
            Card {
                val current = oscStatus
                if (current == null) {
                    Label(stringResource(if (unanswered) R.string.stock_no_answer else R.string.stock_reading), color = colors.text2)
                } else {
                    StatusLine(stringResource(R.string.stock_battery), current.batteryPercent?.let { "$it%" } ?: "?")
                    StatusLine(stringResource(R.string.info_model), current.model ?: "?")
                    StatusLine(stringResource(R.string.stock_firmware), current.firmware ?: "?")
                    if (unanswered) {
                        Spacer(Modifier.height(8.dp))
                        Label(stringResource(R.string.stock_no_answer), size = 14.sp, color = colors.bad)
                    }
                }
            }
            val camera = oscClient
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
                val current = status
                if (current == null) {
                    Label(stringResource(if (unanswered) R.string.stock_no_answer else R.string.stock_reading), color = colors.text2)
                } else {
                    StatusLine(stringResource(R.string.stock_battery), batteryText(current))
                    StatusLine(stringResource(R.string.stock_card), cardText(current))
                    StatusLine(stringResource(R.string.stock_mode), current.mode ?: "?")
                    StatusLine(stringResource(R.string.stock_auto_off), current.autoPowerOff ?: "?")
                    StatusLine(stringResource(R.string.stock_firmware), current.firmware ?: "?")
                    if (unanswered) {
                        Spacer(Modifier.height(8.dp))
                        Label(stringResource(R.string.stock_no_answer), size = 14.sp, color = colors.bad)
                    }
                }
            }
            val camera = client
            if (Features.EXPERIMENTAL && camera != null) {
                if (live) {
                    StockPreview(camera) {
                        live = false
                        afterLive = true
                    }
                } else {
                    Card {
                        PillButton(
                            stringResource(R.string.stock_preview),
                            Modifier.fillMaxWidth(),
                            icon = AppIcons.Live,
                            style = ButtonStyle.PRIMARY,
                        ) {
                            afterLive = false
                            live = true
                        }
                        Spacer(Modifier.height(10.dp))
                        Label(
                            stringResource(if (afterLive) R.string.stock_preview_wait else R.string.stock_preview_note),
                            size = 14.sp,
                            color = colors.text2,
                        )
                    }
                }
            } else {
                Card { Label(stringResource(R.string.stock_read_only), size = 14.sp, color = colors.text2) }
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
        // Wi-Fi power saving delays bursts: the tablet got 9.6 of the camera's 15 pictures a second
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
            factory = { context ->
                SphereVideoView(
                    context = context,
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
                    // A locked screen ends the session: reopening by itself could find the camera restarting
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

    /** Once per session: a second surface does not open the camera again. */
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
