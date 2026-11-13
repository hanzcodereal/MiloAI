package com.miloai.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.miloai.core.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object AttachmentProcessor {

    private const val MAX_IMAGE_EDGE = 2048
    private const val MAX_TEXT_BYTES = 1 * 1024 * 1024
    private const val MAX_TEXT_OUTPUT_CHARS = 300_000

    fun queryNameFromResolver(resolver: android.content.ContentResolver, uri: Uri): String {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "attachment"
    }

    suspend fun imageFromUri(context: Context, uri: Uri, name: String): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Tidak dapat membaca gambar")
                val decoded = decodeScaled(bytes)
                val rotated = applyExifRotation(context, uri, decoded)
                val source = rotated ?: decoded
                val bitmap = scaleIfNeeded(source)
                if (rotated != null && rotated !== bitmap) rotated.recycle()
                if (decoded !== source && decoded !== bitmap) decoded.recycle()
                compressToAttachment(bitmap, name)
            }
        }

    suspend fun imageFromFile(path: String, name: String): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = java.io.File(path).readBytes()
                val decoded = decodeScaled(bytes)
                val bitmap = scaleIfNeeded(decoded)
                if (decoded !== bitmap) decoded.recycle()
                compressToAttachment(bitmap, name)
            }
        }

    private fun decodeScaled(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Tidak dapat mendekode gambar")
        }
        var sample = 1
        var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("Tidak dapat mendekode gambar")
    }

    private fun scaleIfNeeded(source: Bitmap): Bitmap =
        if (maxOf(source.width, source.height) > MAX_IMAGE_EDGE) {
            val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(source.width, source.height)
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== source) source.recycle() }
        } else source

    private fun compressToAttachment(bitmap: Bitmap, name: String): Attachment {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return Attachment(
            kind = "image",
            name = name,
            mimeType = "image/jpeg",
            dataBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        )
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val exif = input.use { androidx.exifinterface.media.ExifInterface(it) }
            val rotation = when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return null
            }
            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun docFromUri(context: Context, uri: Uri, name: String): Result<Attachment> {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> DocParser.extractPdf(context, uri, name).map {
                Attachment(kind = "text_file", name = name, mimeType = "application/pdf", textContent = it)
            }
            "docx" -> DocParser.extractDocx(context, uri, name).map {
                Attachment(
                    kind = "text_file", name = name,
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    textContent = it
                )
            }
            "xlsx" -> DocParser.extractXlsx(context, uri, name).map {
                Attachment(
                    kind = "text_file", name = name,
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    textContent = it
                )
            }
            "doc", "xls", "ppt", "pptx" -> Result.failure(
                IllegalArgumentException("Format Office lama ($ext) belum didukung, simpan ulang sebagai docx/xlsx/pdf lalu coba lagi")
            )
            else -> textFromUri(context, uri, name)
        }
    }

    suspend fun textFromUri(context: Context, uri: Uri, name: String): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val size = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1
                } ?: -1L
                if (size > MAX_TEXT_BYTES) {
                    throw IllegalArgumentException(
                        "Berkas terlalu besar (${size / 1024}KB), hanya mendukung berkas teks hingga 1MB"
                    )
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().takeIf { b -> b.size <= MAX_TEXT_BYTES }
                } ?: throw IllegalArgumentException("Tidak dapat membaca berkas")
                if (bytes == null) {
                    throw IllegalArgumentException("Berkas terlalu besar (lebih dari 1MB), hanya mendukung berkas teks hingga 1MB")
                }
                val text = String(bytes, Charsets.UTF_8)
                if (text.isBlank()) throw IllegalArgumentException("Isi berkas kosong")
                val excerpt = if (text.length > MAX_TEXT_OUTPUT_CHARS) {
                    text.take(MAX_TEXT_OUTPUT_CHARS) + "\n… (isi terlalu panjang, dipotong, total ${text.length} karakter)"
                } else text
                Attachment(
                    kind = "text_file",
                    name = name,
                    mimeType = context.contentResolver.getType(uri) ?: "text/plain",
                    textContent = excerpt
                )
            }
        }
}
