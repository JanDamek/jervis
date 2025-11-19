package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.KnowledgeType
import com.jervis.domain.task.TaskPriorityEnum
import com.jervis.domain.task.TaskSourceType
import com.jervis.service.knowledge.KnowledgeClassifierService
import com.jervis.service.knowledge.KnowledgeManagementService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.task.UserTaskService
import com.jervis.service.text.TextChunkingService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Intelligent Knowledge Store tool.
 *
 * Automatically classifies content into RULE or MEMORY using LLM.
 * - RULES: Create UserTask for approval before storing
 * - MEMORIES: Store directly using KnowledgeManagementService
 *
 * Uses Knowledge Engine infrastructure for proper tagging and indexing.
 */
@Service
class KnowledgeStoreTool(
    private val knowledgeManagementService: KnowledgeManagementService,
    private val knowledgeClassifier: KnowledgeClassifierService,
    private val userTaskService: UserTaskService,
    private val textChunkingService: TextChunkingService,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_STORE_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "KNOWLEDGE_STORE_START: Intelligent classification and storage (${taskDescription.length} chars)" }

        if (taskDescription.isBlank()) {
            return ToolResult.error("Content cannot be blank")
        }

        // Step 1: Classify knowledge using LLM
        val classification =
            knowledgeClassifier
                .classifyKnowledge(taskDescription, plan.correlationId)
                .getOrElse { error ->
                    logger.error { "Failed to classify knowledge: ${error.message}" }
                    return ToolResult.error("Classification failed: ${error.message}")
                }

        logger.info {
            "Knowledge classified: type=${classification.type}, severity=${classification.severity}, " +
                "tags=${classification.tags}, reasoning=${classification.reasoning}"
        }

        // Step 2: Handle based on type
        return when (classification.toKnowledgeType()) {
            KnowledgeType.RULE -> handleRuleStorage(taskDescription, classification, plan)
            KnowledgeType.MEMORY -> handleMemoryStorage(taskDescription, classification, plan)
        }
    }

    /**
     * RULE: Create UserTask for approval before storing
     */
    private suspend fun handleRuleStorage(
        content: String,
        classification: KnowledgeClassifierService.KnowledgeClassification,
        plan: Plan,
    ): ToolResult {
        logger.info { "Handling RULE: Creating UserTask for approval" }

        // Create metadata for approval
        val metadata =
            mapOf(
                "knowledgeType" to classification.type,
                "knowledgeSeverity" to classification.severity,
                "knowledgeTags" to classification.tags.joinToString(","),
                "knowledgeText" to content,
                "reasoning" to classification.reasoning,
            )

        // Create UserTask
        val task =
            userTaskService.createTask(
                title = "Schválit nové pravidlo",
                description =
                    buildString {
                        appendLine("**Navrhované pravidlo:**")
                        appendLine(content)
                        appendLine()
                        appendLine("**Klasifikace:**")
                        appendLine("- Typ: ${classification.type}")
                        appendLine("- Přísnost: ${classification.severity}")
                        appendLine("- Tagy: ${classification.tags.joinToString(", ")}")
                        appendLine()
                        appendLine("**Zdůvodnění:** ${classification.reasoning}")
                    },
                priority = TaskPriorityEnum.HIGH,
                clientId = plan.clientDocument.id,
                projectId = plan.projectDocument?.id,
                sourceType = TaskSourceType.KNOWLEDGE_APPROVAL,
                metadata = metadata,
            )

        logger.info { "UserTask created for rule approval: taskId=${task.id}" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Rule pending approval (task ${task.id})",
            content =
                buildString {
                    appendLine("Detected RULE - requires user approval")
                    appendLine("Created approval task: ${task.id}")
                    appendLine()
                    appendLine("Classification:")
                    appendLine("  Type: ${classification.type}")
                    appendLine("  Severity: ${classification.severity}")
                    appendLine("  Tags: ${classification.tags.joinToString(", ")}")
                    appendLine()
                    appendLine("User will need to approve this rule before it becomes active.")
                },
        )
    }

    /**
     * MEMORY: Store directly using KnowledgeManagementService
     */
    private suspend fun handleMemoryStorage(
        content: String,
        classification: KnowledgeClassifierService.KnowledgeClassification,
        plan: Plan,
    ): ToolResult {
        logger.info { "Handling MEMORY: Storing directly" }

        val stored =
            knowledgeManagementService
                .storeKnowledge(
                    text = content,
                    type = classification.toKnowledgeType(),
                    severity = classification.toKnowledgeSeverity(),
                    tags = classification.tags,
                    clientId = plan.clientDocument.id,
                    projectId = plan.projectDocument?.id,
                    correlationId = plan.correlationId,
                ).getOrElse { error ->
                    logger.error { "Failed to store memory: ${error.message}" }
                    return ToolResult.error("Failed to store memory: ${error.message}")
                }

        logger.info { "Memory stored successfully: knowledgeId=${stored.knowledgeId}" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Memory stored (ID: ${stored.knowledgeId})",
            content =
                buildString {
                    appendLine("Successfully stored MEMORY")
                    appendLine("  Knowledge ID: ${stored.knowledgeId}")
                    appendLine("  Vector Store ID: ${stored.vectorStoreId}")
                    appendLine("  Tags: ${stored.tags.joinToString(", ")}")
                    appendLine()
                    appendLine("Classification reasoning: ${classification.reasoning}")
                },
        )
    }
}
