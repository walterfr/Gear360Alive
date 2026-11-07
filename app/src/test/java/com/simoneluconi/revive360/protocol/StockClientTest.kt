package com.simoneluconi.revive360.protocol

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StockClientTest {
    private fun resource(name: String) = javaClass.getResource("/stock/$name")!!.readText()
    private val requests = mutableListOf<String>()
    private var server: HttpServer? = null

    /** A camera on port [StockClient]'s client is redirected to, answering [firstDescriptions] before 200. */
    private fun client(firstDescriptions: List<Int> = emptyList()): StockClient {
        val pending = firstDescriptions.toMutableList()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val soap = exchange.requestHeaders.getFirst("SOAPACTION")?.substringAfter('#')?.trim('"')
            requests += "${exchange.requestMethod} ${exchange.requestURI.path} ${soap.orEmpty()}".trim()
            val body = when (soap) {
                "X_GetSettingInformation" -> resource("settings.xml")
                "X_GetStorage" -> resource("storage.xml")
                else -> ""
            }.toByteArray()
            val code = if (exchange.requestURI.path == "/smp_2_" && pending.isNotEmpty()) pending.removeAt(0) else 200
            exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        http.start()
        server = http
        val port = http.address.port
        return StockClient("127.0.0.1") { url ->
            URL(url.toString().replace(":7676", ":$port")).openConnection() as HttpURLConnection
        }
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
    }
}
