package com.miloai.skills

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolContext(
    val appContext: Context
)

interface DeviceTool {
    val name: String
    val description: String
    val readOnly: Boolean
    val parameters: JsonObject

    suspend fun execute(context: ToolContext, arguments: JsonObject): String
}

fun schemaOf(
    vararg props: Pair<String, JsonObject>,
    required: List<String> = emptyList()
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        props.forEach { (k, v) -> put(k, v) }
    })
    if (required.isNotEmpty()) {
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun numberProp(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}

fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

fun arrayProp(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}
