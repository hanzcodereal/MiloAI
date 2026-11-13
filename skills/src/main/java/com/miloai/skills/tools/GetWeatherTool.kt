package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GetWeatherTool : DeviceTool {

    override val name = "get_weather"
    override val description = "Cek cuaca: nama kota (mis. Jakarta, Surabaya), mengembalikan cuaca saat ini, suhu, kelembapan, kecepatan angin, dan prakiraan 3 hari ke depan. Sumber data gratis, tanpa API Key."
    override val readOnly = true
    override val parameters = schemaOf(
        "city" to stringProp("Nama kota, jika kosong default Jakarta"),
        required = emptyList()
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val city = arguments["city"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Jakarta"
                val encoded = URLEncoder.encode(city, "UTF-8")
                val conn = URL("https://wttr.in/$encoded?format=j1").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "curl/8.0")
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@runCatching "ERROR:Layanan cuaca mengembalikan ${conn.responseCode}"
                }
                val json = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                val cur = root["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@runCatching "ERROR:Gagal memproses data cuaca"
                val temp = cur["temp_C"]?.jsonPrimitive?.content ?: "?"
                val feels = cur["FeelsLikeC"]?.jsonPrimitive?.content ?: "?"
                val humidity = cur["humidity"]?.jsonPrimitive?.content ?: "?"
                val desc = cur["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("value")?.jsonPrimitive?.content ?: "?"
                val wind = cur["windspeedKmph"]?.jsonPrimitive?.content ?: "?"
                val pressure = cur["pressure"]?.jsonPrimitive?.content ?: "?"
                val sb = StringBuilder()
                sb.appendLine("Kota: $city")
                sb.appendLine("Saat ini: $desc, ${temp}°C (terasa ${feels}°C)")
                val visibility = cur["visibility"]?.jsonPrimitive?.content ?: "?"
                val uv = cur["uvIndex"]?.jsonPrimitive?.content ?: "?"
                val precip = cur["precipMM"]?.jsonPrimitive?.content ?: "?"
                sb.appendLine("Kelembapan: $humidity% | Angin: ${wind}km/jam | Tekanan: ${pressure}hPa")
                sb.appendLine("Jarak pandang: ${visibility}km | Indeks UV: $uv | Curah hujan: ${precip}mm")
                val forecast = root["weather"]?.jsonArray
                if (forecast != null) {
                    val days = listOf("Hari ini", "Besok", "Lusa")
                    forecast.take(3).forEachIndexed { i, day ->
                        val d = day.jsonObject
                        val date = d["date"]?.jsonPrimitive?.content ?: ""
                        val max = d["maxtempC"]?.jsonPrimitive?.content ?: "?"
                        val min = d["mintempC"]?.jsonPrimitive?.content ?: "?"
                        val dayDesc = d["hourly"]?.jsonArray
                            ?.take(6)?.mapNotNull { h ->
                                h.jsonObject["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
                                    ?.get("value")?.jsonPrimitive?.content
                            }?.distinct()?.joinToString("/") ?: "?"
                        sb.appendLine("${days.getOrElse(i) { date }}: ${min}~${max}°C, $dayDesc")
                    }
                }
                sb.toString().trim()
            }.getOrElse { e -> "ERROR:Gagal mengambil data: ${e.message}" }
        }
}
