package com.miloai.skills

import com.miloai.core.engine.ToolCatalog
import com.miloai.core.mode.AppMode
import com.miloai.core.model.ToolSpec

class ToolRegistry(private val builtinTools: List<DeviceTool>) : ToolCatalog {

    fun findTool(name: String): DeviceTool? =
        builtinTools.firstOrNull { it.name == name }

    override fun specsFor(mode: AppMode): List<ToolSpec> = when (mode) {
        AppMode.CHAT -> emptyList()
        AppMode.PLAN -> builtinTools.filter { it.readOnly }.map { it.spec() }
        AppMode.BUILD, AppMode.MAX -> builtinTools.map { it.spec() }
    }

    override fun find(name: String): ToolSpec? = findTool(name)?.spec()

    private fun DeviceTool.spec() = ToolSpec(
        name = name,
        description = description,
        parameters = parameters,
        readOnly = readOnly
    )
}
