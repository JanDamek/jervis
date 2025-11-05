package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.task.TaskPriority
import com.jervis.domain.task.TaskSourceType
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.task.UserTaskService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Creates a user-facing task. For EMAIL-based tasks we enforce mandatory identifiers
 * so the system knows exactly which email/thread to respond to after approval.
 *
 * Required for EMAIL sourceType:
 * - sourceUri must be email://<accountId>/<messageId>
 * - metadata.threadId must be present (hex string)
 * - We enrich metadata with emailAccountId and emailMessageId if missing
 */

@Service
class TaskCreateUserTaskTool(
    private val userTaskService: UserTaskService,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.TASK_CREATE_USER_TASK_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        try {
            logger.info { "Creating user task from: $taskDescription" }

            val request = Json.decodeFromString<CreateUserTaskRequest>(taskDescription)

            val priority =
                request.priority?.let {
                    runCatching { TaskPriority.valueOf(it.uppercase()) }.getOrNull()
                } ?: TaskPriority.MEDIUM

            val sourceType =
                runCatching { TaskSourceType.valueOf(request.sourceType.uppercase()) }
                    .getOrElse { TaskSourceType.AGENT_SUGGESTION }

            val dueDate =
                request.dueDate?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                }

            val enrichedMetadata = request.metadata.toMutableMap()
            if (sourceType == TaskSourceType.EMAIL) {
                val uri = request.sourceUri
                require(!uri.isNullOrBlank()) { "For sourceType=EMAIL, sourceUri must be provided as email://<accountId>/<messageId>" }
                require(uri.startsWith("email://")) { "For sourceType=EMAIL, sourceUri must start with 'email://'" }
                val parts = uri.removePrefix("email://").split("/")
                require(parts.size >= 2) { "Invalid email sourceUri. Expected format: email://<accountId>/<messageId>" }
                val accountIdHex = parts[0]
                val messageId = parts[1]
                // enrich if absent
                enrichedMetadata.putIfAbsent("emailAccountId", accountIdHex)
                enrichedMetadata.putIfAbsent("emailMessageId", messageId)
                // require threadId for auto-resolution and correct routing
                require(enrichedMetadata.containsKey("threadId")) {
                    "metadata.threadId is required for EMAIL tasks so the system can auto-resolve when the conversation is answered"
                }
            }

            // Compose a comprehensive description with clear user action and full context
            val combinedDescription =
                buildString {
                    appendLine("Action: ${request.title}")
                    request.description?.takeIf { it.isNotBlank() }?.let {
                        appendLine()
                        appendLine(it)
                    }
                    appendLine()
                    appendLine("Source: $sourceType")
                    request.sourceUri?.let { appendLine("Source URI: $it") }
                    if (stepContext.isNotBlank()) {
                        appendLine()
                        appendLine("Analysis Context:")
                        appendLine(stepContext)
                    }
                    // Email context (if provided)
                    val emailAccountId = enrichedMetadata["emailAccountId"]
                    val emailMessageId = enrichedMetadata["emailMessageId"]
                    val threadId = enrichedMetadata["threadId"]
                    if (emailAccountId != null || emailMessageId != null || threadId != null) {
                        appendLine()
                        appendLine("Email Context:")
                        emailAccountId?.let { appendLine("- Account: $it") }
                        emailMessageId?.let { appendLine("- Message ID: $it") }
                        threadId?.let { appendLine("- Thread ID: $it") }
                        enrichedMetadata["emailFrom"]?.let { appendLine("- From: $it") }
                        enrichedMetadata["emailTo"]?.let { appendLine("- To: $it") }
                        enrichedMetadata["emailSubject"]?.let { appendLine("- Subject: $it") }
                    }
                    // Git context (if provided)
                    val commitHash = enrichedMetadata["commitHash"]
                    val commitMessage = enrichedMetadata["commitMessage"] ?: enrichedMetadata["message"]
                    val commitAuthor = enrichedMetadata["author"]
                    val commitBranch = enrichedMetadata["branch"]
                    val additions = enrichedMetadata["additions"]
                    val deletions = enrichedMetadata["deletions"]
                    if (commitHash != null || commitMessage != null || commitAuthor != null) {
                        appendLine()
                        appendLine("Git Commit:")
                        commitHash?.let { appendLine("- Hash: $it") }
                        commitAuthor?.let { appendLine("- Author: $it") }
                        commitBranch?.let { appendLine("- Branch: $it") }
                        commitMessage?.let { appendLine("- Message: $it") }
                        if (additions != null || deletions != null) {
                            val addStr = additions ?: "?"
                            val delStr = deletions ?: "?"
                            appendLine("- Changes: +$addStr/-$delStr")
                        }
                        enrichedMetadata["changedFiles"]?.let { files ->
                            appendLine("- Changed Files:")
                            files
                                .split('\n', ',', ';')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .take(50)
                                .forEach { f ->
                                    appendLine("  - $f")
                                }
                        }
                    }
                    // Any attachments summary if supplied
                    enrichedMetadata["attachments"]?.let { at ->
                        appendLine()
                        appendLine("Attachments:")
                        at.lines().forEach { appendLine("- $it") }
                    }
                    // Append full metadata at the end for completeness
                    if (enrichedMetadata.isNotEmpty()) {
                        appendLine()
                        appendLine("Metadata:")
                        for (entry in enrichedMetadata.entries.sortedBy { it.key }) {
                            appendLine("- ${entry.key}=${entry.value}")
                        }
                    }
                }

            val task =
                userTaskService.createTask(
                    title = request.title,
                    description = combinedDescription,
                    priority = priority,
                    dueDate = dueDate,
                    projectId = plan.projectId,
                    clientId = plan.clientId,
                    sourceType = sourceType,
                    sourceUri = request.sourceUri,
                    metadata = enrichedMetadata,
                )

            ToolResult.success(
                toolName = name.name,
                summary = "Created user task: ${task.title} (priority: ${task.priority})",
                content =
                    buildString {
                        appendLine("TASK_ID: ${task.id.toHexString()}")
                        appendLine("TITLE: ${task.title}")
                        task.description?.let { appendLine("DESCRIPTION: $it") }
                        appendLine("PRIORITY: ${task.priority}")
                        task.dueDate?.let { appendLine("DUE: $it") }
                        appendLine("SOURCE: ${task.sourceType}")
                        task.sourceUri?.let { appendLine("SOURCE_URI: $it") }
                    },
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create user task" }
            ToolResult.error(
                output = "Failed to create user task: ${e.message}",
                message = e.message,
            )
        }

    @Serializable
    data class CreateUserTaskRequest(
        val title: String,
        val description: String? = null,
        val priority: String? = null,
        val dueDate: String? = null,
        val sourceType: String,
        val sourceUri: String? = null,
        val metadata: Map<String, String> = emptyMap(),
    )
}
