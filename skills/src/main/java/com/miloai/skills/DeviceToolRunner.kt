package com.miloai.skills

import com.miloai.core.engine.ToolRunner
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val toolArgsJson = Json { ignoreUnknownKeys = true }

internal fun parseToolArguments(raw: String): JsonObject? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    parseJsonObject(text)?.let { return it }
    val element = runCatching { toolArgsJson.parseToJsonElement(text) }.getOrNull()
    if (element is JsonPrimitive && element.isString) {
        parseJsonObject(element.content)?.let { return it }
    }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start in 0 until end) {
        parseJsonObject(text.substring(start, end + 1))?.let { return it }
    }
    return null
}

private fun parseJsonObject(text: String): JsonObject? =
    runCatching { toolArgsJson.parseToJsonElement(text).jsonObject }.getOrNull()

class DeviceToolRunner(
    private val registry: ToolRegistry,
    private val context: ToolContext
) : ToolRunner {

    override suspend fun run(name: String, arguments: String): String {
        val tool = registry.findTool(name)
            ?: throw IllegalArgumentException("Alat tidak dikenal: $name")
        val args: JsonObject = parseToolArguments(arguments)
            ?: return "Parameter alat gagal diurai: $name menerima parameter yang tidak dapat diurai sebagai objek JSON (teks asli: ${
                arguments.take(120)
            }). Susun ulang parameter, pastikan berupa objek JSON seperti {\"nama_parameter\":\"nilai\"}."
        val (healed, fixes) = healArgs(tool.parameters, args)
        return try {
            val result = tool.execute(context, healed)
            if (fixes.isEmpty()) result
            else "(Parameter dikoreksi otomatis: ${fixes.joinToString("; ")})\n$result"
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            val fixNote = if (fixes.isEmpty()) "" else "(Sudah mencoba mengoreksi parameter otomatis: ${fixes.joinToString("; ")}, tetap gagal)\n"
            if (e.message?.contains("JsonPrimitive") == true) {
                fixNote + "Parameter alat bertipe salah: berikan parameter sesuai deskripsi alat. Nilai string harus diberi tanda kutip (mis. \"query\":\"...\"), angka berupa bilangan bulat, dan jangan mengarang nama parameter."
            } else {
                fixNote + "Eksekusi alat bermasalah: ${e.message ?: e.javaClass.simpleName}"
            }
        } catch (e: Exception) {
            "Eksekusi alat bermasalah: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun healArgs(schema: JsonObject, args: JsonObject): Pair<JsonObject, List<String>> {
        val props = schema["properties"]?.jsonObject ?: return args to emptyList()
        val fixes = mutableListOf<String>()
        val out = buildJsonObject {
            args.forEach { (rawKey, v) ->
                val key = when {
                    rawKey in props -> rawKey
                    else -> {
                        val match = props.keys
                            .filter { it.length >= 3 }
                            .minByOrNull { levenshtein(rawKey.lowercase(), it.lowercase()) }
                        if (match != null && levenshtein(rawKey.lowercase(), match.lowercase()) <= 2) {
                            fixes += "Nama parameter '$rawKey' dikoreksi menjadi '$match'"
                            match
                        } else rawKey
                    }
                }
                val propType = props[key]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
                when {
                    propType == "string" && v is JsonPrimitive && !v.isString -> {
                        fixes += "Parameter '$key' diubah menjadi string"
                        put(key, JsonPrimitive(v.content))
                    }
                    propType == "string" && v is kotlinx.serialization.json.JsonArray -> {
                        put(key, v)
                    }
                    propType == "string" && v !is JsonPrimitive -> {
                        fixes += "Parameter '$key' diubah dari objek menjadi string"
                        put(key, JsonPrimitive(v.toString()))
                    }
                    propType == "integer" && v is JsonPrimitive && v.isString -> {
                        v.content.toLongOrNull()?.let {
                            fixes += "Parameter '$key' diubah dari string menjadi bilangan bulat"
                            put(key, JsonPrimitive(it))
                            return@forEach
                        }
                        put(key, v)
                    }
                    else -> put(key, v)
                }
            }
        }
        return out to fixes
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }
}
