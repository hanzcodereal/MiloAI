package com.miloai.core.skills

import android.content.Context
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Locale

data class Skill(
    val name: String,
    val description: String,
    val content: String,
    val allowedTools: List<String> = emptyList(),
    val fileName: String = ""
)

class SkillRepository(private val context: Context) {

    private val dir = File(context.filesDir, "skills")
    private val yaml = Yaml()

    fun loadAll(): List<Skill> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) == "md" }
            ?.mapNotNull { f -> parse(f.name, f.readText()) }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun loadByName(name: String): Skill? = loadAll().firstOrNull { it.name == name }

    fun import(fileName: String, content: String): Result<Skill> {
        return runCatching {
            val skill = parse(fileName, content)
                ?: throw IllegalArgumentException("Tidak dapat mengurai berkas Skill: frontmatter (name / description) tidak ada")
            if (skill.name.isBlank() || skill.name.contains(" ") || skill.name.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) {
                throw IllegalArgumentException("Nama Skill hanya boleh berisi huruf, angka, garis bawah, atau tanda hubung")
            }
            if (skill.description.isBlank()) {
                throw IllegalArgumentException("Skill tidak memiliki description")
            }
            if (loadAll().any { it.name == skill.name }) {
                throw IllegalArgumentException("Skill dengan nama yang sama sudah ada: ${skill.name}")
            }
            if (!dir.exists()) dir.mkdirs()
            val safeName = skill.name + ".md"
            File(dir, safeName).writeText(content.trim() + "\n")
            skill.copy(fileName = safeName)
        }
    }

    fun delete(name: String): Boolean {
        val skill = loadByName(name) ?: return false
        return File(dir, skill.fileName).delete()
    }

    private fun parse(fileName: String, content: String): Skill? {
        val lines = content.trim().lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return null
        val frontmatter = lines.drop(1).take(endIndex).joinToString("\n")
        val body = lines.drop(endIndex + 2).joinToString("\n").trim()

        val map = runCatching {
            yaml.load<Any?>(frontmatter) as? Map<*, *>
        }.getOrNull() ?: return null

        val name = map["name"]?.toString()?.trim()
        if (name.isNullOrBlank()) return null
        val description = map["description"]?.toString()?.trim() ?: ""
        val allowedTools = (map["allowed-tools"] as? List<*>)?.mapNotNull { it?.toString()?.trim() } ?: emptyList()

        return Skill(
            name = name,
            description = description,
            content = body,
            allowedTools = allowedTools,
            fileName = fileName
        )
    }
}
