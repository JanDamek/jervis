package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.AgentConstants
import com.jervis.service.agent.coordinator.LanguageOrchestrator
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.ToolResult
import org.springframework.stereotype.Service

/**
 * Tool that normalizes the current task's initial query to English using LanguageOrchestrator.
 * Returns the normalized English text; does not persist it (keeps tool side-effect minimal).
 */
@Service
class LanguageNormalizationTool(
    private val language: LanguageOrchestrator,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.LANGUAGE_NORMALIZE
    override val description: String = "Normalize the initial query to English using LanguageOrchestrator."

    override suspend fun execute(context: TaskContextDocument, parameters: Map<String, Any>): ToolResult {
        val query = context.initialQuery.trim()
        if (query.isEmpty()) return ToolResult(success = true, output = "")
        val lang = language.detectLanguage(query)
        val normalized = if (lang == "en") query else language.translateToEnglish(query, lang)
        return ToolResult(success = true, output = normalized)
    }
}
