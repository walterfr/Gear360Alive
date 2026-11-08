package com.simoneluconi.revive360.protocol

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The only OSC v1 commands this client can send to a camera on its original firmware in "Connect to
 * Street View" mode: each one was tested on an SM-R210 (docs/PLANO.md, section 13).
 */
enum class OscCommand(val wire: String) {
    StartSession("camera.startSession"),
    CloseSession("camera.closeSession"),
    GetOptions("camera.getOptions"),
    TakePicture("camera.takePicture"),
    GetImage("camera.getImage"),
}

data class OscStatus(
    val model: String?,
    val firmware: String?,
    val batteryPercent: Int?,
)

/**
 * Open Spherical Camera (OSC v1, port 80) client for the SM-R210 in stock "Connect to Street View"
 * mode (`Gear 360(XXXX).OSC`). Blocking: call on an IO dispatcher.
 */
class OscClient(
    host: String = DEFAULT_HOST,
    port: Int = DEFAULT_PORT,
    private val connector: Connector = Connector { it.openConnection() as HttpURLConnection },
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
    private val limitMs: Long = LIMIT_MS,
) {
    private val base = "http://${if (':' in host) "[$host]" else host}:$port"

    fun status(): OscStatus {
        val info = JSONObject(request("GET", "/osc/info"))
        val state = JSONObject(request("POST", "/osc/state", "{}")).optJSONObject("state") ?: JSONObject()
        val battery = if (state.has("batteryLevel")) {
            (state.optDouble("batteryLevel") * 100.0).toInt().coerceIn(0, 100)
        } else {
            null
        }
        return OscStatus(
            model = info.optString("model", "").ifEmpty { null },
            firmware = info.optString("firmwareVersion", "").ifEmpty { null },
            batteryPercent = battery,
        )
    }

    /**
     * Takes one stitched 360° photo (`SAM_XXXX.JPG`), polls `/osc/commands/status` until `done`,
     * closes the session, and downloads the JPEG via `camera.getImage` into [target]. Never retries
     * `takePicture`. Returns the file name on the card.
     */
    fun shoot(target: File): String {
        request("POST", "/osc/state", "{}")
        val started = execute(OscCommand.StartSession, JSONObject())
        val sid = started.optJSONObject("results")?.optString("sessionId", "").orEmpty()
        if (sid.isEmpty() || sid == NO_SESSION) throw IOException("OSC: no session")
        val fileUri = try {
            if (settleMs > 0) Thread.sleep(settleMs)
            var answer = execute(OscCommand.TakePicture, JSONObject().put("sessionId", sid))
            val commandId = answer.optString("id", "")
            val deadline = System.currentTimeMillis() + limitMs
            while (answer.optString("state") == "inProgress" && commandId.isNotEmpty() && System.currentTimeMillis() < deadline) {
                if (pollMs > 0) Thread.sleep(pollMs)
                answer = JSONObject(request("POST", "/osc/commands/status", JSONObject().put("id", commandId).toString()))
            }
            val finalState = answer.optString("state")
            if (finalState != "done") throw IOException("OSC takePicture: $finalState")
            val uri = answer.optJSONObject("results")?.optString("fileUri", "").orEmpty()
            if (uri.isEmpty()) throw IOException("OSC takePicture: no fileUri")
            if (settleMs > 0) Thread.sleep(settleMs)
            uri
        } finally {
            runCatching { execute(OscCommand.CloseSession, JSONObject().put("sessionId", sid)) }
        }
        if (settleMs > 0) Thread.sleep(settleMs)
        getImage(fileUri, target)
        return fileUri.substringAfterLast('/').ifEmpty { "SAM_360.JPG" }
    }

    fun getImage(fileUri: String, target: File) {
        val body = JSONObject()
            .put("name", OscCommand.GetImage.wire)
            .put("parameters", JSONObject().put("fileUri", fileUri))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val conn = open("/osc/commands/execute", IMAGE_READ_TIMEOUT_MS).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) throw IOException("OSC getImage: HTTP $code")
            FileOutputStream(target).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var b0 = -1
                    var b1 = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            if (total == 0L) b0 = buf[0].toInt() and 0xFF
                            if (total == 0L && n > 1) b1 = buf[1].toInt() and 0xFF
                            else if (total == 1L) b1 = buf[0].toInt() and 0xFF
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                    if (total < 2L || b0 != 0xFF || b1 != 0xD8) {
                        throw IOException("OSC getImage: not a JPEG")
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun execute(command: OscCommand, parameters: JSONObject): JSONObject {
        val body = JSONObject().put("name", command.wire).put("parameters", parameters).toString()
        return JSONObject(request("POST", "/osc/commands/execute", body))
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val conn = open(path, READ_TIMEOUT_MS).apply {
            requestMethod = method
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("OSC $path: HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, readTimeoutMs: Int): HttpURLConnection = connector.open(URL(base + path)).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = readTimeoutMs
        useCaches = false
        setRequestProperty("Connection", "close")
    }

    companion object {
        const val DEFAULT_HOST = "192.168.43.1"
        const val DEFAULT_PORT = 80
        private const val NO_SESSION = "SID_0000"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val IMAGE_READ_TIMEOUT_MS = 30_000
        private const val SETTLE_MS = 2_000L
        private const val POLL_MS = 2_000L
        private const val LIMIT_MS = 60_000L
    }
}
