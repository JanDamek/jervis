package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.requirement.RequirementStatus
import com.jervis.repository.mongo.UserRequirementMongoRepository
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
    private val llmGateway: LlmGateway,
    private val requirementRepository: UserRequirementMongoRepository,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.REQUIREMENT_UPDATE_USER_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "REQUIREMENT_UPDATE_TOOL: Updating requirement status" }

        val result =
            llmGateway.callLlm(
                type = name,
                mappingValue = mapOf("taskDescription" to taskDescription),
                quick = false,
                responseSchema = RequirementUpdateRequest(),
                backgroundMode = plan.backgroundMode,
            )

        val request = result.result

        // Validate requirement ID
        val requirementId =
            try {
                ObjectId(request.requirementId)
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("Invalid requirement ID format: ${request.requirementId}")
            }

        // Update requirement
        val document =
            requirementRepository.findById(requirementId).awaitSingleOrNull()
                ?: return ToolResult.error("Requirement not found: $requirementId")

        val updatedDocument =
            document.copy(
                status = request.status,
                completedAt =
                    if (request.status in listOf(RequirementStatus.COMPLETED, RequirementStatus.CANCELLED)) {
                        Instant.now()
                    } else {
                        null
                    },
            )

        val updated = requirementRepository.save(updatedDocument).awaitSingle()

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

    @Serializable
    data class RequirementUpdateRequest(
        val requirementId: String = "",
        val status: RequirementStatus = RequirementStatus.ACTIVE,
    )
}
