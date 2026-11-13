package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GetTimeTool : DeviceTool {

    override val name = "get_time"
    override val description = "Cek tanggal dan waktu saat ini (termasuk hari dan zona waktu), days_offset dapat dipakai untuk menghitung tanggal relatif (mis. tanggal dan hari berapa 3 hari lagi atau 10 hari lalu). Alat hanya-baca."
    override val readOnly = true
    override val parameters = com.miloai.skills.schemaOf(
        "days_offset" to com.miloai.skills.intProp("Selisih hari relatif terhadap hari ini (opsional, mis. 3 = 3 hari lagi, -7 = 7 hari lalu)")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String {
        val offsetDays = (arguments["days_offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            .coerceIn(-3650, 3650)
        val now = Date()
        val tz = TimeZone.getDefault()
        val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("Tanggal: ${dateFmt.format(now)}")
            appendLine("Waktu: ${timeFmt.format(now)}")
            if (offsetDays != 0) {
                val target = Date(now.time + offsetDays * 86_400_000L)
                appendLine("${if (offsetDays > 0) "$offsetDays hari lagi" else "${-offsetDays} hari lalu"}: ${dateFmt.format(target)}")
            }
            val offsetMin = tz.getOffset(now.time) / 60000
            val offsetText = buildString {
                append("UTC")
                if (offsetMin < 0) append('-') else append('+')
                val abs = kotlin.math.abs(offsetMin)
                append(abs / 60)
                if (abs % 60 != 0) append(":${abs % 60}")
            }
            appendLine("Zona waktu: ${tz.id} ($offsetText)")
            append("Unix timestamp: ${now.time / 1000}")
        }
    }
}
