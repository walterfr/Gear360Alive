package com.simoneluconi.revive360.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open Spherical Camera (OSC v1) client for the Samsung Gear 360 (2017 SM-R210) in stock
 * "Connect to Street View" mode (`192.168.107.1:80` or `192.168.43.1:80`).
 *
 * Allows zero-mod photo capture, file listing (with automatic fallback across `camera.listFiles`,
 * `camera.listImages` and `camera._listFiles`), file download, deletion, and battery/storage queries.
 */
class OscClient(
    host: String = DEFAULT_HOST,
    port: Int = DEFAULT_PORT,
    private val connector: Connector = Connector { it.openConnection() as HttpURLConnection },
) {
    private val base = "http://${if (':' in host) "[$host]" else host}:$port"
    private var sessionId: String? = null

    data class OscState(
        val sessionId: String?,
        val batteryPercent: Int,
        val storageUri: String?,
    )

    fun info(): CameraInfo {
        val o = JSONObject(request("GET", "/osc/info"))
        val model = o.optString("model", "SM-R210").ifEmpty { "SM-R210" }
        val firmware = o.optString("firmwareVersion", "stock-osc")
        val serial = o.optString("serialNumber", "000000")
        return CameraInfo(
            protocol = Protocol.VERSION,
            serverName = "osc-streetview",
            serverVersion = "1.0",
            model = model,
            modelRaw = model,
            firmware = firmware,
            adapter = "osc",
            adapterStatus = "supported",
            mac = serial,
            name = "Gear 360 ($model OSC)",
            capabilities = Capabilities(
                captureModes = listOf(CaptureMode.PHOTO),
                photoQualities = emptyList(),
                photoResolutions = listOf("5472x2736"),
                resolutionSettable = false,
                videoModes = emptyList(),
                record = false,
                liveview = false,
                listFiles = true,
                download = true,
                range = false,
                delete = true,
                thumbnails = true,
                battery = true,
                storage = false,
                batteryTemperature = false,
                leds = emptyList(),
                debugLog = false,
                time = false,
                power = false,
                settings = emptyList(),
            ),
        )
    }

    fun state(): OscState {
        val o = JSONObject(request("POST", "/osc/state", "{}"))
        val state = o.optJSONObject("state") ?: JSONObject()
        val sid = state.optString("sessionId", "").takeIf { it.isNotEmpty() }
        if (sid != null) sessionId = sid
        val batt = (state.optDouble("batteryLevel", 0.0) * 100.0).toInt().coerceIn(0, 100)
        val storage = state.optString("_storageUri", state.optString("storageUri", "")).takeIf { it.isNotEmpty() }
        return OscState(sid, batt, storage)
    }

    fun ensureSession(): String {
        sessionId?.let { return it }
        state().sessionId?.let { return it }
        val res = execute("camera.startSession", JSONObject())
        val sid = res.optJSONObject("results")?.optString("sessionId", "")?.takeIf { it.isNotEmpty() }
            ?: "osc-session"
        sessionId = sid
        return sid
    }

    fun takePicture(): String {
        val sid = ensureSession()
        val res = execute("camera.takePicture", JSONObject().put("sessionId", sid))
        val state = res.optString("state")
        if (state == "inProgress") {
            val id = res.optString("id")
            repeat(30) {
                Thread.sleep(500)
                val poll = JSONObject(request("POST", "/osc/commands/status", JSONObject().put("id", id).toString()))
                if (poll.optString("state") == "done") {
                    return poll.optJSONObject("results")?.optString("fileUri") ?: ""
                }
            }
        }
        return res.optJSONObject("results")?.optString("fileUri") ?: ""
    }

    /**
     * Lists files using Samsung's OSC command variants (`camera.listFiles`, `camera.listImages`,
     * `camera._listFiles`).
     */
    fun listFiles(entryCount: Int = 100): List<CameraFile> {
        val commands = listOf(
            "camera.listFiles" to JSONObject().put("fileType", "all").put("entryCount", entryCount).put("maxThumbSize", 0),
            "camera.listImages" to JSONObject().put("entryCount", entryCount).put("includeThumb", false),
            "camera._listFiles" to JSONObject().put("entryCount", entryCount),
        )
        for ((cmd, params) in commands) {
            val res = runCatching { execute(cmd, params) }.getOrNull() ?: continue
            val entries = res.optJSONObject("results")?.optJSONArray("entries") ?: continue
            return parseEntries(entries)
        }
        return emptyList()
    }

    fun download(fileUri: String, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val path = if (fileUri.startsWith("http://") || fileUri.startsWith("https://")) {
            URL(fileUri).path
        } else if (fileUri.startsWith("/")) {
            fileUri
        } else {
            "/files/$fileUri"
        }
        val conn = connector.open(URL(base + path)).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw CameraException(code, CameraException.INTERNAL, "OSC HTTP $code")
            val total = conn.contentLengthLong
            FileOutputStream(target).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    fun delete(fileUri: String) {
        execute("camera.delete", JSONObject().put("fileUri", fileUri).put("fileUrls", JSONArray().put(fileUri)))
    }

    fun execute(name: String, parameters: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("name", name).apply {
            if (parameters.length() > 0) put("parameters", parameters)
        }.toString()
        return JSONObject(request("POST", "/osc/commands/execute", body))
    }

    private fun parseEntries(entries: JSONArray): List<CameraFile> {
        val out = ArrayList<CameraFile>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val uri = e.optString("fileUrl", e.optString("fileUri", ""))
            val name = e.optString("name", uri.substringAfterLast('/'))
            if (name.isEmpty()) continue
            val size = e.optLong("size", 0L)
            val kind = when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> FileKind.PHOTO
                "mp4", "mov" -> FileKind.VIDEO
                "dng", "srw", "raw" -> FileKind.RAW
                else -> FileKind.OTHER
            }
            out += CameraFile(
                path = uri.ifEmpty { "DCIM/100PHOTO/$name" },
                name = name,
                size = size,
                mtime = System.currentTimeMillis() / 1000L - i,
                kind = kind,
            )
        }
        return out
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val conn = connector.open(URL(base + path)).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("X-XSRF-Protected", "1")
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
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.use(InputStream::readBytes)?.toString(Charsets.UTF_8) }.getOrNull()
                throw CameraException(code, CameraException.INTERNAL, err ?: "OSC HTTP $code")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.107.1"
        const val FALLBACK_HOST = "192.168.43.1"
        const val DEFAULT_PORT = 80
    }
}
