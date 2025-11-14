package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.requirement.RequirementPriorityEnum
import com.jervis.entity.UserRequirementDocument
import com.jervis.repository.mongo.UserRequirementMongoRepository
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * MCP tool for creating user requirements/wishes.
 * Used to track what users want to be notified about (e.g., "Find Spain vacation", "Track GPU prices").
 */
@Service
class RequirementCreateUserTool(
    private val llmGateway: LlmGateway,
    private val requirementRepository: UserRequirementMongoRepository,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.REQUIREMENT_CREATE_USER_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "REQUIREMENT_CREATE_TOOL: Creating user requirement" }

        val result =
            llmGateway.callLlm(
                type = name,
                mappingValue = mapOf("taskDescription" to taskDescription),
                correlationId = plan.correlationId,
                quick = false,
                responseSchema = RequirementCreateRequest(),
                backgroundMode = plan.backgroundMode,
            )

        val request = result.result

        // Validate request
        if (request.title.isBlank()) {
            return ToolResult.error("Requirement title cannot be blank")
        }

        // Create requirement document
        val document =
            UserRequirementDocument(
                clientId = plan.clientDocument.id,
                projectId = plan.projectDocument?.id,
                title = request.title,
                description = request.description,
                keywords = request.keywords,
                priority = request.priority,
            )

        val created = requirementRepository.save(document)

        val content =
            buildString {
                appendLine("✓ Created user requirement")
                appendLine("Title: ${created.title}")
                appendLine("Description: ${created.description}")
                appendLine("Keywords: ${created.keywords.joinToString(", ")}")
                appendLine("Priority: ${created.priority}")
                appendLine("ID: ${created.id}")
            }

        logger.info { "REQUIREMENT_CREATE_SUCCESS: Created requirement '${created.title}' with ${created.keywords.size} keywords" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Created requirement: ${created.title}",
            content = content,
        )
    }

    @Serializable
    data class RequirementCreateRequest(
        val title: String = "",
        val description: String = "",
        val keywords: List<String> = emptyList(),
        val priority: RequirementPriorityEnum = RequirementPriorityEnum.MEDIUM,
    )
}
