package com.miloai.skills.tools

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import com.miloai.skills.intProp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.EncodeHintType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.Hashtable

class GenerateQrTool : DeviceTool {

    override val name = "generate_qr"
    override val description = "Ubah teks atau tautan menjadi gambar kode QR, simpan ke folder \"Pictures/Download\" ponsel, dan kembalikan lokasi simpan (dapat dipadukan dengan fitur berbagi)."
    override val readOnly = false
    override val parameters = schemaOf(
        "content" to stringProp("Isi kode QR (teks atau URL)"),
        "size" to intProp("Sisi kode QR dalam piksel, default 512, maksimum 2048"),
        required = listOf("content")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = arguments["content"]?.jsonPrimitive?.content ?: return@runCatching "Parameter content tidak valid"
                if (content.length > 4000) return@runCatching "ERROR:Isi terlalu panjang (maksimum 4000 karakter)"
                val size = (arguments["size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 512).coerceIn(128, 2048)
                val hints = Hashtable<EncodeHintType, Any>().apply {
                    put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                    put(EncodeHintType.MARGIN, 1)
                }
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                val dir = File(context.appContext.getExternalFilesDir(null), "qr").apply { mkdirs() }
                val file = File(dir, "qr_${System.currentTimeMillis()}.png")
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } finally {
                    bitmap.recycle()
                }
                "Kode QR dibuat: ${file.absolutePath} (${size}x${size}px)"
            }.getOrElse { e -> "ERROR:Gagal membuat: ${e.message}" }
        }
}
