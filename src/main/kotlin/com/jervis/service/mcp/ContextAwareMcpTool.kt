package com.jervis.service.mcp

import com.jervis.domain.context.ProjectContextInfo
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.domain.ToolResult

/**
 * Enhanced MCP Tool interface that provides context-aware functionality.
 * All MCP tools should implement this interface to utilize project context information.
 */
interface ContextAwareMcpTool : McpTool {
    /**
     * Execute the tool with enhanced project context information.
     * This method provides access to technology stack, coding guidelines, and project context.
     */
    suspend fun executeWithProjectContext(
        taskDescription: String,
        context: TaskContext,
        plan: Plan,
    ): ToolResult

    /**
     * Build a context-aware prompt that includes project and technology information.
     * This helps create more targeted and relevant prompts for LLM interactions.
     */
    fun buildContextAwarePrompt(
        basePrompt: String,
        projectInfo: ProjectContextInfo?,
    ): String

    /**
     * Default implementation that falls back to standard execute method.
     * Tools can override executeWithProjectContext to provide enhanced functionality.
     */
    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult = executeWithProjectContext(taskDescription, context, plan)
}

/**
 * Default implementation of context-aware prompt building.
 * Provides common functionality for building prompts with project context.
 */
fun buildStandardContextAwarePrompt(
    basePrompt: String,
    projectInfo: ProjectContextInfo?,
): String =
    buildString {
        append(basePrompt)

        if (projectInfo != null) {
            append("\n\n=== PROJECT CONTEXT ===")

            // Project description
            projectInfo.projectDescription?.let { description ->
                append("\nProject Description: $description")
            }

            // Technology stack information
            val techStack = projectInfo.techStack
            append("\n\nTechnology Stack:")
            append("\n- Framework: ${techStack.framework}")
            append("\n- Language: ${techStack.language}")
            techStack.version?.let { append("\n- Version: $it") }
            techStack.securityFramework?.let { append("\n- Security: $it") }
            techStack.databaseType?.let { append("\n- Database: $it") }
            techStack.buildTool?.let { append("\n- Build Tool: $it") }

            // Coding guidelines
            val guidelines = projectInfo.codingGuidelines
            append("\n\nCoding Guidelines:")
            append("\n- Programming Style: ${guidelines.programmingStyle.language} with ${guidelines.programmingStyle.framework}")
            append("\n- Architectural Patterns: ${guidelines.programmingStyle.architecturalPatterns.joinToString(", ")}")
            append("\n- Testing Approach: ${guidelines.programmingStyle.testingApproach}")
            append("\n- Documentation Level: ${guidelines.programmingStyle.documentationLevel}")

            // Effective guidelines
            if (guidelines.effectiveGuidelines.rules.isNotEmpty()) {
                append("\n\nEffective Rules:")
                guidelines.effectiveGuidelines.rules.forEach { rule ->
                    append("\n- $rule")
                }
            }

            if (guidelines.effectiveGuidelines.conventions.isNotEmpty()) {
                append("\n\nCoding Conventions:")
                guidelines.effectiveGuidelines.conventions.forEach { (key, value) ->
                    append("\n- $key: $value")
                }
            }

            // Dependencies
            if (projectInfo.dependencyInfo.isNotEmpty()) {
                append("\n\nKey Dependencies:")
                projectInfo.dependencyInfo.take(10).forEach { dependency ->
                    append("\n- $dependency")
                }
            }
        }
    }
