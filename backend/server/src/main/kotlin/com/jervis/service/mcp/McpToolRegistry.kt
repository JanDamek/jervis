package com.jervis.service.mcp

import com.jervis.configuration.prompts.ToolTypeEnum
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

/**
 * Registry of all available MCP tools.
 * Provides centralized access to tools and generates descriptions for LLM prompts.
 */
@Service
@DependsOn("promptRepository")
class McpToolRegistry(
    private val tools: List<McpTool<*>>,
) {
    @Suppress("UNCHECKED_CAST")
    fun byName(name: ToolTypeEnum): McpTool<Any> = tools.first { it.name.name == name.name } as McpTool<Any>

    fun getAllTools(): List<McpTool<*>> = tools

    @PostConstruct
    fun initialize() {
        // Validate that all tools have proper descriptions
        tools.forEach { tool ->
            try {
                val description = tool.description
                check(!description.isBlank()) { "Description for tool '${tool.name}' is required but found empty or blank" }
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Description for tool '${tool.name}' is required but not found", e)
            }
        }
    }

    fun getAllToolsPlannerDescriptions() = tools.joinToString("\n") { it.plannerDescription }
}
