package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.intProp
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URL

class FetchRssTool : DeviceTool {

    override val name = "fetch_rss"
    override val description = "Ambil entri terbaru dari umpan RSS/Atom (judul, tautan, waktu terbit, ringkasan). url adalah alamat umpan, limit adalah jumlah entri (default 10, maksimum 20)."
    override val readOnly = true
    override val parameters = schemaOf(
        "url" to stringProp("Alamat umpan RSS, mis. https://example.com/feed.xml"),
        "limit" to intProp("Jumlah entri, default 10, maksimum 20"),
        required = listOf("url")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = arguments["url"]?.jsonPrimitive?.content ?: return@runCatching "Parameter url tidak valid"
                val limit = (arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10).coerceIn(1, 20)
                val doc = Jsoup.connect(url).maxBodySize(2 * 1024 * 1024)
                    .timeout(15_000)
                    .userAgent("MiloAI/1.0 RSS reader")
                    .get()
                val channel = doc.selectFirst("rss > channel")
                val title = if (channel != null) {
                    channel.selectFirst("title")?.text() ?: "RSS"
                } else {
                    doc.selectFirst("feed > title")?.text() ?: "Atom"
                }
                val items = if (channel != null) {
                    channel.select("item")
                } else {
                    doc.select("feed > entry")
                }
                if (items.isEmpty()) return@runCatching "Umpan \"$title\" tidak memiliki entri"
                val sb = StringBuilder()
                sb.appendLine("Umpan: $title (total ${items.size} entri, menampilkan $limit teratas)")
                items.take(limit).forEachIndexed { i, it ->
                    val itemTitle = it.selectFirst("title")?.text() ?: "(tanpa judul)"
                    val link = it.selectFirst("link")?.attr("href")
                        ?: it.selectFirst("link")?.text()
                        ?: ""
                    val pubDate = it.selectFirst("pubDate")?.text()
                        ?: it.selectFirst("updated")?.text()
                        ?: ""
                    sb.appendLine("${i + 1}. $itemTitle")
                    if (pubDate.isNotBlank()) sb.appendLine("   Waktu: $pubDate")
                    if (link.isNotBlank()) sb.appendLine("   Tautan: $link")
                }
                sb.toString().trim()
            }.getOrElse { e -> "ERROR:Gagal mengambil umpan: ${e.message}" }
        }
}
