package com.simoneluconi.revive360.protocol

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StockClientTest {
    private fun resource(name: String) = javaClass.getResource("/stock/$name")!!.readText()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val renewals: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var server: HttpServer? = null
    private var giveSid = true
    private var infoStatus = 200
    private var notifyAnswer: String? = null
    private val live = "TTTS".toByteArray() + ByteArray(5000) { it.toByte() }

    /** A camera on port [StockClient]'s client is redirected to, answering [firstDescriptions] before 200. */
    private fun client(firstDescriptions: List<Int> = emptyList()): StockClient {
        val pending = firstDescriptions.toMutableList()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val soap = exchange.requestHeaders.getFirst("SOAPACTION")?.substringAfter('#')?.trim('"')
            val sid = exchange.requestHeaders.getFirst("SID")
            requests += "${exchange.requestMethod} ${exchange.requestURI.path} ${soap ?: sid.orEmpty()}".trim()
            bodies += exchange.requestBody.readBytes().decodeToString()
            if (exchange.requestMethod == "SUBSCRIBE") {
                val callbackHeader = exchange.requestHeaders.getFirst("CALLBACK")
                renewals += exchange.requestHeaders.getFirst("TIMEOUT").orEmpty() +
                    (if (callbackHeader == null) " renewal" else " new") +
                    (if (exchange.requestHeaders.getFirst("NT") == null) "" else " NT")
                // A new subscription gets the first NOTIFY; a renewal (SID only) does not
                if (callbackHeader != null) {
                    val callback = callbackHeader.trim('<', '>').removePrefix("http://")
                    val (address, path) = callback.split("/", limit = 2)
                    Socket(address.substringBefore(':'), address.substringAfter(':').toInt()).use { s ->
                        s.getOutputStream().write("NOTIFY /$path HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
                        notifyAnswer = s.getInputStream().bufferedReader().readLine()
                    }
                }
                if (giveSid) exchange.responseHeaders.set("SID", "uuid:fake")
            }
            val body = when {
                soap == "X_GetSettingInformation" -> resource("settings.xml")
                soap == "X_GetStorage" -> resource("storage.xml")
                soap == "GetInfomation" -> "<QualityHighUrl>http://x:7679/livestream_high.avi</QualityHighUrl>"
                soap == "Browse" -> "<Result>&lt;DIDL-Lite&gt;&lt;item id=\"1\"&gt;&lt;dc:title&gt;SAM_0001.jpg&lt;/dc:title&gt;" +
                    "&lt;res size=\"5\"&gt;http://127.0.0.1:7679/smp01.jpg&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</Result>"
                exchange.requestURI.path == "/smp01.jpg" -> "PHOTO"
                exchange.requestURI.path == "/livestream_high.avi" -> null
                else -> ""
            }?.toByteArray() ?: live
            val code = when {
                exchange.requestURI.path == "/smp_2_" && pending.isNotEmpty() -> pending.removeAt(0)
                soap == "GetInfomation" -> infoStatus
                body === live -> 206
                else -> 200
            }
            exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        http.start()
        server = http
        val port = http.address.port
        return StockClient(
            "127.0.0.1",
            { url -> URL(url.toString().replace(Regex(":76(76|79)"), ":$port")).openConnection() as HttpURLConnection },
            eventPort = port,
            callbackPort = 0,
            settleMs = 0,
        )
    }


    @After
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun readsTheStatusFromARealCameraAnswer() {
        val status = client().status()
        assertEquals("R210GLU0ARB2_0.81", status.firmware)
        assertEquals(75, status.batteryPercent)
        assertFalse(status.charging)
        assertEquals("Photo", status.mode)
        assertTrue(status.modes.containsAll(listOf("Video", "Photo", "Time lapse", "Looping Video", "Landscape HDR")))
        assertEquals("5min", status.autoPowerOff)
        assertEquals(60935L, status.totalMb)
        assertEquals(60867L, status.freeMb)
    }

    @Test
    fun sendsOnlyTheDescriptionAndTheTwoQueries() {
        client().status()
        assertEquals(
            listOf("GET /smp_2_", "POST /smp_4_ X_GetSettingInformation", "POST /smp_4_ X_GetStorage"),
            requests,
        )
    }

    @Test
    fun aFirst503IsRetriedAndTwoStopBeforeAnyQuery() {
        client(listOf(503)).status()
        assertEquals(2, requests.count { it == "GET /smp_2_" })
        stop()
        requests.clear()
        val refusing = client(listOf(503, 503))
        assertThrows(IOException::class.java) { refusing.status() }
        assertTrue(requests.none { it.startsWith("POST") })
    }

    @Test
    fun theAllowedActionsAreOnlyQueries() {
        assertEquals(setOf("X_GetSettingInformation", "X_GetStorage"), StockQuery.entries.map { it.name }.toSet())
        assertEquals(
            setOf("SetOperationState", "GetInfomation", "Browse"),
            StockSessionAction.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun theSessionIsRenewedAndNeverEndedWithUnsubscribe() {
        val camera = client()
        val session = camera.openSession()
        assertEquals("HTTP/1.1 200 OK", notifyAnswer)
        assertEquals("uuid:fake", session.sid)
        session.renew()
        session.renew()
        // The tested renewal: the SID and a TIMEOUT, with no CALLBACK and no NT
        assertEquals(listOf("Second-120 new NT", "Second-120 renewal", "Second-120 renewal"), renewals.toList())
        assertEquals(listOf("SUBSCRIBE /smp_5_ uuid:fake", "SUBSCRIBE /smp_5_ uuid:fake"), requests.drop(2))
        session.close()
        session.close()
        // UNSUBSCRIBE turns the camera's Wi-Fi off (2026-11-14): it is never sent
        assertTrue(requests.none { it.startsWith("UNSUBSCRIBE") })
    }

    @Test
    fun opensTheLiveViewInTheTestedOrderInsideTheSession() {
        val camera = client()
        val session = camera.openSession()
        val stream = camera.openLive(session)
        assertTrue(stream.input.readBytes().contentEquals(live))
        assertEquals(
            listOf(
                "GET /smp_2_",
                "SUBSCRIBE /smp_5_",
                "POST /smp_4_ SetOperationState",
                "POST /smp_4_ GetInfomation",
                "GET /livestream_high.avi",
            ),
            requests.toList(),
        )
        assertTrue(bodies[2].contains("<StateEvent>changeToRVF</StateEvent>"))
        assertFalse(bodies[3].contains("GPSINFO"))
        stream.close()
        Thread.sleep(200)
        assertTrue(requests.none { it.startsWith("UNSUBSCRIBE") })
    }

    @Test
    fun theGallerySetsMediaListModeOnceAndDownloadsOnlyFromTheCamera() {
        val camera = client()
        val session = camera.openSession()
        val item = camera.browseMedia(session).single()
        camera.browseMedia(session)
        // changeToML only once in the session; listing again only browses
        assertEquals(
            listOf("GET /smp_2_", "SUBSCRIBE /smp_5_", "POST /smp_4_ SetOperationState", "POST /smp_4_ Browse", "POST /smp_4_ Browse"),
            requests.toList(),
        )
        assertTrue(bodies[2].contains("<StateEvent>changeToML</StateEvent>"))
        val saved = File.createTempFile("stock", ".jpg")
        try {
            camera.download(item.mediaUrl, saved)
            assertEquals("PHOTO", saved.readText())
            // An address that is not the camera's is refused before any connection
            assertThrows(IOException::class.java) { camera.download("http://example.com:7679/smp01.jpg", saved) }
        } finally {
            saved.delete()
        }
        session.close()
        assertTrue(requests.none { it.startsWith("UNSUBSCRIBE") })
    }

    @Test
    fun withoutASubscriptionNoSessionOpens() {
        giveSid = false
        assertThrows(IOException::class.java) { client().openSession() }
        assertEquals(listOf("GET /smp_2_", "SUBSCRIBE /smp_5_"), requests.toList())
    }

    @Test
    fun aFailedStepOpensNoStreamAndLeavesTheSession() {
        infoStatus = 500
        val camera = client()
        val session = camera.openSession()
        assertThrows(IOException::class.java) { camera.openLive(session) }
        assertTrue(requests.none { it.contains("livestream") || it.startsWith("UNSUBSCRIBE") })
        session.renew()
        assertEquals("SUBSCRIBE /smp_5_ uuid:fake", requests.last())
    }
}
