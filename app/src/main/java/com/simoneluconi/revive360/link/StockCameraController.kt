package com.simoneluconi.revive360.link

import android.content.Context
import android.net.Network
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.Connector
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.OscClient
import com.simoneluconi.revive360.protocol.OscStatus
import com.simoneluconi.revive360.protocol.StockClient
import com.simoneluconi.revive360.protocol.StockMediaItem
import com.simoneluconi.revive360.protocol.StockStatus
import com.simoneluconi.revive360.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class StockMode {
    IOS,
    OSC
}

sealed interface WifiLayerState {
    data object Disconnected : WifiLayerState
    data object Connecting : WifiLayerState
    data class Connected(val host: String, val frequencyMhz: Int = 0, val linkMbps: Int = 0) : WifiLayerState
    data class Lost(val atMillis: Long = System.currentTimeMillis()) : WifiLayerState
    data class Unavailable(val atMillis: Long = System.currentTimeMillis()) : WifiLayerState
}

sealed interface CameraHealthState {
    data object Unknown : CameraHealthState
    data class HealthyIos(val status: StockStatus, val atMillis: Long = System.currentTimeMillis()) : CameraHealthState
    data class HealthyOsc(val status: OscStatus, val atMillis: Long = System.currentTimeMillis()) : CameraHealthState
    data class Unresponsive(val lastError: String?, val atMillis: Long = System.currentTimeMillis()) : CameraHealthState
}

sealed interface SessionLayerState {
    data object Idle : SessionLayerState
    data object LivePreview : SessionLayerState
    data object CapturingPhoto : SessionLayerState
    data object MediaLibrary : SessionLayerState
    data class CoolingDown(val secondsRemaining: Int) : SessionLayerState
}

data class StockCameraState(
    val mode: StockMode = StockMode.IOS,
    val wifi: WifiLayerState = WifiLayerState.Disconnected,
    val health: CameraHealthState = CameraHealthState.Unknown,
    val session: SessionLayerState = SessionLayerState.Idle,
    val lastDropReason: String? = null,
    /** The camera's files while its gallery is open; null when it is closed. */
    val media: List<StockMediaItem>? = null,
    val mediaLoading: Boolean = false,
    /** The title of the file being downloaded */
    val downloading: String? = null,
    val lastDownloaded: String? = null,
    val mediaError: String? = null,
)

/**
 * Single controller for cameras running stock firmware (SM-R210 / SM-C200).
 * Lives outside Compose UI lifecycle to preserve network link and session states.
 */
