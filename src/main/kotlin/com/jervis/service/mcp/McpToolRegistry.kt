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

    fun getAllToolDescriptions(): List<String> = tools.map { tool -> "${tool.name}: ${tool.description}" }
}
