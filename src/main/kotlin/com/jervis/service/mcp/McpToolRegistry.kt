package com.jervis.service.mcp

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.agent.planner.Planner
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

/**
 * Registry of all available MCP tools.
 * Currently only collects beans by injection; selection logic will be added later.
 */
@Service
@DependsOn("promptRepository")
class McpToolRegistry(
    private val tools: List<McpTool>,
    private val planner: Planner,
) {
    fun byName(name: PromptTypeEnum): McpTool = tools.first { it.name == name }

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

        planner.allToolDescriptions =
            tools.joinToString(separator = "\n") { tool ->
                """ 
                ${tool.name}:
                ${tool.description}
                 
                """.trimIndent()
            }

        // Generate available tool names from enum for dynamic substitution
        planner.availableToolNames = PromptTypeEnum.values()
            .filter { enumValue -> tools.any { tool -> tool.name == enumValue } }
            .joinToString { it.name }
    }
}
