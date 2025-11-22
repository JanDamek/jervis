package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.requirement.RequirementPriorityEnum
import com.jervis.entity.UserRequirementDocument
import com.jervis.repository.UserRequirementMongoRepository
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
    private val requirementRepository: UserRequirementMongoRepository,
    override val promptRepository: PromptRepository,
) : McpTool<RequirementCreateUserTool.RequirementCreateRequest> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.REQUIREMENT_CREATE_USER_TOOL

    @Serializable
    data class RequirementCreateRequest(
        val title: String = "",
        val description: String = "",
        val keywords: List<String> = emptyList(),
        val priority: RequirementPriorityEnum = RequirementPriorityEnum.MEDIUM,
    )

    override val descriptionObject =
        RequirementCreateRequest(
            title = "Track NVIDIA GPU prices",
            description = "Notify me when RTX 5090 drops below $1500",
            keywords = listOf("GPU", "NVIDIA", "price"),
            priority = RequirementPriorityEnum.HIGH,
        )

    override suspend fun execute(
        plan: Plan,
        request: RequirementCreateRequest,
    ): ToolResult {
        logger.info { "REQUIREMENT_CREATE_TOOL: Creating user requirement" }

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
}
