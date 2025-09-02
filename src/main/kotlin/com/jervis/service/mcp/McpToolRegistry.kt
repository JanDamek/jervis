package com.jervis.service.mcp

import com.jervis.service.agent.planner.Planner
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

/**
 * Registry of all available MCP tools.
 * Currently only collects beans by injection; selection logic will be added later.
 */
@Service
class McpToolRegistry(
    private val tools: List<McpTool>,
    private val planner: Planner,
) {
    fun all(): List<McpTool> = tools

    fun byName(name: String): McpTool = tools.first { it.name == name }

    @PostConstruct
    fun initialize() {
        planner.allToolDescriptions =
            tools.joinToString(separator = "\n\n") { tool ->
                "Tool: ${tool.name}\nDescription: ${tool.description}"
            }
    }
}
