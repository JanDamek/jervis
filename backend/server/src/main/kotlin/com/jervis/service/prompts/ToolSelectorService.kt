package com.jervis.service.prompts

import com.jervis.configuration.prompts.PromptTypeEnum
import org.springframework.stereotype.Service
import kotlinx.serialization.Serializable

@Serializable
data class ToolRequirement(
    val capability: String,
    val detail: String,
    val priority: String = "medium",
)

data class SelectedTool(
    val tool: PromptTypeEnum,
    val reason: String,
    val params: Map<String, Any> = emptyMap(),
)

@Service
class ToolSelectorService {
    /**
     * Map planner `tool_requirements` into concrete MCP tools (PromptTypeEnum) and suggested params
     */
    fun selectTools(
        requirements: List<ToolRequirement>,
        projectContext: Map<String, Any> = emptyMap(),
    ): List<SelectedTool> =
        requirements.map { req ->
            val matched = PromptTypeEnum.matchByCapability(req.capability)
            if (matched != null) {
                val reason = "Matched capability '${req.capability}' to tool ${matched.name}"
                val params = suggestParamsFor(matched, req, projectContext)
                SelectedTool(matched, reason, params)
            } else {
                // Fallback to a safe analysis tool
                val fallback = PromptTypeEnum.ANALYSIS_REASONING_TOOL
                val reason = "No direct tool match for capability '${req.capability}', falling back to ${fallback.name}"
                val params = mapOf("capability" to req.capability, "detail" to req.detail)
                SelectedTool(fallback, reason, params)
            }
        }

    private fun suggestParamsFor(
        tool: PromptTypeEnum,
        req: ToolRequirement,
        projectContext: Map<String, Any>,
    ): Map<String, Any> =
        when (tool) {
            PromptTypeEnum.GIT_FILE_CURRENT_CONTENT_TOOL -> mapOf("filePath" to req.detail)
            PromptTypeEnum.GIT_COMMIT_DIFF_TOOL -> mapOf("commitHash" to req.detail)
            PromptTypeEnum.GIT_COMMIT_FILES_LIST_TOOL -> mapOf("commitHash" to req.detail)
            PromptTypeEnum.SOURCE_FETCH_ORIGINAL_TOOL -> mapOf("uri" to req.detail)
            PromptTypeEnum.CODE_ANALYZE_TOOL -> mapOf("analysisQuery" to req.detail, "maxResults" to 100)
            PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL -> mapOf("command" to req.detail)
            PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL -> mapOf("query" to req.detail)
            else -> mapOf("info" to req.detail)
        }
}
