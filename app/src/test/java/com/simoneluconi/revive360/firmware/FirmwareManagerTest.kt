package com.simoneluconi.revive360.firmware

import com.simoneluconi.revive360.protocol.Connector
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress

class FirmwareManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun preparesFlashActivationAndNormalDirectories() {
        val sdRoot = temp.newFolder("sdcard")
        val fakeBin = temp.newFile("fake.bin").apply { writeBytes(ByteArray(4096) { 0x5A }) }
        val hash = FirmwareManager.sha256(fakeBin)
        assertTrue(FirmwareManager.verifyFirmwareBin(fakeBin, hash))
        assertFalse(FirmwareManager.verifyFirmwareBin(fakeBin, "0000"))

        // Step 1: Flash preparation
        FirmwareManager.prepareFlashDirectory(sdRoot, FirmwareManager.Model.C200, fakeBin)
        assertTrue(File(sdRoot, "smc200.bin").exists())
        assertTrue(File(sdRoot, "fwupgrade.txt").exists())
        assertTrue(File(sdRoot, "g360r/g360r.conf").exists())
        assertFalse(File(sdRoot, "info.tg").exists())

        // Step 2: Activation preparation (removes smc200.bin + fwupgrade.txt, writes info.tg + cmd.tg + p8_invalidate.sh)
        FirmwareManager.prepareActivationDirectory(sdRoot)
        assertFalse(File(sdRoot, "smc200.bin").exists())
        assertFalse(File(sdRoot, "fwupgrade.txt").exists())
        assertTrue(File(sdRoot, "info.tg").exists())
        assertTrue(File(sdRoot, "cmd.tg").exists())
        assertTrue(File(sdRoot, "g360r/p8_hook/p8_invalidate.sh").exists())

        // Step 3: Everyday normal mode (removes info.tg + cmd.tg + p8_hook, keeps g360r.conf)
        FirmwareManager.prepareNormalDirectory(sdRoot, customPassphrase = "MySecret123")
        assertFalse(File(sdRoot, "info.tg").exists())
        assertFalse(File(sdRoot, "cmd.tg").exists())
        assertFalse(File(sdRoot, "g360r/p8_hook").exists())
        val conf = File(sdRoot, "g360r/g360r.conf").readText()
        assertTrue("psk=MySecret123" in conf)
    }

    @Test
    fun uploadsOtaPayloadAndTriggersRestart() {
        var uploadedBytes = ByteArray(0)
        var uploadQuery = ""
        var restartQuery = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/debug/upload") { ex ->
            uploadQuery = ex.requestURI.query ?: ""
            uploadedBytes = ex.requestBody.readBytes()
            val ok = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.createContext("/api/v1/debug/restart") { ex ->
            restartQuery = ex.requestURI.query ?: ""
            val ok = """{"status":"restarting"}""".toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.start()
        try {
            val payload = ByteArray(16_384) { (it * 7).toByte() }
            FirmwareManager.uploadOta(
                host = "127.0.0.1",
                port = server.address.port,
                connector = Connector { it.openConnection() as HttpURLConnection },
                remoteName = "g360rd.new",
                data = ByteArrayInputStream(payload),
                totalBytes = payload.size.toLong(),
                replaceDaemon = true,
                maxBytesPerSec = 0L,
            )
            assertEquals("name=g360rd.new", uploadQuery)
            assertEquals("replace=g360rd.new", restartQuery)
            assertArrayEquals(payload, uploadedBytes)
        } finally {
            server.stop(0)
        }
    }
}
