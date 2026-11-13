package com.miloai.skills.tools

import com.miloai.core.skills.Skill
import com.miloai.skills.DeviceTool
import com.miloai.skills.ToolContext
import com.miloai.skills.schemaOf
import com.miloai.skills.stringProp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class LoadSkillTool(
    private val skillProvider: () -> List<Skill>
) : DeviceTool {

    override val name = "load_skill"

    override val description: String
        get() {
            val available = skillProvider()
            if (available.isEmpty()) {
                return "Memuat skill untuk menjalankan tugas tertentu. Saat ini tidak ada skill yang tersedia, minta pengguna mengimpornya di halaman pengaturan."
            }
            val list = available.joinToString("; ") { it.name }
            return "Memuat skill dan menjalankan tugas persis sesuai instruksinya. Skill yang tersedia: $list."
        }

    override val readOnly = false

    override val parameters = schemaOf(
        "name" to stringProp("Nama skill yang dimuat, harus salah satu skill yang tersedia di atas"),
        required = listOf("name")
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String {
        val requested = arguments["name"]?.jsonPrimitive?.content?.trim()
            ?: return "Parameter name tidak ada"
        val skill = skillProvider().firstOrNull { it.name == requested }
            ?: return "Skill \"$requested\" tidak ditemukan. Skill yang tersedia: ${
                skillProvider().joinToString(", ") { it.name }
            }. Jika tidak ada skill yang tersedia, minta pengguna mengimpornya di halaman pengaturan."
        val allowed = if (skill.allowedTools.isEmpty()) {
            "Tanpa batas (semua alat bawaan dapat dipakai)"
        } else {
            skill.allowedTools.joinToString(", ")
        }
        return buildString {
            appendLine("Skill \"${skill.name}\" telah dimuat, jalankan sesuai instruksi berikut:")
            appendLine()
            appendLine("[Instruksi skill]")
            appendLine(skill.content)
            appendLine()
            append("[Alat bawaan yang boleh dipakai skill ini] $allowed")
        }
    }
}
