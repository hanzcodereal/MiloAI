package com.miloai.skills.tools

import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class PingNetworkTool : DeviceTool {

    override val name = "ping_network"
    override val description = "Uji konektivitas dan latensi jaringan: ping sebuah host (default 8.8.8.8) dan kembalikan latensi dalam milidetik; juga mendukung uji antarmuka HTTP (alamat http/https) dan mengembalikan kode status serta waktunya. Dipakai untuk mendiagnosis masalah jaringan."
    override val readOnly = true
    override val parameters = schemaOf(
        "target" to stringProp("Target: IP atau domain (mis. 8.8.8.8, google.com) atau alamat http(s), default 8.8.8.8"),
        required = emptyList()
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            val target = arguments["target"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() } ?: "8.8.8.8"
            if (target.startsWith("http://") || target.startsWith("https://")) {
                runCatching {
                    val start = System.currentTimeMillis()
                    val conn = URL(target).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.instanceFollowRedirects = true
                    try {
                        conn.connect()
                        val code = conn.responseCode
                        val ms = System.currentTimeMillis() - start
                        "HTTP ${code} · waktu ${ms}ms · $target"
                    } finally {
                        conn.disconnect()
                    }
                }.getOrElse { e -> "ERROR:Tidak dapat terhubung ke $target: ${e.message}" }
            } else {
                runCatching {
                    val start = System.currentTimeMillis()
                    val addr = InetAddress.getByName(target)
                    val reachable = addr.isReachable(10_000)
                    val ms = System.currentTimeMillis() - start
                    if (reachable) {
                        "Jaringan terhubung · $target (${addr.hostAddress}) latensi sekitar ${ms}ms"
                    } else {
                        "ERROR:$target tidak dapat dijangkau (waktu habis)"
                    }
                }.getOrElse { e -> "ERROR:Tidak dapat menguraikan $target: ${e.message}" }
            }
        }
}
