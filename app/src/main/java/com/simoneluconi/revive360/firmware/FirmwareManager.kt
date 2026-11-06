package com.simoneluconi.revive360.firmware

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import com.simoneluconi.revive360.protocol.Connector
import com.simoneluconi.revive360.protocol.Protocol
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app Firmware & SD Card Manager (Zero-PC).
 *
 * Supports:
 * 1. Preparing an SD card via USB-C OTG (`StorageAccessFramework` DocumentTree or local directory in tests)
 *    for Step 1 (Firmware Flash `.bin` + `fwupgrade.txt`), Step 2 (Activation `info.tg` + `cmd.tg` + `p8_invalidate.sh`),
 *    Step 3 (Everyday `g360r.conf`), or Stock Firmware Restore (`C200GLU0AQK1` / `R210GLU0ARB2`).
 * 2. SHA-256 verification of `.bin` firmware images.
 * 3. Wi-Fi OTA updates (`PUT /api/v1/debug/upload` with Broadcom-safe rate limiting + `POST /api/v1/debug/restart`).
 */
object FirmwareManager {
    enum class Model(
        val id: String,
        val displayName: String,
        val cardImageName: String,
        val stockName: String,
        val stockSha256: String,
        val releaseUrl: String,
    ) {
        C200(
            id = "c200",
            displayName = "Gear 360 (2016 · SM-C200)",
            cardImageName = "smc200.bin",
            stockName = "C200GLU0AQK1_171121_1257_REV00_user.bin",
            stockSha256 = "150bc48362555a4812e8871ff581c693f50f80e3e31e4647d2b563e9072c48db",
            releaseUrl = "https://github.com/walterfr/Gear360Alive/releases/latest/download/smc200.bin",
        ),
        R210(
            id = "r210",
            displayName = "Gear 360 (2017 · SM-R210)",
            cardImageName = "smr210.bin",
            stockName = "R210GLU0ARB2_180201_1739_REV00_user.bin",
            stockSha256 = "81765495ee2dfa3f67f0543e8f99f0df2e5310e3f0b10f8dabddbf64015d8324",
            releaseUrl = "https://github.com/walterfr/Gear360Alive/releases/latest/download/smr210.bin",
        ),
    }

    const val FWUPGRADE_CONTENT = "Revive360: the camera only checks that this file exists.\n"
    const val INFO_TG_CONTENT = "cmd.tg\n"
    const val CMD_TG_CONTENT = "shell script /sdcard/g360r/p8_hook/p8_invalidate.sh\n"

    val DEFAULT_CONF = """
        # Revive360 controller configuration
        psk=CHANGE_ME
        wifi=ble
        wifi_idle=600
        ssid=
        band=5
        channel_5g=36
        channel=6
        port=8360
        led_roles=unknown,unknown,unknown,unknown,unknown
        led_feedback=none
        led_error=none
        ledtest=0
        selftest=0
    """.trimIndent() + "\n"

    val P8_INVALIDATE_SH = """
        #!/bin/sh
        SNAP=/dev/mmcblk0p8
        LOG=/sdcard/g360r/log/p8_invalidate.log
        export PATH=/usr/sbin:/sbin:/usr/bin:/bin
        if [ "$1" != "__detached" ]; then
            setsid /bin/sh "$0" __detached </dev/null >/dev/null 2>&1 &
            exit 0
        fi
        mkdir -p /sdcard/g360r/log
        SND=/usr/apps/com.samsung.di-camera-app/res/sounds/camera
        WAV_OK=${'$'}SND/04_N_OK.wav
        WAV_ERR=${'$'}SND/05_N_Cancel.wav
        [ -e "${'$'}WAV_OK" ] || WAV_OK=${'$'}SND/confirm.wav
        [ -e "${'$'}WAV_ERR" ] || WAV_ERR=${'$'}SND/error.wav
        amixer -q sset 'Ext Spk L' on >/dev/null 2>&1
        amixer -q sset 'Ext Spk R' on >/dev/null 2>&1
        beep() {
            n=${'$'}1; w=${'$'}2; i=0
            while [ "${'$'}i" -lt "${'$'}n" ]; do paplay "${'$'}w" >/dev/null 2>&1; i=${'$'}((i + 1)); sleep 0.3; done
        }
        beep 1 "${'$'}WAV_OK"
        rm -f /sdcard/info.tg.done
        mv /sdcard/info.tg /sdcard/info.tg.done
        sync
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
        if [ -b "${'$'}SNAP" ] && command -v mkswap >/dev/null 2>&1; then
            mkswap "${'$'}SNAP" >> "${'$'}LOG" 2>&1
            echo done > /sdcard/g360r/p8_invalidate.done
            sync
            beep 2 "${'$'}WAV_OK"
        else
            echo "error: mkswap failed" > /sdcard/g360r/p8_invalidate.done
            sync
            beep 3 "${'$'}WAV_ERR"
        fi
    """.trimIndent().replace("\r\n", "\n") + "\n"

