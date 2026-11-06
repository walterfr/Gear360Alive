package com.simoneluconi.revive360.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.firmware.FirmwareManager
import com.simoneluconi.revive360.link.LinkState
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.protocol.OscClient
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection

/**
 * In-App Firmware, SD Card Preparation (USB-C OTG / SAF), Wi-Fi OTA Updater & OSC Street View Mode.
 */
@Composable
fun FirmwareScreen() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val state by nav.app.controller.state.collectAsStateWithLifecycle()
    val pairedCamera by nav.app.store.camera.collectAsStateWithLifecycle()
    val connected = state is LinkState.Connected

    var selectedModel by rememberSaveable { mutableStateOf(FirmwareManager.Model.C200) }
    var sdTreeUri by remember { mutableStateOf<Uri?>(null) }
    var binFileUri by remember { mutableStateOf<Uri?>(null) }
    var binSha256 by remember { mutableStateOf<String?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var oscStatus by remember { mutableStateOf<String?>(null) }

    fun queryUriSize(uri: Uri, fallback: Long = 250_000_000L): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: fallback

    fun connectOscWithFallback(): OscClient {
        val wifiConn = FirmwareManager.wifiConnector(context)
        val primary = OscClient(OscClient.DEFAULT_HOST, OscClient.DEFAULT_PORT, wifiConn)
        return if (runCatching { primary.info() }.isSuccess) primary
        else OscClient(OscClient.FALLBACK_HOST, OscClient.DEFAULT_PORT, wifiConn)
    }

    val pickSdTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            sdTreeUri = uri
            nav.message("SD Card selected", MessageKind.GOOD)
        }
    }

    val pickBinFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            binFileUri = uri
            scope.launch {
                busyText = "Verifying SHA-256…"
                val hash = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.buffered(256 * 1024)?.use { input ->
                        FirmwareManager.sha256(input)
                    }
                }
                binSha256 = hash
                busyText = null
            }
        }
    }

    val pickOtaFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busyText = "Uploading OTA update over Wi-Fi…"
                progress = 0f
                try {
                    withContext(Dispatchers.IO) {
                        val size = queryUriSize(uri, -1L)
                        context.contentResolver.openInputStream(uri)?.buffered(128 * 1024)?.use { input ->
                            FirmwareManager.uploadOta(
                                connector = FirmwareManager.wifiConnector(context),
                                remoteName = "g360rd.new",
                                data = input,
                                totalBytes = size,
                                replaceDaemon = true,
                            ) { done, total ->
                                if (total > 0) progress = done.toFloat() / total
                            }
                        } ?: error("Cannot read selected OTA file")
                    }
                    EventLog.log("OTA: uploaded and restarted g360rd")
                    nav.message("OTA update applied!", MessageKind.GOOD)
                } catch (e: Exception) {
                    nav.message("OTA failed: ${e.message}", MessageKind.BAD)
                } finally {
                    busyText = null
                }
            }
        }
    }

    ScreenScaffold(title = "Firmware & SD Manager") {
        SectionTitle("1. Camera Model")
        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FirmwareManager.Model.entries.forEach { m ->
                    Chip(m.id.uppercase(), selected = selectedModel == m) { selectedModel = m }
                }
            }
            Spacer(Modifier.height(10.dp))
            Label(selectedModel.displayName, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Label("Target SD file: ${selectedModel.cardImageName}", size = 13.sp, color = colors.text2, mono = true)
            Label("Stock SHA-256: ${selectedModel.stockSha256.take(16)}…", size = 12.sp, color = colors.text2, mono = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Download ${selectedModel.cardImageName}", icon = AppIcons.Download) {
                    uriHandler.openUri(selectedModel.releaseUrl)
                }
                PillButton("Pick .bin File", icon = AppIcons.File) {
                    pickBinFile.launch("*/*")
                }
            }
            if (binSha256 != null) {
                val isStock = binSha256.equals(selectedModel.stockSha256, ignoreCase = true)
                Spacer(Modifier.height(8.dp))
                Label(
                    "SHA-256: ${binSha256!!.take(24)}… " + if (isStock) "(Verified Stock Image)" else "(Custom / Mod Image)",
                    size = 12.sp,
                    color = if (isStock) colors.good else colors.accent,
                    mono = true,
                )
            }
        }

        SectionTitle("2. Prepare microSD via USB-C OTG (Zero-PC)")
        Card {
            Label(
                "Insert your camera's microSD card using a USB-C reader and select its root folder.",
                size = 14.sp,
                color = colors.text2,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                if (sdTreeUri == null) "Select SD Card Root (OTG)" else "Change SD Card Root",
                Modifier.fillMaxWidth(),
                icon = AppIcons.SdCard,
                style = if (sdTreeUri == null) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
            ) { pickSdTree.launch(null) }

            if (sdTreeUri != null) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        "Step 1: Write Firmware (${selectedModel.cardImageName} + fwupgrade.txt)",
                        Modifier.fillMaxWidth(),
                        style = ButtonStyle.PRIMARY,
                        enabled = binFileUri != null && busyText == null,
                    ) {
                        val tree = sdTreeUri ?: return@PillButton
                        val bin = binFileUri ?: return@PillButton
                        scope.launch {
                            busyText = "Writing ${selectedModel.cardImageName} to SD card…"
                            progress = 0f
                            try {
                                withContext(Dispatchers.IO) {
                                    val totalBytes = queryUriSize(bin, 250_000_000L)
                                    context.contentResolver.openInputStream(bin)?.use { stream ->
                                        FirmwareManager.writeSafFlash(context, tree, selectedModel, stream, totalBytes) {
                                            progress = it
                                        }
                                    } ?: error("Cannot read selected .bin")
                                }
                                nav.message("Step 1 ready on SD! Hold Shutter + Power on camera.", MessageKind.GOOD)
                            } catch (e: Exception) {
                                nav.message("SD write failed: ${e.message}", MessageKind.BAD)
                            } finally {
                                busyText = null
                            }
                        }
                    }

                    PillButton(
                        "Step 2: Write Activation Hook (info.tg + p8_invalidate.sh)",
                        Modifier.fillMaxWidth(),
                        enabled = busyText == null,
                    ) {
                        val tree = sdTreeUri ?: return@PillButton
                        val psk = pairedCamera?.passphrase
                        scope.launch {
                            busyText = "Writing activation hook to SD card…"
                            try {
                                withContext(Dispatchers.IO) {
                                    FirmwareManager.writeSafActivation(context, tree, psk)
                                }
                                nav.message("Step 2 ready on SD! Power on camera and wait for 2 beeps.", MessageKind.GOOD)
                            } catch (e: Exception) {
                                nav.message("Activation write failed: ${e.message}", MessageKind.BAD)
                            } finally {
                                busyText = null
                            }
                        }
                    }

                    PillButton(
                        "Step 3: Clean SD for Everyday Use (g360r.conf)",
                        Modifier.fillMaxWidth(),
                        enabled = busyText == null,
                    ) {
                        val tree = sdTreeUri ?: return@PillButton
                        val psk = pairedCamera?.passphrase
                        scope.launch {
                            busyText = "Preparing SD card for everyday use…"
                            try {
                                withContext(Dispatchers.IO) {
                                    FirmwareManager.writeSafNormal(context, tree, psk)
                                }
                                nav.message("SD card ready for normal everyday use!", MessageKind.GOOD)
                            } catch (e: Exception) {
                                nav.message("Clean SD failed: ${e.message}", MessageKind.BAD)
                            } finally {
                                busyText = null
                            }
                        }
                    }
                }
            }

            if (busyText != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Spacer(Modifier.width(10.dp))
                    Label(busyText!!, size = 14.sp)
                }
                if (progress > 0f) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.line)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(colors.accent))
                    }
                }
            }
        }

        SectionTitle("3. Wi-Fi OTA Update (No SD Removal)")
        Card {
            Label(
                "Update the camera controller (g360rd) directly over Wi-Fi with rate-limiting safe for the Broadcom radio.",
                size = 14.sp,
                color = colors.text2,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                "Upload & Restart Daemon (OTA)",
                Modifier.fillMaxWidth(),
                icon = AppIcons.Wifi,
                enabled = connected && busyText == null,
            ) {
                pickOtaFile.launch("*/*")
            }
        }

        SectionTitle("4. Zero-Mod Fallback (OSC Street View Mode)")
        Card {
            Label(
                "Using a 100% stock Gear 360 (2017)? Switch camera to 'Connect to Street View', join its '.OSC' Wi-Fi, and test or shoot directly below.",
                size = 14.sp,
                color = colors.text2,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Check OSC Camera", Modifier.weight(1f), icon = AppIcons.Camera360R210, enabled = busyText == null) {
                    scope.launch {
                        busyText = "Connecting to OSC (Street View Wi-Fi)…"
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val osc = connectOscWithFallback()
                                val info = osc.info()
                                val st = osc.state()
                                val files = osc.listFiles(20)
                                "${info.model} (${info.firmware}) · Battery ${st.batteryPercent}% · ${files.size} files"
                            }
                            oscStatus = result
                            nav.message("OSC connected!", MessageKind.GOOD)
                        } catch (e: Exception) {
                            oscStatus = "OSC not reachable: ${e.message}"
                            nav.message("OSC connection failed", MessageKind.BAD)
                        } finally {
                            busyText = null
                        }
                    }
                }
                PillButton("OSC Shoot", icon = AppIcons.Shutter, style = ButtonStyle.PRIMARY, enabled = busyText == null) {
                    scope.launch {
                        busyText = "Taking OSC photo…"
                        try {
                            val uri = withContext(Dispatchers.IO) { connectOscWithFallback().takePicture() }
                            oscStatus = "Captured: $uri"
                            nav.message("Photo taken ($uri)", MessageKind.GOOD)
                        } catch (e: Exception) {
                            nav.message("OSC shoot failed: ${e.message}", MessageKind.BAD)
                        } finally {
                            busyText = null
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PillButton("OSC Import Latest Photo to Phone Gallery", Modifier.fillMaxWidth(), icon = AppIcons.Download, enabled = busyText == null) {
                scope.launch {
                    busyText = "Downloading latest OSC photo…"
                    progress = 0f
                    try {
                        val savedName = withContext(Dispatchers.IO) {
                            val osc = connectOscWithFallback()
                            val latest = osc.listFiles(5).firstOrNull() ?: error("No files on OSC camera")
                            val tmp = File(context.cacheDir, "osc_${latest.name}")
                            try {
                                osc.download(latest.path, tmp) { done, total ->
                                    if (total > 0) progress = done.toFloat() / total
                                }
                                MediaSaver.publish(context, latest.name, FileKind.PHOTO, "image/jpeg", latest.mtime, tmp)
                                latest.name
                            } finally {
                                tmp.delete()
                            }
                        }
                        oscStatus = "Imported $savedName to ${MediaSaver.FOLDER}"
                        nav.message("Saved $savedName to Gallery!", MessageKind.GOOD)
                    } catch (e: Exception) {
                        nav.message("OSC import failed: ${e.message}", MessageKind.BAD)
                    } finally {
                        busyText = null
                    }
                }
            }
            if (oscStatus != null) {
                Spacer(Modifier.height(10.dp))
                Label(oscStatus!!, size = 13.sp, color = colors.accent, mono = true)
            }
        }
    }
}
