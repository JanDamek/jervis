package com.jervis.koog.tools.qualifier

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.dto.TaskStateEnum
import com.jervis.entity.TaskDocument
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId

/**
 * Qualification routing and link delegation tools.
 * Used by KoogQualifierAgent to make routing decisions and delegate async link processing.
 */
@LLMDescription("Qualification routing (DONE/LIFT_UP) and link delegation for background processing")
class QualifierRoutingTools(
    private val task: TaskDocument,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @Serializable
    enum class RoutingDecision { DONE, LIFT_UP, USER_TASK, NONE }

    @Tool
    @LLMDescription(
        """Finalize qualification by routing the task. This should be one of the last calls.

Routing Options:
- DONE: All information indexed, no further actions needed, task is complete
- LIFT_UP: Requires complex analysis, coding, or user response from main GPU agent
- USER_TASK: Requires human interaction (info, decision, approval) before proceeding. 
            NOTE: Only use this if you cannot proceed with indexing due to missing critical info.
- NONE: No routing action (used for internal ingestion)""",
    )
    suspend fun routeTask(
        @LLMDescription("Routing decision. Allowed: DONE, LIFT_UP, USER_TASK, NONE")
        routing: RoutingDecision,
        @LLMDescription("Reason for routing, especially for USER_TASK or LIFT_UP")
        reason: String? = null
    ): String {
        when (routing) {
            RoutingDecision.LIFT_UP -> taskService.updateState(task, TaskStateEnum.READY_FOR_GPU)
            RoutingDecision.USER_TASK -> {
                val escalationReason = reason ?: "Qualifier requested user interaction"
                // Mark as error with reason, BackgroundEngine will escalate to UserTask
                taskService.markAsError(task, escalationReason)
            }
            RoutingDecision.DONE -> taskService.deleteTask(task)
            RoutingDecision.NONE -> {}
        }
        return "OK-$routing"
    }

    @Tool
    @LLMDescription(
        """HIGHLY RESTRICTED AND DANGEROUS TOOL.
Asynchronously download content from URL and create new task for analysis.

MANDATORY SAFETY RULES:
1. ONLY USE FOR SAFE, CONTENT-RICH LINKS: documentation, articles, knowledge bases, code repositories
2. NEVER USE FOR ACTION LINKS: unsubscribe, confirm participation, delete account, login
3. NEVER USE FOR TRACKING/MONITORING LINKS: tracking pixels, monitoring endpoints
4. WHEN IN DOUBT, DO NOT USE: Add link to knowledge graph with REFERENCES_URL relationship instead

Background processing: Returns immediately, download happens asynchronously""",
    )
    fun delegateLinkProcessing(
        @LLMDescription("Safe URL pointing to static, content-rich text")
        url: String,
    ): DelegationResult {
        val lowerUrl = url.lowercase()
        val forbiddenKeywords =
            listOf("unsubscribe", "confirm", "accept", "decline", "login", "logout", "delete", "remove")

        if (forbiddenKeywords.any { lowerUrl.contains(it) }) {
            logger.warn { "SAFETY_VIOLATION: URL contains forbidden keyword: $url" }
            return DelegationResult(
                success = false,
                url = url,
                message = "SAFETY VIOLATION: URL contains a forbidden keyword. Cannot process action links.",
            )
        }

        scope.launch {
            try {
                // Check if link already indexed
                if (indexedLinkService.isLinkIndexed(url, task.clientId)) {
                    val existingLink = indexedLinkService.getIndexedLink(url, task.clientId)
                    logger.info { "Link $url already indexed at ${existingLink?.lastIndexedAt}, skipping" }
                    return@launch
                }

                // Detect known service (Confluence, Jira, GitHub)
                val knownService = indexedLinkService.detectKnownService(url)

                // Check if client has connection for this service
                val connection =
                    if (knownService != null) {
                        connectionService.findValidConnectionByDomain(knownService.domain)
                    } else {
                        null
                    }

                // If known service with connection, enqueue for ContinuousIndexer
                if (knownService != null && connection != null) {
                    val enqueued =
                        when (knownService.serviceType) {
                            "BUGTRACKER" -> {
                                indexedLinkService.enqueueJiraIssueFromLink(
                                    issueKey = knownService.identifier,
                                    connection = connection,
                                    clientId = task.clientId,
                                    projectId = task.projectId,
                                )
                            }

                            "WIKI" -> {
                                indexedLinkService.enqueueConfluencePageFromLink(
                                    pageId = knownService.identifier,
                                    connection = connection,
                                    clientId = task.clientId,
                                    projectId = task.projectId,
                                )
                            }

                            else -> {
                                false
                            }
                        }

                    if (enqueued) {
                        val correlationId =
                            when (knownService.serviceType) {
                                "BUGTRACKER" -> "bugtracker:${knownService.identifier}"
                                "WIKI" -> "wiki:${knownService.identifier}"
                                else -> "link:${ObjectId()}"
                            }

                        indexedLinkService.recordIndexedLink(
                            url = url,
                            clientId = task.clientId,
                            correlationId = correlationId,
                            taskId = null,
                        )

                        logger.info { "✓ Enqueued ${knownService.serviceType} ${knownService.identifier} for API indexing: $url" }
                    } else {
                        logger.info { "${knownService.serviceType} ${knownService.identifier} already indexed: $url" }
                    }
                    return@launch
                }

                // Default: fetch HTML content
                val content = linkContentService.fetchPlainText(url)
                if (content.success) {
                    val correlationId = "link:${ObjectId()}"

                    val enrichedContent =
                        if (knownService != null) {
                            buildString {
                                appendLine("--- KNOWN SERVICE DETECTED (NO CONNECTION) ---")
                                appendLine("Service Type: ${knownService.serviceType}")
                                appendLine("Identifier: ${knownService.identifier}")
                                appendLine("Domain: ${knownService.domain}")
                                appendLine("URL: $url")
                                appendLine("Note: Client does not have connection for this service, using HTML content.")
                                appendLine("--- ORIGINAL CONTENT ---")
                                appendLine()
                                append(content.plainText)
                            }
                        } else {
                            content.plainText
                        }

                    val newTask =
                        taskService.createTask(
                            taskType = com.jervis.dto.TaskTypeEnum.LINK_PROCESSING,
                            content = enrichedContent,
                            clientId = task.clientId,
                            projectId = task.projectId,
                            sourceUrn =
                                com.jervis.types.SourceUrn
                                    .link(url),
                            correlationId = correlationId,
                        )

                    indexedLinkService.recordIndexedLink(
                        url = url,
                        clientId = task.clientId,
                        correlationId = correlationId,
                        taskId = newTask.id,
                    )

                    logger.info { "✓ Created new task from downloaded link: $url" }
                } else {
                    logger.warn { "Downloaded content from $url is blank, skipping" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to download or process link $url" }
            }
        }

        return DelegationResult(
            success = true,
            url = url,
            message = "✓ Link processing delegated to background task: $url",
        )
    }
}

@Serializable
data class DelegationResult(
    val success: Boolean,
    val url: String,
    val message: String,
)
