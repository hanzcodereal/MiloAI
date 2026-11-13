package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.numberProp
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class UnitConvertTool : DeviceTool {

    override val name = "unit_convert"
    override val description = "Konversi satuan: panjang (m/km/cm/mm/mile/ft/inch), berat (kg/g/lb/oz), suhu (c/f/k), luas (m2/km2/ha), kecepatan (m/s/km/h/mph), data (b/kb/mb/gb/tb), waktu (s/min/h/day). Contoh: 100 km/h ke m/s."
    override val readOnly = true
    override val parameters = schemaOf(
        "value" to numberProp("Nilai yang dikonversi"),
        "from" to stringProp("Satuan asal, mis. km/h, c, kg, mile"),
        "to" to stringProp("Satuan tujuan, mis. m/s, f, lb, km")
    )

    private val groups: Map<String, Map<String, Double>> = mapOf(
        "length" to mapOf(
            "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "inch" to 0.0254, "ft" to 0.3048, "mile" to 1609.344, "nmi" to 1852.0
        ),
        "weight" to mapOf(
            "mg" to 0.000001, "g" to 0.001, "kg" to 1.0, "t" to 1000.0,
            "oz" to 0.028349523, "lb" to 0.45359237
        ),
        "area" to mapOf(
            "m2" to 1.0, "km2" to 1_000_000.0, "ha" to 10_000.0,
            "ft2" to 0.092903, "acre" to 4046.86
        ),
        "speed" to mapOf(
            "m/s" to 1.0, "km/h" to 1000.0 / 3600, "mph" to 0.44704,
            "knot" to 1852.0 / 3600, "ft/s" to 0.3048
        ),
        "data" to mapOf(
            "b" to 1.0, "kb" to 1024.0, "mb" to 1024.0 * 1024, "gb" to 1024.0 * 1024 * 1024,
            "tb" to 1024.0 * 1024 * 1024 * 1024
        ),
        "time" to mapOf(
            "ms" to 0.001, "s" to 1.0, "min" to 60.0, "h" to 3600.0, "day" to 86400.0
        ),
        "temperature" to mapOf("c" to 1.0, "f" to 1.0, "k" to 1.0)
    )

    private val aliases = mapOf(
        "meter" to "m", "kilometer" to "km", "sentimeter" to "cm", "milimeter" to "mm",
        "mil" to "mile", "kaki" to "ft", "inci" to "inch",
        "gram" to "g", "kilogram" to "kg", "ton" to "t", "pon" to "lb", "ons" to "oz",
        "hektare" to "ha",
        "celsius" to "c", "fahrenheit" to "f", "kelvin" to "k",
        "detik" to "s", "menit" to "min", "jam" to "h", "hari" to "day",
        "byte" to "b", "kilobyte" to "kb", "megabyte" to "mb", "gigabyte" to "gb"
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String {
        val value = arguments["value"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: return "Parameter value tidak valid: berikan angka"
        val fromRaw = arguments["from"]?.jsonPrimitive?.content?.trim().orEmpty()
        val toRaw = arguments["to"]?.jsonPrimitive?.content?.trim().orEmpty()
        val from = normalize(fromRaw)
        val to = normalize(toRaw)
        if (from.isBlank() || to.isBlank()) return "from/to tidak boleh kosong"
        val group = groups.entries.firstOrNull { from in it.value.keys && to in it.value.keys }
            ?: return "Kombinasi satuan tidak didukung: $from → $to (gunakan satuan sesuai deskripsi, perhatikan huruf besar/kecil, mis. km/h, m/s, c, f, kg, mile)"
        val result = when (group.key) {
            "temperature" -> convertTemperature(value, from, to)
            else -> value * (group.value[from]!! / group.value[to]!!)
        }
        val formatted = if (result == result.toLong().toDouble() && kotlin.math.abs(result) < 1e15) {
            result.toLong().toString()
        } else {
            "%.6f".format(result).trimEnd('0').trimEnd('.')
        }
        val valueText = if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        return "$valueText $fromRaw = $formatted $toRaw"
    }

    private fun normalize(unit: String): String = aliases[unit] ?: unit.lowercase()

    private fun convertTemperature(value: Double, from: String, to: String): Double {
        val celsius = when (from) {
            "c" -> value
            "f" -> (value - 32) * 5 / 9
            "k" -> value - 273.15
            else -> value
        }
        return when (to) {
            "c" -> celsius
            "f" -> celsius * 9 / 5 + 32
            "k" -> celsius + 273.15
            else -> celsius
        }
    }
}
