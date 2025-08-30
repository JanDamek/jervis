package com.jervis.service.mcp

import org.springframework.stereotype.Service

/**
 * Registry of all available MCP tools.
 * Currently only collects beans by injection; selection logic will be added later.
 */
@Service
class McpToolRegistry(
    private val tools: List<McpTool>,
) {
    fun all(): List<McpTool> = tools

    fun byName(name: String): McpTool? = tools.find { it.name == name }

    fun names(): Set<String> = tools.map { it.name }.toSet()

    fun getAllToolDescriptions(): List<String> = tools.map { tool -> "${tool.name}: ${tool.description}" }

    /**
     * Returns JSON lines (one JSON per line) describing available MCP tools.
     */
    fun getAllToolDescriptionsJson(): String =
        tools.joinToString(separator = "\n") {
            """{"name": "${it.name}", "description": "${it.description}"}"""
        }
}