class StockCameraController(
    context: Context,
    private val settings: AppSettings,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val appContext = context.applicationContext
    private val network = CameraNetwork(appContext)

    private val _state = MutableStateFlow(StockCameraState())
    val state: StateFlow<StockCameraState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var cooldownJob: Job? = null
    private var activeNetwork: Network? = null
    private var releaseJob: Job? = null

    @Volatile private var mediaSession: StockClient.MediaSession? = null
    /** One request to the camera's media server at a time: thumbnails, downloads and the session's end. */
    private val mediaLane = Mutex()
    private val thumbnails = ConcurrentHashMap<String, ByteArray>()

    var stockClient: StockClient? = null
        private set
    var oscClient: OscClient? = null
        private set

    private fun setupClients(network: Network?, host: String, mode: StockMode) {
        if (network != null) activeNetwork = network
        val net = activeNetwork ?: return
        val connector = Connector { url -> net.openConnection(url) as HttpURLConnection }
        if (mode == StockMode.OSC) {
            oscClient = OscClient(host, connector = connector)
            stockClient = null
        } else {
            stockClient = StockClient(
                host,
                connector,
                openSocket = { net.socketFactory.createSocket() }
            )
            oscClient = null
        }
    }

    fun setMode(mode: StockMode) {
        if (_state.value.mode == mode) return
        closeMedia()
        _state.update { it.copy(mode = mode, health = CameraHealthState.Unknown) }
        val currentWifi = _state.value.wifi
        if (currentWifi is WifiLayerState.Connected) {
            setupClients(null, currentWifi.host, mode)
            startPolling()
        }
    }

    fun connect() {
        val currentMode = _state.value.mode
        val activeCode = if (currentMode == StockMode.OSC) {
            settings.oscPassphrase.orEmpty()
        } else {
            settings.stockPassphrase.orEmpty()
        }

        disconnectInternal(keepMode = true)
        _state.update {
            it.copy(
                wifi = WifiLayerState.Connecting,
                health = CameraHealthState.Unknown,
                session = SessionLayerState.Idle
            )
        }

        network.requestStock(activeCode, object : CameraNetwork.Listener {
            override fun onAvailable(network: Network, host: String) {
                activeNetwork = network
                _state.update {
                    it.copy(
                        wifi = WifiLayerState.Connected(host = host),
                        health = CameraHealthState.Unknown,
                        lastDropReason = null
                    )
                }
                // Detect which service is actually answering on the camera
                detectAndApplyCameraMode(network, host)
            }

            override fun onSsid(ssid: String) {
                val detectedMode = if (ssid.endsWith(".OSC", ignoreCase = true)) StockMode.OSC else StockMode.IOS
                if (_state.value.mode != detectedMode) {
                    EventLog.log("StockCameraController: detected $detectedMode from SSID $ssid")
                    setMode(detectedMode)
                }
            }

            override fun onLost() {
                val now = System.currentTimeMillis()
                EventLog.log("StockCameraController: Wi-Fi onLost at $now")
                stopPolling()
                abandonMedia()
                stockClient = null
                oscClient = null
                activeNetwork = null
                _state.update {
                    it.copy(
                        wifi = WifiLayerState.Lost(now),
                        lastDropReason = "Wi-Fi connection lost"
                    )
                }
            }

            override fun onUnavailable() {
                val now = System.currentTimeMillis()
                EventLog.log("StockCameraController: Wi-Fi onUnavailable at $now")
                stopPolling()
                abandonMedia()
                stockClient = null
                oscClient = null
                activeNetwork = null
                _state.update {
                    it.copy(
                        wifi = WifiLayerState.Unavailable(now),
                        lastDropReason = "Wi-Fi unavailable or canceled"
                    )
                }
            }

            override fun onLink(frequencyMhz: Int, linkMbps: Int) {
                _state.update { current ->
                    val wifi = current.wifi
                    if (wifi is WifiLayerState.Connected) {
                        current.copy(wifi = wifi.copy(frequencyMhz = frequencyMhz, linkMbps = linkMbps))
                    } else {
                        current
                    }
                }
            }
        })
    }

    /**
     * Inspects camera ports directly (port 80 for OSC vs port 7676 for iOS UPnP/SOAP)
     * so that the app instantly knows whether the camera is in Street View or Connect to iOS mode,
     * even if Android didn't reveal the SSID name.
     */
    private fun detectAndApplyCameraMode(network: Network, host: String) {
        scope.launch(Dispatchers.IO) {
            val isOscOpen = testPort(network, host, 80, timeoutMs = 700)
            val isSoapOpen = testPort(network, host, 7676, timeoutMs = 700)
            val detected = when {
                isOscOpen && !isSoapOpen -> StockMode.OSC
                isSoapOpen && !isOscOpen -> StockMode.IOS
                else -> _state.value.mode
            }
            EventLog.log("StockCameraController: Port probe (80=$isOscOpen, 7676=$isSoapOpen) -> mode=$detected")
            withContext(Dispatchers.Main) {
                if (_state.value.mode != detected) {
                    _state.update { it.copy(mode = detected) }
                }
                setupClients(network, host, detected)
                startPolling()
            }
        }
    }

    private fun testPort(network: Network, host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val socket = network.socketFactory.createSocket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Leaves the camera's Wi-Fi. An open gallery is ended first, while the camera can still be told. */
    fun disconnect() {
        val session = takeMedia()
        if (session == null) {
            disconnectInternal(keepMode = false)
            return
        }
        scope.launch {
            mediaLane.withLock { withContext(Dispatchers.IO) { session.close() } }
            disconnectInternal(keepMode = false)
        }
    }

    private fun disconnectInternal(keepMode: Boolean) {
        stopPolling()
        cooldownJob?.cancel()
        cooldownJob = null
        abandonMedia()
        activeNetwork = null
        network.release()
        stockClient = null
        oscClient = null
        _state.update {
            it.copy(
                wifi = WifiLayerState.Disconnected,
                health = CameraHealthState.Unknown,
                session = SessionLayerState.Idle
            )
        }
    }

    // ---- the camera's gallery (Media List mode) ----

    /** Lists the camera's files. Only from idle: the camera holds one session at a time. */
    fun openMedia() {
        val client = stockClient ?: return
        val current = _state.value
        if (current.session !is SessionLayerState.Idle || current.mediaLoading) return
        setSessionState(SessionLayerState.MediaLibrary)
        _state.update { it.copy(mediaLoading = true, mediaError = null, lastDownloaded = null) }
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { client.openMediaSession() }
                if (stockClient !== client) {
                    // The link went away meanwhile: nothing to show, only the session's port to free
                    thread(isDaemon = true) { session.close() }
                    return@launch
                }
                mediaSession = session
                _state.update { it.copy(media = session.items, mediaLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                EventLog.log("Stock gallery failed: ${e.message}")
                _state.update { it.copy(mediaLoading = false, mediaError = e.message ?: e.javaClass.simpleName) }
                if (_state.value.session is SessionLayerState.MediaLibrary) setSessionState(SessionLayerState.Idle)
            }
        }
    }

    /**
     * Ends the gallery, after a transfer in progress. The camera then stops answering for a while
     * (UNSUBSCRIBE closes its session), hence the countdown.
     */
    fun closeMedia() {
        val session = takeMedia() ?: return
        scope.launch {
            mediaLane.withLock { withContext(Dispatchers.IO) { session.close() } }
            if (_state.value.session is SessionLayerState.MediaLibrary) startCooldown()
        }
    }

    /** Saves one of the camera's files to the phone. One at a time. */
    fun download(item: StockMediaItem) {
        val client = stockClient ?: return
        if (mediaSession == null || _state.value.downloading != null) return
        _state.update { it.copy(downloading = item.title, mediaError = null, lastDownloaded = null) }
        scope.launch {
            // Not the file's own name: that text comes from the camera
            val tmp = File(appContext.cacheDir, "stock_dl_${System.nanoTime()}")
            try {
                mediaLane.withLock {
                    withContext(Dispatchers.IO) {
                        client.download(item.mediaUrl, tmp)
                        MediaSaver.publish(
                            appContext,
                            item.title,
                            item.kind,
                            if (item.kind == FileKind.VIDEO) "video/mp4" else "image/jpeg",
                            System.currentTimeMillis() / 1000L,
                            tmp,
                        )
                    }
                }
                EventLog.log("Stock gallery: saved ${item.title}")
                _state.update { it.copy(lastDownloaded = item.title) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                EventLog.log("Stock gallery: ${item.title} failed: ${e.message}")
                _state.update { it.copy(mediaError = e.message ?: e.javaClass.simpleName) }
            } finally {
                tmp.delete()
                _state.update { it.copy(downloading = null) }
            }
        }
    }

    /** A file's small picture, fetched once and one at a time; null when there is none. */
    suspend fun thumbnail(item: StockMediaItem): ByteArray? {
        val url = item.thumbUrl ?: return null
        thumbnails[url]?.let { return it }
        return mediaLane.withLock {
            val client = stockClient
            if (mediaSession == null || client == null) return@withLock null
            thumbnails[url] ?: withContext(Dispatchers.IO) { client.fetchThumbnail(url) }?.also { thumbnails[url] = it }
        }
    }

    /** The open session, taken out of the state: the caller ends it. */
    private fun takeMedia(): StockClient.MediaSession? {
        val session = mediaSession
        mediaSession = null
        thumbnails.clear()
        _state.update { it.copy(media = null, mediaLoading = false, mediaError = null, lastDownloaded = null) }
        return session
    }

    /** The link is gone or going: frees the session's port without waiting for the camera. */
    private fun abandonMedia() {
        takeMedia()?.let { session -> thread(isDaemon = true) { session.close() } }
        if (_state.value.session is SessionLayerState.MediaLibrary) {
            _state.update { it.copy(session = SessionLayerState.Idle) }
        }
    }

    // ---- app in the background ----

    /** Lets the camera's Wi-Fi go shortly after the app leaves the screen, unless something is in use. */
    fun onBackground() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(BACKGROUND_RELEASE_MS)
            val current = _state.value
            val unused = current.session is SessionLayerState.Idle || current.session is SessionLayerState.CoolingDown
            if (current.wifi !is WifiLayerState.Disconnected && unused && current.downloading == null) {
                EventLog.log("In the background: releasing the stock camera link")
                disconnect()
            }
        }
    }

    fun onForeground() {
        releaseJob?.cancel()
        releaseJob = null
    }

    fun setSessionState(session: SessionLayerState) {
        _state.update { it.copy(session = session) }
        if (session is SessionLayerState.LivePreview || session is SessionLayerState.CapturingPhoto || session is SessionLayerState.MediaLibrary) {
            stopPolling()
        } else if (session is SessionLayerState.Idle && _state.value.wifi is WifiLayerState.Connected) {
            startPolling()
        }
    }

    fun startCooldown(seconds: Int = 60, onFinished: () -> Unit = {}) {
        cooldownJob?.cancel()
        setSessionState(SessionLayerState.CoolingDown(seconds))
        cooldownJob = scope.launch {
            for (remaining in seconds downTo 1) {
                _state.update { it.copy(session = SessionLayerState.CoolingDown(remaining)) }
                delay(1000L)
            }
            _state.update { it.copy(session = SessionLayerState.Idle) }
            onFinished()
        }
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                val currentState = _state.value
                if (currentState.session !is SessionLayerState.Idle) {
                    delay(5_000L)
                    continue
                }
                when (currentState.mode) {
                    StockMode.IOS -> {
                        val client = stockClient
                        if (client != null) {
                            try {
                                val s = withContext(Dispatchers.IO) { client.status() }
                                _state.update { it.copy(health = CameraHealthState.HealthyIos(s)) }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                EventLog.log("StockCameraController poll iOS error: ${e.message}")
                                _state.update {
                                    it.copy(health = CameraHealthState.Unresponsive(e.message))
                                }
                            }
                        }
                    }
                    StockMode.OSC -> {
                        val client = oscClient
                        if (client != null) {
                            try {
                                val s = withContext(Dispatchers.IO) { client.status() }
                                _state.update { it.copy(health = CameraHealthState.HealthyOsc(s)) }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                EventLog.log("StockCameraController poll OSC error: ${e.message}")
                                _state.update {
                                    it.copy(health = CameraHealthState.Unresponsive(e.message))
                                }
                            }
                        }
                    }
                }
                delay(20_000L)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun release() {
        disconnect()
    }

    private companion object {
        const val BACKGROUND_RELEASE_MS = 30_000L
    }
}