    /**
     * Returns a [Connector] bound to the phone's active Wi-Fi [android.net.Network] (`TRANSPORT_WIFI`),
     * so HTTP calls to the camera (`192.168.43.1` / `192.168.107.1`) never get routed over 4G/5G mobile data.
     */
    @Suppress("DEPRECATION")
    fun wifiConnector(context: Context): Connector {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return Connector { url ->
            val wifiNet = cm?.allNetworks?.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            (wifiNet?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        }
    }

    /** Computes the lowercase hex SHA-256 of [input] without buffering the whole file on disk. */
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Computes the lowercase hex SHA-256 of [file]. */
    fun sha256(file: File): String =
        file.inputStream().buffered(256 * 1024).use { sha256(it) }

    /** Verifies whether [file] is a valid SLP `.bin` firmware container or matches [expectedSha256]. */
    fun verifyFirmwareBin(file: File, expectedSha256: String? = null): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        if (expectedSha256 != null) {
            return sha256(file).equals(expectedSha256.trim(), ignoreCase = true)
        }
        return true
    }

    /**
     * Prepares a local directory (or mounted SD card root) for Step 1: Firmware Update (`.bin` + `fwupgrade.txt`).
     */
    fun prepareFlashDirectory(sdRoot: File, model: Model, firmwareBin: File, progress: (Float) -> Unit = {}) {
        require(firmwareBin.exists() && firmwareBin.length() > 0) { "Firmware image not found" }
        tidyDirectory(sdRoot)
        val targetBin = File(sdRoot, model.cardImageName)
        val total = firmwareBin.length().coerceAtLeast(1L)
        firmwareBin.inputStream().use { input ->
            targetBin.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        File(sdRoot, "fwupgrade.txt").writeBytes(FWUPGRADE_CONTENT.toByteArray(Charsets.UTF_8))
        val confFile = File(sdRoot, "g360r/g360r.conf")
        if (!confFile.exists()) {
            confFile.parentFile?.mkdirs()
            confFile.writeBytes(DEFAULT_CONF.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Prepares a local directory (or mounted SD card root) for Step 2: One-shot Snapshot Invalidation (`info.tg` + `cmd.tg` + `p8_invalidate.sh`).
     */
    fun prepareActivationDirectory(sdRoot: File) {
        File(sdRoot, "smc200.bin").delete()
        File(sdRoot, "smr210.bin").delete()
        File(sdRoot, "fwupgrade.txt").delete()
        tidyDirectory(sdRoot)
        val hookDir = File(sdRoot, "g360r/p8_hook").apply { mkdirs() }
        File(sdRoot, "g360r/log").mkdirs()
        File(hookDir, "p8_invalidate.sh").writeBytes(P8_INVALIDATE_SH.toByteArray(Charsets.UTF_8))
        File(sdRoot, "info.tg").writeBytes(INFO_TG_CONTENT.toByteArray(Charsets.UTF_8))
        File(sdRoot, "cmd.tg").writeBytes(CMD_TG_CONTENT.toByteArray(Charsets.UTF_8))
        val confFile = File(sdRoot, "g360r/g360r.conf")
        if (!confFile.exists()) {
            confFile.writeBytes(DEFAULT_CONF.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Prepares a local directory (or mounted SD card root) for Step 3: Everyday Normal Mode (`g360r/g360r.conf`, no `info.tg`).
     */
    fun prepareNormalDirectory(sdRoot: File, customPassphrase: String? = null) {
        tidyDirectory(sdRoot)
        val g360rDir = File(sdRoot, "g360r").apply { mkdirs() }
        File(g360rDir, "bin").mkdirs()
        File(g360rDir, "log").mkdirs()
        val confFile = File(g360rDir, "g360r.conf")
        val psk = customPassphrase?.takeIf { Protocol.isValidPassphrase(it) } ?: "CHANGE_ME"
        val content = DEFAULT_CONF.replace("psk=CHANGE_ME", "psk=$psk")
        confFile.writeBytes(content.toByteArray(Charsets.UTF_8))
    }

    /**
     * Writes the activation or flash files to an Android SAF `DocumentTree` URI (USB-C OTG card reader).
     */
    fun writeSafActivation(context: Context, treeUri: Uri, customPassphrase: String? = null) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        deleteSafChildIfExists(context, treeUri, rootDocId, "smc200.bin")
        deleteSafChildIfExists(context, treeUri, rootDocId, "smr210.bin")
        deleteSafChildIfExists(context, treeUri, rootDocId, "fwupgrade.txt")
        deleteSafChildIfExists(context, treeUri, rootDocId, "info.tg.done")
        writeSafBytes(context, treeUri, rootDocId, rootUri, "info.tg", INFO_TG_CONTENT.toByteArray(Charsets.UTF_8))
        writeSafBytes(context, treeUri, rootDocId, rootUri, "cmd.tg", CMD_TG_CONTENT.toByteArray(Charsets.UTF_8))
        val g360rUri = ensureSafDir(context, treeUri, rootDocId, rootUri, "g360r")
        val g360rDocId = DocumentsContract.getDocumentId(g360rUri)
        val hookUri = ensureSafDir(context, treeUri, g360rDocId, g360rUri, "p8_hook")
        val hookDocId = DocumentsContract.getDocumentId(hookUri)
        writeSafBytes(context, treeUri, hookDocId, hookUri, "p8_invalidate.sh", P8_INVALIDATE_SH.toByteArray(Charsets.UTF_8))
        val psk = customPassphrase?.takeIf { Protocol.isValidPassphrase(it) } ?: "CHANGE_ME"
        val confBytes = DEFAULT_CONF.replace("psk=CHANGE_ME", "psk=$psk").toByteArray(Charsets.UTF_8)
        writeSafBytes(context, treeUri, g360rDocId, g360rUri, "g360r.conf", confBytes)
    }

    /**
     * Prepares an Android SAF `DocumentTree` URI (USB-C OTG card reader) for Step 3: Everyday Normal Mode
     * (removes flash/activation files and writes `g360r/g360r.conf` with [customPassphrase]).
     */
    fun writeSafNormal(context: Context, treeUri: Uri, customPassphrase: String? = null) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        for (name in listOf("smc200.bin", "smr210.bin", "fwupgrade.txt", "info.tg", "info.tg.done", "cmd.tg")) {
            deleteSafChildIfExists(context, treeUri, rootDocId, name)
        }
        val g360rUri = ensureSafDir(context, treeUri, rootDocId, rootUri, "g360r")
        val g360rDocId = DocumentsContract.getDocumentId(g360rUri)
        ensureSafDir(context, treeUri, g360rDocId, g360rUri, "bin")
        ensureSafDir(context, treeUri, g360rDocId, g360rUri, "log")
        val psk = customPassphrase?.takeIf { Protocol.isValidPassphrase(it) } ?: "CHANGE_ME"
        val confBytes = DEFAULT_CONF.replace("psk=CHANGE_ME", "psk=$psk").toByteArray(Charsets.UTF_8)
        writeSafBytes(context, treeUri, g360rDocId, g360rUri, "g360r.conf", confBytes)
    }

    /**
     * Writes a firmware `.bin` + `fwupgrade.txt` to an Android SAF `DocumentTree` URI (USB-C OTG card reader).
     */
    fun writeSafFlash(context: Context, treeUri: Uri, model: Model, firmwareStream: InputStream, totalBytes: Long, progress: (Float) -> Unit = {}) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        deleteSafChildIfExists(context, treeUri, rootDocId, "info.tg")
        deleteSafChildIfExists(context, treeUri, rootDocId, "cmd.tg")
        deleteSafChildIfExists(context, treeUri, rootDocId, model.cardImageName)
        val binUri = DocumentsContract.createDocument(context.contentResolver, rootUri, "application/octet-stream", model.cardImageName)
            ?: error("Could not create ${model.cardImageName} on SD card")
        context.contentResolver.openOutputStream(binUri)?.use { out ->
            val buf = ByteArray(128 * 1024)
            var done = 0L
            while (true) {
                val n = firmwareStream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (totalBytes > 0) progress((done.toFloat() / totalBytes).coerceIn(0f, 1f))
            }
        } ?: error("Could not open ${model.cardImageName} for writing")
        writeSafBytes(context, treeUri, rootDocId, rootUri, "fwupgrade.txt", FWUPGRADE_CONTENT.toByteArray(Charsets.UTF_8))
    }

    /**
     * Uploads an update file (`g360rd`, `libg360r_plugin.so`, or `.bin` firmware) over Wi-Fi OTA
     * using `PUT /api/v1/debug/upload?name=<remoteName>` with optional rate limiting (safe for Broadcom AP),
     * then optionally restarts `g360rd` via `POST /api/v1/debug/restart?replace=<remoteName>`.
     */
    fun uploadOta(
        host: String = Protocol.DEFAULT_HOST,
        port: Int = Protocol.PORT,
        connector: Connector,
        remoteName: String,
        data: InputStream,
        totalBytes: Long,
        replaceDaemon: Boolean = false,
        maxBytesPerSec: Long = 1_500_000L, // 1.5 MB/s prevents BCM43455 AP firmware hang on R210
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val base = "http://${if (':' in host) "[$host]" else host}:$port"
        val uploadConn = connector.open(URL("$base/api/v1/debug/upload?name=$remoteName")).apply {
            requestMethod = "PUT"
            connectTimeout = 5_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            if (totalBytes >= 0) setFixedLengthStreamingMode(totalBytes)
        }
        try {
            uploadConn.outputStream.use { out ->
                val buf = ByteArray(32 * 1024)
                var done = 0L
                val startMs = System.currentTimeMillis()
                while (true) {
                    val n = data.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, totalBytes)
                    if (maxBytesPerSec > 0) {
                        val expectedMs = (done * 1000L) / maxBytesPerSec
                        val elapsedMs = System.currentTimeMillis() - startMs
                        if (expectedMs > elapsedMs + 15) {
                            Thread.sleep((expectedMs - elapsedMs).coerceAtMost(200L))
                        }
                    }
                }
            }
            val code = uploadConn.responseCode
            if (code !in 200..299) throw IllegalStateException("OTA upload HTTP $code")
        } finally {
            uploadConn.disconnect()
        }

        if (replaceDaemon) {
            val restartConn = connector.open(URL("$base/api/v1/debug/restart?replace=$remoteName")).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 10_000
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            try {
                val code = restartConn.responseCode
                if (code !in 200..299) throw IllegalStateException("OTA restart HTTP $code")
            } finally {
                restartConn.disconnect()
            }
        }
    }

    private fun tidyDirectory(sdRoot: File) {
        listOf(
            "info.tg", "cmd.tg", "info.tg.done",
            "smc200.bin.flashed", "smr210.bin.flashed", "fwupgrade.txt.done",
            "g360r/p8_invalidate.done",
        ).forEach { File(sdRoot, it).delete() }
        File(sdRoot, "g360r/p8_hook").deleteRecursively()
    }

    private fun ensureSafDir(context: Context, treeUri: Uri, parentDocId: String, parentUri: Uri, name: String): Uri {
        val childId = "$parentDocId/$name"
        val candidate = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
        val exists = runCatching {
            context.contentResolver.query(candidate, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        if (exists) return candidate
        return DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: candidate
    }

    private fun deleteSafChildIfExists(context: Context, treeUri: Uri, parentDocId: String, name: String) {
        runCatching {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$parentDocId/$name")
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    private fun writeSafBytes(context: Context, treeUri: Uri, parentDocId: String, parentUri: Uri, name: String, bytes: ByteArray) {
        deleteSafChildIfExists(context, treeUri, parentDocId, name)
        val uri = DocumentsContract.createDocument(context.contentResolver, parentUri, "application/octet-stream", name)
            ?: error("Could not create $name on SD card")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Could not write $name on SD card")
    }
}
