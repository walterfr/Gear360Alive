package com.simoneluconi.revive360.protocol

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A media item stored on the camera SD card, discovered via UPnP ContentDirectory:Browse in ML mode.
 */
data class StockMediaItem(
    val id: String,
    val title: String,
    val mediaUrl: String,
    val thumbUrl: String?,
    val screenUrl: String?,
    val sizeBytes: Long = 0L,
    val kind: FileKind = if (title.endsWith(".mp4", ignoreCase = true)) FileKind.VIDEO else FileKind.PHOTO
)

/**
 * The only SOAP actions this client can send to a camera on its original firmware: each one was read
 * from the camera's own action list and tested on an SM-R210 (docs/PLANO.md, section 13). Anything
 * else has to be added here on purpose, after its own test.
 */
enum class StockQuery { X_GetSettingInformation, X_GetStorage }

/** The live view's two actions, sent only inside a subscription, in this order, as tested on an SM-R210. */
enum class StockSessionAction(val arguments: String) {
    SetOperationState("<StateEvent>changeToRVF</StateEvent>"),
    GetInfomation(""),
}

data class StockStatus(
    val firmware: String?,
    val batteryPercent: Int?,
    val charging: Boolean,
    val mode: String?,
    val modes: List<String>,
    val autoPowerOff: String?,
    val totalMb: Long?,
    val freeMb: Long?,
)

