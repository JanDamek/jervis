package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.requirement.RequirementStatusEnum
import com.jervis.repository.mongo.UserRequirementMongoRepository
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * PARAMETRIC MCP tool for querying user requirements/wishes.
 * NO LLM - planner provides keywords directly as JSON.
 * Used in EMAIL_PROCESSING to check if email content matches what user wants to track.
 */
@Service
class RequirementQueryUserTool(
    private val requirementRepository: UserRequirementMongoRepository,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val json = Json { ignoreUnknownKeys = true }
    }

    override val name: PromptTypeEnum = PromptTypeEnum.REQUIREMENT_QUERY_USER_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "REQUIREMENT_QUERY_TOOL: Querying user requirements" }

        // Parse parameters - handle both JSON and text with keywords
        val request =
            try {
                // Try JSON first
                json.decodeFromString<RequirementQueryRequest>(taskDescription)
            } catch (e: Exception) {
                // Fallback: extract keywords from text (e.g., "Check user requirements for keywords: rigips, sádrokarton")
                logger.debug { "Parsing keywords from text: $taskDescription" }
                val keywords =
                    taskDescription
                        .substringAfter("keywords:", "")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                if (keywords.isNotEmpty()) {
                    RequirementQueryRequest(keywords)
                } else {
                    RequirementQueryRequest()
                }
            }

        // Query requirements
        val flow =
            if (plan.projectDocument != null) {
                requirementRepository.findByClientIdAndProjectIdAndStatus(
                    plan.clientDocument.id,
                    plan.projectDocument!!.id,
                    RequirementStatusEnum.ACTIVE,
                )
            } else {
                requirementRepository.findByClientIdAndStatus(
                    plan.clientDocument.id,
                    RequirementStatusEnum.ACTIVE,
                )
            }

        // Filter by keywords if provided and convert to list
        val requirements =
            if (request.keywords.isNotEmpty()) {
                flow
                    .filter { doc ->
                        doc.keywords.any { keyword ->
                            request.keywords.any { searchKeyword ->
                                keyword.contains(searchKeyword, ignoreCase = true) ||
                                    searchKeyword.contains(keyword, ignoreCase = true)
                            }
                        }
                    }.toList()
            } else {
                flow.toList()
            }

        val content =
            if (requirements.isEmpty()) {
                buildString {
                    appendLine("No matching requirements found")
                    if (request.keywords.isNotEmpty()) {
                        appendLine("Search keywords: ${request.keywords.joinToString(", ")}")
                    }
                }
            } else {
                buildString {
                    appendLine("Found ${requirements.size} matching requirement(s):")
                    appendLine()
                    requirements.forEachIndexed { index, req ->
                        appendLine("${index + 1}. ${req.title}")
                        appendLine("   Description: ${req.description}")
                        appendLine("   Keywords: ${req.keywords.joinToString(", ")}")
                        appendLine("   Priority: ${req.priority}")
                        appendLine("   Status: ${req.status}")
                        appendLine("   ID: ${req.id}")
                        appendLine()
                    }
                }
            }

        logger.info { "REQUIREMENT_QUERY_SUCCESS: Found ${requirements.size} requirement(s)" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Found ${requirements.size} requirement(s)",
            content = content,
        )
    }

    @Serializable
    data class RequirementQueryRequest(
        val keywords: List<String> = emptyList(),
    )
}
