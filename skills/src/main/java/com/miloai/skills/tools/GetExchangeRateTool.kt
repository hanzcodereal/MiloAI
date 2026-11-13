package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.numberProp
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class GetExchangeRateTool : DeviceTool {

    override val name = "get_exchange_rate"
    override val description = "Cek kurs mata uang real-time (sumber data gratis open.er-api.com). from adalah kode mata uang asal (mis. IDR), to adalah kode mata uang tujuan (mis. USD), amount adalah jumlah (opsional, default 1), mengembalikan hasil konversi."
    override val readOnly = true
    override val parameters = schemaOf(
        "from" to stringProp("Kode mata uang asal, mis. IDR / USD / EUR / JPY / SGD"),
        "to" to stringProp("Kode mata uang tujuan"),
        "amount" to numberProp("Jumlah, default 1"),
        required = listOf("from", "to")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val from = arguments["from"]?.jsonPrimitive?.content?.uppercase() ?: return@runCatching "Parameter from tidak valid"
                val to = arguments["to"]?.jsonPrimitive?.content?.uppercase() ?: return@runCatching "Parameter to tidak valid"
                val amount = arguments["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
                val conn = URL("https://open.er-api.com/v6/latest/$from").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                try {
                    if (conn.responseCode != 200) {
                        return@runCatching "ERROR:Layanan kurs mengembalikan ${conn.responseCode}"
                    }
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = Json.parseToJsonElement(json).jsonObject
                    val rates = root["rates"]?.jsonObject ?: return@runCatching "ERROR:Gagal memproses data kurs"
                    val rate = rates[to]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?: return@runCatching "ERROR:Kode mata uang tidak didukung: $to"
                    val result = amount * rate
                    val fmt = { v: Double ->
                        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e12) v.toLong().toString()
                        else "%.4f".format(v).trimEnd('0').trimEnd('.')
                    }
                    val timeText = root["time_last_update_utc"]?.jsonPrimitive?.content ?: ""
                    buildString {
                        appendLine("1 $from = ${fmt(rate)} $to")
                        appendLine("$amount $from = ${fmt(result)} $to")
                        if (timeText.isNotBlank()) append("Waktu pembaruan: $timeText")
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { e -> "ERROR:Gagal mengambil kurs: ${e.message}" }
        }
}
