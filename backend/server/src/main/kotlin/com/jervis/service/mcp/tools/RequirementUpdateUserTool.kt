package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.requirement.RequirementStatusEnum
import com.jervis.repository.UserRequirementMongoRepository
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * MCP tool for updating user requirement status.
 * Used to mark requirements as completed or cancelled.
 */
@Service
class RequirementUpdateUserTool(
    private val requirementRepository: UserRequirementMongoRepository,
    override val promptRepository: PromptRepository,
) : McpTool<RequirementUpdateUserTool.RequirementUpdateRequest> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.REQUIREMENT_UPDATE_USER_TOOL

    @Serializable
    data class RequirementUpdateRequest(
        val requirementId: String = "",
        val status: RequirementStatusEnum = RequirementStatusEnum.ACTIVE,
    )

    override val descriptionObject =
        RequirementUpdateRequest(
            requirementId = "673e5f8a9b2c1a0012345678",
            status = RequirementStatusEnum.COMPLETED,
        )

    override suspend fun execute(
        plan: Plan,
        request: RequirementUpdateRequest,
    ): ToolResult {
        logger.info { "REQUIREMENT_UPDATE_TOOL: Updating requirement status" }

        // Validate requirement ID
        val requirementId =
            try {
                ObjectId(request.requirementId)
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("Invalid requirement ID format: ${request.requirementId}")
            }

        // Update requirement
        val document =
            requirementRepository.findById(requirementId)
                ?: return ToolResult.error("Requirement not found: $requirementId")

        val updatedDocument =
            document.copy(
                status = request.status,
                completedAt =
                    if (request.status in listOf(RequirementStatusEnum.COMPLETED, RequirementStatusEnum.CANCELLED)) {
                        Instant.now()
                    } else {
                        null
                    },
            )

        val updated = requirementRepository.save(updatedDocument)

        val content =
            buildString {
                appendLine("✓ Updated requirement status")
                appendLine("Title: ${updated.title}")
                appendLine("Previous status: ${updated.status}")
                appendLine("New status: ${request.status}")
                appendLine("ID: ${updated.id}")
                if (updated.completedAt != null) {
                    appendLine("Completed at: ${updated.completedAt}")
                }
            }

        logger.info { "REQUIREMENT_UPDATE_SUCCESS: Updated requirement ${updated.id} to ${request.status}" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Updated requirement: ${updated.title} → ${request.status}",
            content = content,
        )
    }
}
