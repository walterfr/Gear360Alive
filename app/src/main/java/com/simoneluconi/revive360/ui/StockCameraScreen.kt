package com.simoneluconi.revive360.ui

import android.net.Network
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.CameraNetwork
import com.simoneluconi.revive360.protocol.Connector
import com.simoneluconi.revive360.protocol.Protocol
import com.simoneluconi.revive360.protocol.StockClient
import com.simoneluconi.revive360.protocol.StockStatus
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class StockLink { IDLE, JOINING, CONNECTED, LOST, NOT_FOUND }

private const val POLL_MS = 20_000L

/**
 * A camera on its original firmware, read only: it joins the network the camera's menu started and
 * shows what the camera reports. Sends only [com.simoneluconi.revive360.protocol.StockQuery].
 */
// ponytail: the network is released when the screen closes, not when the app goes to the background
@Composable
fun StockCameraScreen() {
    val nav = LocalActions.current
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val settings = nav.app.settings
    var code by rememberSaveable { mutableStateOf(settings.stockPassphrase.orEmpty()) }
    var link by remember { mutableStateOf(StockLink.IDLE) }
    var client by remember { mutableStateOf<StockClient?>(null) }
    var status by remember { mutableStateOf<StockStatus?>(null) }
    var unanswered by remember { mutableStateOf(false) }
    val network = remember { CameraNetwork(context) }
    DisposableEffect(Unit) { onDispose { network.release() } }

    LaunchedEffect(client) {
        val camera = client ?: return@LaunchedEffect
        while (true) {
            try {
                status = withContext(Dispatchers.IO) { camera.status() }
                unanswered = false
            } catch (e: IOException) {
                EventLog.log("Stock camera: no answer (${e.message})")
                unanswered = true
            }
            delay(POLL_MS)
        }
    }

    fun connect() {
        settings.stockPassphrase = code
        status = null
        client = null
        unanswered = false
        link = StockLink.JOINING
        network.requestStock(code, object : CameraNetwork.Listener {
            override fun onAvailable(network: Network, host: String) {
                client = StockClient(host, Connector { url -> network.openConnection(url) as HttpURLConnection })
                link = StockLink.CONNECTED
            }

            override fun onLost() {
                client = null
                link = StockLink.LOST
            }

            override fun onUnavailable() {
                client = null
                link = StockLink.NOT_FOUND
            }
        })
    }

    ScreenScaffold(title = stringResource(R.string.stock_title)) {
        if (link != StockLink.CONNECTED) {
            Card {
                Label(stringResource(R.string.stock_steps), color = colors.text2)
                Spacer(Modifier.height(14.dp))
                TextInput(
                    value = code,
                    onChange = { code = it.filter { c -> c.code in 0x20..0x7E }.take(Protocol.PASSPHRASE_MAX) },
                    placeholder = stringResource(R.string.stock_code),
                    mono = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(14.dp))
                PillButton(
                    stringResource(if (link == StockLink.JOINING) R.string.stock_joining else R.string.stock_connect),
                    Modifier.fillMaxWidth(),
                    icon = AppIcons.Wifi,
                    style = ButtonStyle.PRIMARY,
                    enabled = Protocol.isValidPassphrase(code) && link != StockLink.JOINING,
                ) { connect() }
                val problem = when (link) {
                    StockLink.LOST -> stringResource(R.string.stock_lost)
                    StockLink.NOT_FOUND -> stringResource(R.string.stock_not_found)
                    else -> null
                }
                if (problem != null) {
                    Spacer(Modifier.height(12.dp))
                    Label(problem, size = 14.sp, color = colors.bad)
                }
            }
        } else {
            Card {
                val current = status
                if (current == null) {
                    Label(stringResource(if (unanswered) R.string.stock_no_answer else R.string.stock_reading), color = colors.text2)
                } else {
                    StatusLine(stringResource(R.string.stock_battery), batteryText(current))
                    StatusLine(stringResource(R.string.stock_card), cardText(current))
                    StatusLine(stringResource(R.string.stock_mode), current.mode ?: "?")
                    StatusLine(stringResource(R.string.stock_auto_off), current.autoPowerOff ?: "?")
                    StatusLine(stringResource(R.string.stock_firmware), current.firmware ?: "?")
                    if (unanswered) {
                        Spacer(Modifier.height(8.dp))
                        Label(stringResource(R.string.stock_no_answer), size = 14.sp, color = colors.bad)
                    }
                }
            }
            Card { Label(stringResource(R.string.stock_read_only), size = 14.sp, color = colors.text2) }
        }
    }
}

@Composable
private fun StatusLine(title: String, value: String) {
    val colors = LocalAppColors.current
    Label(title, size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
    Label(value)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun batteryText(status: StockStatus): String {
    val percent = status.batteryPercent?.let { "$it%" } ?: "?"
    return if (status.charging) stringResource(R.string.stock_charging, percent) else percent
}

@Composable
private fun cardText(status: StockStatus): String {
    val free = status.freeMb ?: return "?"
    val total = status.totalMb ?: return "?"
    return stringResource(R.string.stock_card_free, free / 1024f, total / 1024f)
}
