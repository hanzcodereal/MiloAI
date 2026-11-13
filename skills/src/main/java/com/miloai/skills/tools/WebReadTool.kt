package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.intProp
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class WebReadTool : DeviceTool {

    override val name = "web_read"
    override val description = "Mengambil halaman web tertentu dan mengekstrak isi utamanya. Biasanya hanya diperlukan bila web_search tidak otomatis menyertakan isi (read_top≥1); " +
        "gunakan saat isi dari web_search kurang, atau saat perlu membaca halaman yang posisinya lebih bawah di daftar hasil pencarian. " +
        "url harus berupa tautan lengkap yang dikembalikan web_search; untuk membaca beberapa halaman, langsung pakai array urls (maksimum 3) sekaligus, jangan dipanggil berkali-kali. " +
        "Gagal membaca umumnya karena proteksi anti-bot atau rendering JS: jangan ulangi url yang sama, ganti ke sumber lain (mis. domain berbeda) atau minta web_search memakai kata kunci lain."
    override val readOnly = true
    override val parameters = schemaOf(
        "url" to stringProp("URL lengkap halaman yang dibaca (http/https)"),
        "max_chars" to intProp("Jumlah karakter maksimum isi yang dikembalikan, default 3000, maksimum 6000")
    )

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            val urls: List<String> = when (val el = arguments["urls"]) {
                is kotlinx.serialization.json.JsonArray ->
                    el.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                is kotlinx.serialization.json.JsonPrimitive -> listOfNotNull(el.content)
                else -> emptyList()
            }.plus(
                arguments["url"]?.jsonPrimitive?.content?.let { listOf(it) } ?: emptyList()
            ).map { it.trim() }.filter { com.miloai.skills.isPublicHttpUrl(it) }.distinct().take(3)
            if (urls.isEmpty()) {
                return@withContext "Parameter url tidak ada: berikan tautan lengkap dari web_search (bisa mengirim array urls untuk membaca beberapa sekaligus)"
            }
            if (urls.size > 1) {
                return@withContext fetchMultiple(urls)
            }
            val url = urls.first()
            val maxChars = (arguments["max_chars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3000).coerceIn(500, 6000)
            try {
                val doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                    .followRedirects(true)
                    .timeout(20_000)
                    .maxBodySize(2 * 1024 * 1024)
                    .get()
                doc.select("script, style, noscript, iframe, nav, footer, header, form, aside, .ad, .ads, .advertisement, .cookie, [aria-hidden=true]").remove()
                val main = doc.selectFirst("article, main, [role=main]")
                val raw = (main ?: doc.body())?.text()?.trim().orEmpty()
                val text = raw.replace(Regex("\\s{2,}"), " ")
                if (text.isEmpty()) {
                    return@withContext "Tidak dapat mengekstrak isi dari halaman ini (rendering JS atau akses ditolak). Saran: coba situs sumber lain, atau minta web_search memakai kata kunci berbeda."
                }
                val title = doc.title().trim()
                val excerpt = text.take(maxChars) + if (text.length > maxChars) "\n… (isi dipotong, naikkan max_chars atau baca per bagian)" else ""
                "Judul halaman: $title\nAlamat halaman: $url\nIsi:\n$excerpt"
            } catch (e: java.net.SocketTimeoutException) {
                "Waktu baca habis: situs ini terlalu lambat merespons. Jangan ulangi url yang sama, baca sumber lain (entri lain di hasil web_search)."
            } catch (e: Exception) {
                "Gagal membaca: ${e.message ?: "Kesalahan jaringan"}. Sebaiknya ganti ke situs sumber lain, atau minta web_search memakai kata kunci berbeda."
            }
        }

    private suspend fun fetchMultiple(urls: List<String>): String =
        coroutineScope {
            val pages = urls.map { u ->
                async {
                    try {
                        readPage(u, 1800)
                    } catch (e: Exception) {
                        "(Gagal membaca: ${e.message ?: "Kesalahan jaringan"})"
                    }
                }
            }.map { it.await() }
            buildString {
                appendLine("${urls.size} halaman telah dibaca:")
                urls.forEachIndexed { i, u ->
                    appendLine()
                    appendLine("[Halaman ${i + 1}] $u")
                    append(pages.getOrNull(i).orEmpty())
                    appendLine()
                }
            }
        }

    private fun readPage(url: String, maxChars: Int): String {
        val doc = Jsoup.connect(url)
            .userAgent(userAgent)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .followRedirects(true)
            .timeout(20_000)
            .maxBodySize(2 * 1024 * 1024)
            .get()
        doc.select("script, style, noscript, iframe, nav, footer, header, form, aside, .ad, .ads, .advertisement, .cookie, [aria-hidden=true]").remove()
        val main = doc.selectFirst("article, main, [role=main]")
        val raw = (main ?: doc.body())?.text()?.trim().orEmpty()
        val text = raw.replace(Regex("\\s{2,}"), " ")
        if (text.isEmpty()) return "(Tidak dapat mengekstrak isi: rendering JS atau akses ditolak)"
        return text.take(maxChars) + if (text.length > maxChars) "…" else ""
    }
}