/** A camera on its original firmware, over UPnP on its own Wi-Fi. Blocking: call on an IO dispatcher. */
class StockClient(
    private val host: String,
    private val connector: Connector,
    /** An unconnected socket on the camera's network: HttpURLConnection cannot send SUBSCRIBE. */
    private val openSocket: () -> Socket = { Socket() },
    private val eventPort: Int = PORT,
    private val callbackPort: Int = CALLBACK_PORT,
    private val settleMs: Long = SETTLE_MS,
) {
    private val base = "http://$host:$PORT"

    fun status(): StockStatus {
        wake()
        return parseStatus(query(StockQuery.X_GetSettingInformation), query(StockQuery.X_GetStorage))
    }

    /**
     * Lists media stored on the camera's SD card by switching to Media List mode (changeToML)
     * and querying ContentDirectory:Browse on container 8 (AllMedia).
     */
    fun listMedia(): List<StockMediaItem> {
        wake()
        val listener = ServerSocket(callbackPort)
        thread(name = "stock-events", isDaemon = true) { answerEvents(listener) }
        var sid: String? = null
        try {
            sid = subscribe(listener.localPort)
            Thread.sleep(settleMs)
            post("SetOperationState", "<StateEvent>changeToML</StateEvent>")
            Thread.sleep(settleMs)

            val browseArgs = "<ObjectID>8</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>100</RequestedCount><SortCriteria></SortCriteria>"
            val answer = post("Browse", browseArgs)
            return parseBrowseItems(answer)
        } finally {
            end(sid, listener)
        }
    }

    /**
     * Downloads a file from the camera's streaming server (port 7679) to [dest].
     */
    fun download(mediaUrl: String, dest: File, onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }) {
        val connection = connector.open(URL(mediaUrl)).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = 30_000
            setRequestProperty("User-Agent", STOCK_USER_AGENT)
            setRequestProperty("Access-Method", "manual")
            setRequestProperty("Connection", "close")
        }
        val code = connection.responseCode
        if (code != 200 && code != 206) {
            connection.disconnect()
            throw IOException("Download failed: HTTP $code")
        }
        val total = connection.contentLengthLong
        var downloaded = 0L
        dest.parentFile?.mkdirs()
        try {
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** The first request after a pause has answered 503 on the SM-R210, and the description then answers. */
    private fun wake() {
        repeat(2) { attempt ->
            val connection = open("$base$DESCRIPTION_PATH")
            try {
                connection.setRequestProperty("User-Agent", STOCK_USER_AGENT)
                connection.setRequestProperty("Access-Method", "manual")
                if (connection.responseCode == 200) return
            } finally {
                connection.disconnect()
            }
            if (attempt == 0) Thread.sleep(RETRY_DELAY_MS)
        }
        throw IOException("the camera does not answer")
    }

    /**
     * The live picture, by the official app's sequence as tested (docs/PLANO.md, section 13): SUBSCRIBE,
     * changeToRVF, GetInfomation, then the stream. Never retried. Closing the stream ends the session
     * with UNSUBSCRIBE, and the camera then stops answering for one to two minutes.
     */
    fun openLive(): LiveStream {
        wake()
        val listener = ServerSocket(callbackPort)
        thread(name = "stock-events", isDaemon = true) { answerEvents(listener) }
        var sid: String? = null
        try {
            sid = subscribe(listener.localPort)
            for (action in StockSessionAction.entries) {
                Thread.sleep(settleMs)
                val answer = post(action.name, action.arguments)
                if (action == StockSessionAction.GetInfomation && LIVE_PATH !in answer) {
                    throw IOException("the camera offers no $LIVE_PATH")
                }
            }
            Thread.sleep(settleMs)
            val connection = connector.open(URL("http://$host:$LIVE_PORT$LIVE_PATH")).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = LIVE_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", LIVE_USER_AGENT)
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = connection.responseCode
            if (code != 200 && code != 206) {
                connection.disconnect()
                throw IOException("live view: HTTP $code")
            }
            val session = sid
            val ended = AtomicBoolean(false)
            return LiveStream(connection.contentType ?: "", connection.inputStream) {
                if (ended.compareAndSet(false, true)) {
                    thread(name = "stock-end", isDaemon = true) {
                        connection.disconnect()
                        end(session, listener)
                    }
                }
            }
        } catch (e: Exception) {
            end(sid, listener)
            throw e
        }
    }

    private fun subscribe(port: Int): String {
        val answer = event { socket ->
            "SUBSCRIBE $EVENT_PATH HTTP/1.1\r\nHOST: $host:$eventPort\r\nUser-Agent: $STOCK_USER_AGENT\r\n" +
                "Access-Method: manual\r\nConnection: close\r\n" +
                "CALLBACK: <http://${socket.localAddress.hostAddress}:$port$CALLBACK_PATH>\r\n" +
                "NT: upnp:event\r\nTIMEOUT: $SUBSCRIBE_TIMEOUT\r\n\r\n"
        }
        if (!Regex("^HTTP/1\\.[01] 200").containsMatchIn(answer)) {
            throw IOException("SUBSCRIBE: ${answer.lineSequence().firstOrNull().orEmpty()}")
        }
        return Regex("(?im)^SID:\\s*(\\S+)").find(answer)?.groupValues?.get(1) ?: throw IOException("SUBSCRIBE: no SID")
    }

    /** UNSUBSCRIBE without waiting for an answer: the SM-R210 often closes the connection instead. */
    private fun end(sid: String?, listener: ServerSocket) {
        if (sid != null) {
            runCatching {
                event {
                    "UNSUBSCRIBE $EVENT_PATH HTTP/1.1\r\nHOST: $host:$eventPort\r\nUser-Agent: $STOCK_USER_AGENT\r\n" +
                        "Access-Method: manual\r\nConnection: close\r\nSID: $sid\r\n\r\n"
                }
            }
        }
        runCatching { listener.close() }
    }

    private fun event(request: (Socket) -> String): String = openSocket().use { socket ->
        socket.connect(InetSocketAddress(host, eventPort), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.getOutputStream().apply {
            write(request(socket).toByteArray(Charsets.US_ASCII))
            flush()
        }
        readHead(socket.getInputStream())
    }

    /** Answers the camera's NOTIFY with 200; their content is not used yet. */
    private fun answerEvents(listener: ServerSocket) {
        while (!listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                return
            }
            runCatching {
                socket.use {
                    it.soTimeout = TIMEOUT_MS
                    val input = it.getInputStream()
                    val head = readHead(input)
                    var left = Regex("(?im)^Content-Length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val buffer = ByteArray(4096)
                    while (left > 0) {
                        val n = input.read(buffer, 0, minOf(left, buffer.size))
                        if (n < 0) break
                        left -= n
                    }
                    it.getOutputStream().write(EVENT_ANSWER.toByteArray(Charsets.US_ASCII))
                }
            }
        }
    }

    private fun query(action: StockQuery): String = post(action.name, "")

    private fun post(action: String, arguments: String): String {
        val body = envelope(action, arguments).toByteArray()
        val connection = open("$base$CONTROL_PATH")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$SERVICE#$action\"")
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) throw IOException("$action: HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = connector.open(URL(url)).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("Connection", "close")
    }

    companion object {
        const val SSID_PREFIX = "Gear 360("
        private const val PORT = 7676
        private const val DESCRIPTION_PATH = "/smp_2_"
        private const val CONTROL_PATH = "/smp_4_"
        private const val SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"
        private const val STOCK_USER_AGENT = "SEC_RVF_ML_02:00:00:00:00:00"
        private const val TIMEOUT_MS = 8_000
        private const val RETRY_DELAY_MS = 1_000L
        private const val EVENT_PATH = "/smp_5_"
        private const val CALLBACK_PORT = 8059
        private const val CALLBACK_PATH = "/evetSub"
        /** Short: if UNSUBSCRIBE is lost, the subscription ends by itself. */
        private const val SUBSCRIBE_TIMEOUT = "Second-300"
        private const val EVENT_ANSWER = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        /** The pause between steps in the tested sequence */
        private const val SETTLE_MS = 3_000L
        private const val LIVE_PORT = 7679
        private const val LIVE_PATH = "/livestream_high.avi"
        private const val LIVE_USER_AGENT = "Android Linux"
        private const val LIVE_READ_TIMEOUT_MS = 15_000
        private const val HEAD_MAX = 16 * 1024

        fun envelope(action: StockQuery): String = envelope(action.name, "")

        fun envelope(action: String, arguments: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:$action xmlns:u=\"$SERVICE\">$arguments</u:$action>" +
                "</s:Body></s:Envelope>"

        /** An HTTP head, up to its blank line or the end of the stream */
        private fun readHead(input: InputStream): String {
            val out = StringBuilder()
            while (out.length < HEAD_MAX && !out.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                out.append(b.toChar())
            }
            return out.toString()
        }

        private fun tag(xml: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

        fun parseStatus(settings: String, storage: String): StockStatus {
            val dial = tag(settings, "DialModeValue").orEmpty()
            return StockStatus(
                firmware = tag(settings, "FWVersion"),
                batteryPercent = tag(settings, "BatteryLevel")?.toIntOrNull(),
                charging = tag(settings, "ChargingStatus").equals("On", ignoreCase = true),
                mode = tag(dial, "Default"),
                modes = Regex("<S>([^<]*)</S>").findAll(dial).map { it.groupValues[1] }.toList(),
                autoPowerOff = tag(tag(settings, "AutoPowerOffValue").orEmpty(), "Default"),
                totalMb = tag(storage, "TotalSpaceValue")?.toLongOrNull(),
                freeMb = tag(storage, "AvaiableSpaceValue")?.toLongOrNull(),
            )
        }

        fun parseBrowseItems(body: String): List<StockMediaItem> {
            val resultMatch = Regex("<Result>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL).find(body)
            val didl = resultMatch?.groupValues?.get(1)?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&") ?: ""
            val items = mutableListOf<StockMediaItem>()
            val itemRegex = Regex("<item\\b(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            for (match in itemRegex.findAll(didl)) {
                val itemXml = match.value
                val id = Regex("id=\"([^\"]+)\"").find(itemXml)?.groupValues?.get(1) ?: continue
                val title = Regex("<dc:title[^>]*>([^<]+)</dc:title>").find(itemXml)?.groupValues?.get(1) ?: id
                val resMatches = Regex("<res\\b([^>]*)>([^<]+)</res>").findAll(itemXml).toList()
                val urls = resMatches.map { it.groupValues[2].trim() }
                if (urls.isEmpty()) continue

                val mainUrl = urls.firstOrNull { !it.contains("thumb") && !it.contains("screen") } ?: urls[0]
                val thumbUrl = urls.firstOrNull { it.contains("thumb") }
                val screenUrl = urls.firstOrNull { it.contains("screen") }

                val size = Regex("size=\"([^\"]+)\"").find(resMatches[0].groupValues[1])?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                items.add(StockMediaItem(id = id, title = title, mediaUrl = mainUrl, thumbUrl = thumbUrl, screenUrl = screenUrl, sizeBytes = size))
            }
            return items
        }
    }
}
