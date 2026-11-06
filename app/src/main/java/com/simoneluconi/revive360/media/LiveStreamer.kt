package com.simoneluconi.revive360.media

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Non-blocking local HTTP/TCP live stream broadcaster (`:8554`) for OBS Studio, VLC, and browsers
 * (Feature #8 — Live Stream 360° / OBS Webcam Mode).
 *
 * Endpoints:
 * - `http://<ip>:8554/` : Full-bleed HTML page for **OBS Browser Source** or any web browser.
 * - `http://<ip>:8554/live.mjpeg` : GPU-stitched 360° Equirectangular / Reframe / Little Planet
 *   stream (with FlowState horizon lock & Nadir Patch applied) for **OBS Media Source / Browser**.
 * - `http://<ip>:8554/live.hevc` : Raw Annex-B HEVC stream with cached `VPS/SPS/PPS` headers for
 *   **VLC / ffplay / OBS Media Source**.
 *
 * Uses per-client bounded [ArrayBlockingQueue]s so slow or stalled Wi-Fi clients never block
 * [LiveViewDecoder] or the OpenGL rendering thread.
 */
class LiveStreamer(val port: Int = DEFAULT_PORT) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val hevcClients = CopyOnWriteArrayList<HevcClient>()
    private val mjpegClients = CopyOnWriteArrayList<MjpegClient>()
    @Volatile private var cachedParameterSets: ByteArray? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    val clientCount: Int
        get() = hevcClients.size + mjpegClients.size

    val hasMjpegClients: Boolean
        get() = mjpegClients.isNotEmpty()

    val isRunning: Boolean
        get() = running.get()

    private class HevcClient(
        val socket: Socket,
        val output: OutputStream,
        val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(24),
        @Volatile var sentKeyframe: Boolean = false,
    ) : AutoCloseable {
        override fun close() {
            queue.clear()
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }

    private class MjpegClient(
        val socket: Socket,
        val output: OutputStream,
        val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(3),
    ) : AutoCloseable {
        override fun close() {
            queue.clear()
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(port).apply { reuseAddress = true }
        serverSocket = ss
        acceptThread = thread(name = "g360-obs-accept", isDaemon = true) {
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                thread(name = "g360-obs-client", isDaemon = true) {
                    handleClientConnection(client)
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3_000
            val inp = BufferedInputStream(socket.getInputStream())
            inp.mark(16)
            val prefix = ByteArray(4)
            val n = inp.read(prefix)
            val out = socket.getOutputStream()
            var path = "/live.hevc"

            if (n == 4 && (String(prefix, Charsets.US_ASCII).let { it == "GET " || it == "HEAD" })) {
                val reqLine = StringBuilder()
                var state = 0
                var firstLine = true
                while (state < 4) {
                    val b = inp.read()
                    if (b < 0) break
                    if (firstLine) {
                        if (b == '\r'.code || b == '\n'.code) firstLine = false
                        else if (reqLine.length < 256) reqLine.append(b.toChar())
                    }
                    state = when {
                        b == '\r'.code && (state == 0 || state == 2) -> state + 1
                        b == '\n'.code && (state == 1 || state == 3) -> state + 1
                        else -> 0
                    }
                }
                val token = reqLine.toString().trim().substringBefore(' ').substringBefore('?')
                if (token.isNotEmpty()) path = token
            }

            socket.soTimeout = 0
            when {
                path == "/" || path.equals("/index.html", ignoreCase = true) -> {
                    val html = """
                        <!DOCTYPE html>
                        <html><head><meta charset="utf-8"><title>Gear360 Alive — OBS Live</title>
                        <style>html,body{margin:0;padding:0;background:#000;overflow:hidden;width:100%;height:100%}
                        img{width:100vw;height:100vh;object-fit:contain;display:block}</style></head>
                        <body><img src="/live.mjpeg" alt="Gear360 Live"/></body></html>
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    val hdr = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ${html.size}\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    out.write(hdr)
                    out.write(html)
                    out.flush()
                    socket.close()
                }
                path.endsWith(".mjpeg", ignoreCase = true) || path.endsWith(".mjpg", ignoreCase = true) -> {
                    val hdr = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: multipart/x-mixed-replace; boundary=g360frame\r\n")
                        append("Cache-Control: no-cache, no-store\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    out.write(hdr)
                    out.flush()
                    val session = MjpegClient(socket, out)
                    mjpegClients.add(session)
                    try {
                        while (running.get() && !socket.isClosed) {
                            val part = session.queue.poll(500L, TimeUnit.MILLISECONDS) ?: continue
                            out.write(part)
                            out.flush()
                        }
                    } finally {
                        mjpegClients.remove(session)
                        session.close()
                    }
                }
                else -> {
                    if (n == 4 && (String(prefix, Charsets.US_ASCII).let { it == "GET " || it == "HEAD" })) {
                        val hdr = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: video/hevc\r\n")
                            append("Cache-Control: no-cache, no-store\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Connection: close\r\n\r\n")
                        }.toByteArray(Charsets.US_ASCII)
                        out.write(hdr)
                        out.flush()
                    }
                    val session = HevcClient(socket, out)
                    cachedParameterSets?.let { csd -> session.queue.offer(csd) }
                    hevcClients.add(session)
                    try {
                        while (running.get() && !socket.isClosed) {
                            val au = session.queue.poll(500L, TimeUnit.MILLISECONDS) ?: continue
                            out.write(au)
                            out.flush()
                        }
                    } finally {
                        hevcClients.remove(session)
                        session.close()
                    }
                }
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    /**
     * Non-blocking O(1) broadcast of an Annex-B HEVC access unit (`00VD` payload) to all `/live.hevc` clients.
     */
    fun broadcastAccessUnit(au: ByteArray) {
        val isKey = LiveViewDecoder.isKeyFrame(au)
        if (isKey) {
            LiveViewDecoder.parameterSets(au)?.let { cachedParameterSets = it }
        }
        if (hevcClients.isEmpty()) return

        for (client in hevcClients) {
            if (!client.sentKeyframe && !isKey && cachedParameterSets == null) continue
            if (isKey) client.sentKeyframe = true
            if (!client.queue.offer(au)) {
                // Client TCP window is full: drop stale frames and wait for next keyframe
                client.queue.clear()
                if (isKey) {
                    cachedParameterSets?.let { client.queue.offer(it) }
                    client.queue.offer(au)
                    client.sentKeyframe = true
                } else {
                    client.sentKeyframe = false
                }
            }
        }
    }

    /**
     * Non-blocking O(1) broadcast of a GPU-stitched JPEG frame to all connected `/live.mjpeg`
     * (OBS Browser Source / Media Source) clients.
     */
    fun broadcastStitchedJpeg(jpegBytes: ByteArray) {
        if (mjpegClients.isEmpty()) return
        val header = "--g360frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        val packet = ByteArrayOutputStream(header.size + jpegBytes.size + 2).apply {
            write(header)
            write(jpegBytes)
            write('\r'.code)
            write('\n'.code)
        }.toByteArray()

        for (client in mjpegClients) {
            if (!client.queue.offer(packet)) {
                client.queue.poll()
                client.queue.offer(packet)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (c in hevcClients) c.close()
        hevcClients.clear()
        for (c in mjpegClients) c.close()
        mjpegClients.clear()
        acceptThread?.join(500L)
        acceptThread = null
    }

    override fun close() = stop()

    companion object {
        const val DEFAULT_PORT = 8554

        /** Returns the best local IPv4 base URL for OBS Studio (`http://<ip>:<port>/live.mjpeg`). */
        fun localStreamUrl(port: Int = DEFAULT_PORT): String {
            val ip = runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                ifaces
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress ?: "" }
                    .sortedBy { if (it.startsWith("192.168.107.")) 1 else 0 }
                    .firstOrNull { it.isNotEmpty() }
            }.getOrNull() ?: "127.0.0.1"
            return "http://$ip:$port/live.mjpeg"
        }
    }
}
