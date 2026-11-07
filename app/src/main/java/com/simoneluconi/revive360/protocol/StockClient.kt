package com.simoneluconi.revive360.protocol

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only SOAP actions this client can send to a camera on its original firmware: each one was read
 * from the camera's own action list and tested on an SM-R210 (docs/PLANO.md, section 13). Anything
 * else has to be added here on purpose, after its own test.
 */
enum class StockQuery { X_GetSettingInformation, X_GetStorage }

data class StockStatus(
    val firmware: String?,
    val batteryPercent: Int?,
    val charging: Boolean,
    val mode: String?,
    val modes: List<String>,
    val autoPowerOff: String?,
    val totalMb: Long?,
    val freeMb: Long?,
)

/** A camera on its original firmware, over UPnP on its own Wi-Fi. Blocking: call on an IO dispatcher. */
class StockClient(host: String, private val connector: Connector) {
    private val base = "http://$host:$PORT"

    fun status(): StockStatus {
        wake()
        return parseStatus(query(StockQuery.X_GetSettingInformation), query(StockQuery.X_GetStorage))
    }

    /** The first request after a pause has answered 503 on the SM-R210, and the description then answers. */
    private fun wake() {
        repeat(2) { attempt ->
            val connection = open("$base$DESCRIPTION_PATH")
            try {
                connection.setRequestProperty("User-Agent", STOCK_USER_AGENT)
                connection.setRequestProperty("Access-Method", "manual")
                if (connection.responseCode == 200) return
            } finally {
                connection.disconnect()
            }
            if (attempt == 0) Thread.sleep(RETRY_DELAY_MS)
        }
        throw IOException("the camera does not answer")
    }

    private fun query(action: StockQuery): String {
        val body = envelope(action).toByteArray()
        val connection = open("$base$CONTROL_PATH")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$SERVICE#${action.name}\"")
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) throw IOException("${action.name}: HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = connector.open(URL(url)).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("Connection", "close")
    }

    companion object {
        const val SSID_PREFIX = "Gear 360("
        private const val PORT = 7676
        private const val DESCRIPTION_PATH = "/smp_2_"
        private const val CONTROL_PATH = "/smp_4_"
        private const val SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"
        private const val STOCK_USER_AGENT = "SEC_RVF_ML_02:00:00:00:00:00"
        private const val TIMEOUT_MS = 8_000
        private const val RETRY_DELAY_MS = 1_000L

        fun envelope(action: StockQuery): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:${action.name} xmlns:u=\"$SERVICE\"></u:${action.name}>" +
                "</s:Body></s:Envelope>"

        private fun tag(xml: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

        fun parseStatus(settings: String, storage: String): StockStatus {
            val dial = tag(settings, "DialModeValue").orEmpty()
            return StockStatus(
                firmware = tag(settings, "FWVersion"),
                batteryPercent = tag(settings, "BatteryLevel")?.toIntOrNull(),
                charging = tag(settings, "ChargingStatus").equals("On", ignoreCase = true),
                mode = tag(dial, "Default"),
                modes = Regex("<S>([^<]*)</S>").findAll(dial).map { it.groupValues[1] }.toList(),
                autoPowerOff = tag(tag(settings, "AutoPowerOffValue").orEmpty(), "Default"),
                totalMb = tag(storage, "TotalSpaceValue")?.toLongOrNull(),
                freeMb = tag(storage, "AvaiableSpaceValue")?.toLongOrNull(),
            )
        }
    }
}
