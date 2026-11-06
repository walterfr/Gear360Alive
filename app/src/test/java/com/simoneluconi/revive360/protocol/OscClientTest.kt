package com.simoneluconi.revive360.protocol

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress

class OscClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var client: OscClient
    private val requests = ArrayList<String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/osc/info") { ex ->
            requests += "${ex.requestMethod} /osc/info"
            val body = """{"manufacturer":"Samsung","model":"SM-R210","serialNumber":"R210ABC","firmwareVersion":"R210GLU0ARB2","endpoints":{"httpPort":80}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/osc/state") { ex ->
            requests += "${ex.requestMethod} /osc/state"
            val body = """{"fingerprint":"FIG_1","state":{"sessionId":"SID_42","batteryLevel":0.82,"_storageUri":"http://192.168.107.1/files"}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/osc/commands/execute") { ex ->
            val reqText = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += reqText
            val resp = when {
                "camera.listFiles" in reqText -> {
                    // Simulate R210 rejecting listFiles so fallback to listImages is tested
                    ex.sendResponseHeaders(400, 2)
                    ex.responseBody.use { it.write("{}".toByteArray()) }
                    return@createContext
                }
                "camera.listImages" in reqText ->
                    """{"name":"camera.listImages","state":"done","results":{"entries":[{"name":"360_0101.JPG","fileUri":"DCIM/100PHOTO/360_0101.JPG","size":4096000}]}}"""
                "camera.takePicture" in reqText ->
                    """{"name":"camera.takePicture","state":"done","results":{"fileUri":"DCIM/100PHOTO/360_0102.JPG"}}"""
                "camera.delete" in reqText ->
                    """{"name":"camera.delete","state":"done"}"""
                else -> """{"state":"done"}"""
            }.toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/files/DCIM/100PHOTO/360_0101.JPG") { ex ->
            val payload = ByteArray(1024) { (it and 0xFF).toByte() }
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
        client = OscClient("127.0.0.1", server.address.port, Connector { it.openConnection() as HttpURLConnection })
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun readsOscInfoAndState() {
        val info = client.info()
        assertEquals("SM-R210", info.model)
        assertEquals("R210GLU0ARB2", info.firmware)
        assertEquals("osc", info.adapter)

        val state = client.state()
        assertEquals("SID_42", state.sessionId)
        assertEquals(82, state.batteryPercent)
    }

    @Test
    fun fallsBackToListImagesAndTakesPicture() {
        val files = client.listFiles()
        assertEquals(1, files.size)
        assertEquals("360_0101.JPG", files.single().name)
        assertEquals(4_096_000L, files.single().size)

        val shotUri = client.takePicture()
        assertEquals("DCIM/100PHOTO/360_0102.JPG", shotUri)

        val dst = temp.newFile("downloaded.jpg")
        client.download("DCIM/100PHOTO/360_0101.JPG", dst)
        assertEquals(1024L, dst.length())

        client.delete("DCIM/100PHOTO/360_0101.JPG")
        assertTrue(requests.any { "camera.delete" in it })
    }
}
