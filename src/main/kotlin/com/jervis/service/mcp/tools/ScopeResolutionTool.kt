package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.ProjectContextInfo
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ScopeResolutionTool(
    override val promptRepository: PromptRepository,
    private val llmGateway: LlmGateway,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.SCOPE_RESOLUTION

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.debug { "SCOPE_RESOLUTION: Adding contextual project and client information to context=${context.id}" }

        val client = context.clientDocument
        val project = context.projectDocument

        // Use LLM to determine what level of detail to include
        val contextRequirements = determineContextRequirements(taskDescription, client.name, project.name, stepContext)

        val summary =
            buildAdaptiveSummary(
                client,
                project,
                context.projectContextInfo,
                contextRequirements,
                taskDescription,
            )

        logger.debug { "SCOPE_RESOLUTION: Successfully added adaptive context information based on task requirements" }

        return ToolResult.ok(summary)
    }

    data class ContextRequirements(
        val includeClientDetails: Boolean = true,
        val includeProjectDetails: Boolean = true,
        val includeFullClientDescription: Boolean = false,
        val includeFullProjectDescription: Boolean = false,
        val includeTechStack: Boolean = false,
        val includeDependencies: Boolean = false,
        val detailLevel: DetailLevel = DetailLevel.BASIC,
    )

    enum class DetailLevel { MINIMAL, BASIC, DETAILED, COMPREHENSIVE }

    private suspend fun determineContextRequirements(
        taskDescription: String,
        clientName: String,
        projectName: String,
        stepContext: String = "",
    ): ContextRequirements =
        try {
            val response =
                llmGateway.callLlm(
                    type = PromptTypeEnum.SCOPE_RESOLUTION,
                    userPrompt = taskDescription,
                    quick = true,
                    "",
                    mappingValue =
                        mapOf(
                            "taskDescription" to taskDescription,
                            "clientName" to clientName,
                            "projectName" to projectName,
                        ),
                    stepContext = stepContext,
                )

            // Parse JSON response - simplified parsing
            val answer = response.lowercase()
            ContextRequirements(
                includeClientDetails = true,
                includeProjectDetails = true,
                includeFullClientDescription = answer.contains("\"includefullclientdescription\": true"),
                includeFullProjectDescription = answer.contains("\"includefullprojectdescription\": true"),
                includeTechStack = answer.contains("\"includetechstack\": true"),
                includeDependencies = answer.contains("\"includedependencies\": true"),
                detailLevel =
                    when {
                        answer.contains("comprehensive") -> DetailLevel.COMPREHENSIVE
                        answer.contains("detailed") -> DetailLevel.DETAILED
                        answer.contains("minimal") -> DetailLevel.MINIMAL
                        else -> DetailLevel.BASIC
                    },
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to determine context requirements, using basic defaults" }
            ContextRequirements() // Default to basic level
        }

    private fun buildAdaptiveSummary(
        client: com.jervis.entity.mongo.ClientDocument,
        project: com.jervis.entity.mongo.ProjectDocument,
        projectContextInfo: ProjectContextInfo?,
        requirements: ContextRequirements,
        taskDescription: String,
    ): String =
        buildString {
            appendLine("ℹ️ **Context Information Added Based on Task Requirements**")
            appendLine()
            appendLine("**Task:** $taskDescription")
            appendLine("**Detail Level:** ${requirements.detailLevel}")
            appendLine()

            if (requirements.includeClientDetails) {
                appendLine("**Client Information:**")
                appendLine("• Name: ${client.name}")

                if (requirements.detailLevel >= DetailLevel.BASIC) {
                    client.shortDescription?.let { appendLine("• Short Description: $it") }
                }

                if (requirements.includeFullClientDescription && !client.fullDescription.isNullOrBlank()) {
                    appendLine("• Full Description: ${client.fullDescription}")
                }

                if (requirements.detailLevel >= DetailLevel.DETAILED) {
                    client.description?.let { appendLine("• Description: $it") }
                    appendLine("• Default Language: ${client.defaultLanguage}")
                }
                appendLine()
            }

            if (requirements.includeProjectDetails) {
                appendLine("**Project Information:**")
                appendLine("• Name: ${project.name}")

                if (requirements.detailLevel >= DetailLevel.BASIC) {
                    project.shortDescription?.let { appendLine("• Short Description: $it") }
                }

                if (requirements.includeFullProjectDescription && !project.fullDescription.isNullOrBlank()) {
                    appendLine("• Full Description: ${project.fullDescription}")
                }

                if (requirements.detailLevel >= DetailLevel.DETAILED) {
                    project.description?.let { appendLine("• Description: $it") }
                    project.path.takeIf { it.isNotEmpty() }?.let { appendLine("• Path: $it") }
                    if (project.languages.isNotEmpty()) {
                        appendLine("• Languages: ${project.languages.joinToString(", ")}")
                    }
                }
                appendLine()
            }

            if (projectContextInfo != null &&
                (requirements.includeTechStack || requirements.includeDependencies || requirements.detailLevel >= DetailLevel.DETAILED)
            ) {
                appendLine("**Additional Indexed Context:**")

                if (requirements.detailLevel >= DetailLevel.BASIC) {
                    projectContextInfo.projectDescription?.let { appendLine("• Project Description: $it") }
                }

                if (requirements.includeTechStack || requirements.detailLevel >= DetailLevel.DETAILED) {
                    projectContextInfo.techStack.let { stack ->
                        appendLine("• Technology Stack: ${stack.framework} with ${stack.language}")
                    }
                }

                if (requirements.includeDependencies || requirements.detailLevel >= DetailLevel.COMPREHENSIVE) {
                    if (projectContextInfo.dependencyInfo.isNotEmpty()) {
                        appendLine("• Dependencies: ${projectContextInfo.dependencyInfo.joinToString(", ")}")
                    }
                }
                appendLine()
            }

            appendLine("**Context Information Successfully Added** ✅")
            appendLine("Selective project and client information has been inserted based on task analysis.")
            when (requirements.detailLevel) {
                DetailLevel.MINIMAL -> appendLine("• Level: Minimal context (names and essential info only)")
                DetailLevel.BASIC -> appendLine("• Level: Basic context (standard information)")
                DetailLevel.DETAILED -> appendLine("• Level: Detailed context (comprehensive information)")
                DetailLevel.COMPREHENSIVE -> appendLine("• Level: Comprehensive context (all available information)")
            }
        }
}
