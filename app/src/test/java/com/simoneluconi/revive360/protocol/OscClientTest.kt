package com.simoneluconi.revive360.protocol

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OscClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(name: String) = javaClass.getResource("/stock/$name")!!.readText()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var server: HttpServer? = null
    private var startSessionStatus = 200
    private var startSessionBody: String? = null
    private var takePictureStatus = 200
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
        ByteArray(1024) { (it and 0xFF).toByte() } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0x46, 0x54)

    private fun client(): OscClient {
        val infoBody = resource("osc_info.json")
        val pictureReport = JSONObject(resource("osc_picture.json"))
        val stateBody = pictureReport.getJSONObject("state").getString("body")
        val defaultStartBody = pictureReport.getJSONObject("startSession").getString("body")
        val shotBody = pictureReport.getJSONObject("picture").getJSONObject("takePicture").getString("body")
        val polls = pictureReport.getJSONObject("picture").getJSONArray("polls")
        val closeBody = pictureReport.getJSONObject("closeSession").getString("body")
        var pollIndex = 0

        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val path = ex.requestURI.path
            val reqText = ex.requestBody.readBytes().decodeToString()
            val cmdName = runCatching { JSONObject(reqText).optString("name") }.getOrDefault("")
            requests += "${ex.requestMethod} $path $cmdName".trim()
            bodies += reqText

            if (path == "/osc/info") {
                val bytes = infoBody.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                ex.close()
                return@createContext
            }
            if (path == "/osc/state") {
                val bytes = stateBody.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                ex.close()
                return@createContext
            }
            if (path == "/osc/commands/status") {
                val idx = pollIndex.coerceAtMost(polls.length() - 1)
                pollIndex++
                val bytes = polls.getJSONObject(idx).getString("body").toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                ex.close()
                return@createContext
            }
            when (cmdName) {
                "camera.startSession" -> {
                    val bytes = (startSessionBody ?: defaultStartBody).toByteArray()
                    ex.sendResponseHeaders(startSessionStatus, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                "camera.takePicture" -> {
                    val bytes = shotBody.toByteArray()
                    ex.sendResponseHeaders(takePictureStatus, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                "camera.closeSession" -> {
                    val bytes = closeBody.toByteArray()
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                "camera.getImage" -> {
                    ex.responseHeaders.set("Content-Type", "image/jpeg")
                    ex.sendResponseHeaders(200, fakeJpeg.size.toLong())
                    ex.responseBody.use { it.write(fakeJpeg) }
                }
                else -> ex.sendResponseHeaders(400, -1)
            }
            ex.close()
        }
        http.start()
        server = http
        return OscClient(
            "127.0.0.1",
            http.address.port,
            Connector { it.openConnection() as HttpURLConnection },
            settleMs = 0,
            pollMs = 0,
            limitMs = 5_000,
        )
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun readsStatusFromRealCameraAnswers() {
        val status = client().status()
        assertEquals("GEAR 360", status.model)
        assertEquals("R210GLU0ARB2", status.firmware)
        assertEquals(100, status.batteryPercent)
        assertEquals(listOf("GET /osc/info", "POST /osc/state"), requests)
    }

    @Test
    fun allowedCommandsAreOnlyTheValidatedOnes() {
        assertEquals(
            setOf(
                "camera.startSession",
                "camera.closeSession",
                "camera.getOptions",
                "camera.takePicture",
                "camera.getImage",
            ),
            OscCommand.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun shootsPollsStatusClosesSessionAndDownloadsInTestedOrder() {
        val out = temp.newFile("shot.jpg")
        val name = client().shoot(out)
        assertEquals("SAM_0306.JPG", name)
        assertArrayEquals(fakeJpeg, out.readBytes())
        assertEquals(
            listOf(
                "POST /osc/state",
                "POST /osc/commands/execute camera.startSession",
                "POST /osc/commands/execute camera.takePicture",
                "POST /osc/commands/status",
                "POST /osc/commands/status",
                "POST /osc/commands/execute camera.closeSession",
                "POST /osc/commands/execute camera.getImage",
            ),
            requests,
        )
        assertTrue(bodies[2].contains("\"sessionId\":\"SID_0216\""))
        assertTrue(bodies[5].contains("\"sessionId\":\"SID_0216\""))
        assertTrue(bodies[6].contains("\"/mnt/mmc/DCIM/100PHOTO/SAM_0306.JPG\""))
    }

    @Test
    fun withoutAValidSessionNothingElseIsSent() {
        startSessionBody = """{"name":"camera.startSession","state":"done","results":{"sessionId":"SID_0000"}}"""
        val out = temp.newFile("shot.jpg")
        assertThrows(IOException::class.java) { client().shoot(out) }
        assertEquals(
            listOf(
                "POST /osc/state",
                "POST /osc/commands/execute camera.startSession",
            ),
            requests,
        )
    }

    @Test
    fun aFailedShotStillClosesTheSessionAndDoesNotDownload() {
        takePictureStatus = 500
        val out = temp.newFile("shot.jpg")
        assertThrows(IOException::class.java) { client().shoot(out) }
        assertEquals(
            listOf(
                "POST /osc/state",
                "POST /osc/commands/execute camera.startSession",
                "POST /osc/commands/execute camera.takePicture",
                "POST /osc/commands/execute camera.closeSession",
            ),
            requests,
        )
        assertFalse(requests.any { "camera.getImage" in it })
    }
}
