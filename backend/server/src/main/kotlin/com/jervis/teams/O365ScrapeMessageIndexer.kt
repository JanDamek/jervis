package com.jervis.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for browser-scraped O365 messages.
 *
 * Reads `o365_scrape_messages` collection (state=NEW), creates TaskDocuments,
 * marks messages as PROCESSED. Same pattern as TeamsContinuousIndexer but
 * for VLM/DOM-scraped content (browser pool → MongoDB → here).
 *
 * Messages are grouped by chatName (topic consolidation) — multiple messages
 * from the same chat append to one TaskDocument instead of creating many tasks.
 */
@Service
@Order(14)
class O365ScrapeMessageIndexer(
    private val repository: O365ScrapeMessageRepository,
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30s
        private const val BATCH_SIZE = 50
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting O365ScrapeMessageIndexer (browser pool scrape → tasks)..." }
        scope.launch {
            // Initial delay — let other services start first
            delay(10_000)
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "O365ScrapeMessageIndexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        while (true) {
            try {
                val processed = processBatch()
                if (processed == 0) {
                    delay(POLL_DELAY_MS)
                }
            } catch (e: Exception) {
                logger.error(e) { "Error in O365ScrapeMessageIndexer batch" }
                delay(POLL_DELAY_MS)
            }
        }
    }

    private suspend fun processBatch(): Int {
        // Query NEW messages directly from MongoDB (repository doesn't have a general findByState)
        val query = Query(Criteria.where("state").`is`("NEW")).limit(BATCH_SIZE)
        val messages = mongoTemplate.find(query, O365ScrapeMessageDocument::class.java)
            .collectList()
            .awaitSingle()
        if (messages.isEmpty()) return 0

        // Group by connectionId + chatName for topic consolidation
        val groups = messages.groupBy { "${it.connectionId}::${it.chatName ?: "unknown"}" }

        groups.forEach { (key, groupMessages) ->
            try {
                indexMessageGroup(groupMessages)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index scrape message group: $key" }
                groupMessages.forEach { msg -> markAsState(msg, "FAILED") }
            }
        }

        return messages.size
    }

    private suspend fun indexMessageGroup(messages: List<O365ScrapeMessageDocument>) {
        val first = messages.first()
        val chatName = first.chatName ?: "Unknown chat"
        val connectionId = first.connectionId
        val clientId = first.clientId

        // Self-tail short-circuit: if every newly observed message in this
        // batch is authored by the logged-in user (the agent flagged
        // isSelf=true on each), the chat does NOT need a follow-up
        // qualification — the user has already replied. Mark the rows
        // PROCESSED so they don't loop, but skip TaskDocument creation
        // entirely. KB ingest still happens via the existing meeting/
        // chat indexers; we just don't escalate to a USER_TASK.
        val sortedNewest = messages.sortedByDescending { it.timestamp }
        if (sortedNewest.all { it.isSelf }) {
            messages.forEach { markAsState(it, "PROCESSED") }
            logger.info {
                "Skipped task creation for chat=$chatName (${messages.size} self-only messages, no reply needed)"
            }
            return
        }

        val content = buildString {
            appendLine("# Teams Chat: $chatName")
            appendLine()
            for (msg in messages.sortedBy { it.timestamp }) {
                val sender = msg.sender ?: "?"
                val time = msg.timestamp ?: ""
                val text = msg.content ?: ""
                val selfTag = if (msg.isSelf) " [you]" else ""
                appendLine("**$sender**$selfTag ($time):")
                appendLine(text)
                appendLine()
            }
            appendLine("---")
            appendLine("Source: browser-scrape, connection=$connectionId")
        }

        val taskName = when (first.messageType) {
            "email" -> "Email: $chatName"
            "calendar" -> "Calendar: $chatName"
            else -> "Teams: $chatName"
        }.take(120)

        val topicId = "teams-scrape:$connectionId:${chatName.lowercase().replace(" ", "_").take(50)}"

        // Topic merge: append to existing active task for same chat
        val activeStates = listOf(
            TaskStateEnum.NEW, TaskStateEnum.INDEXING, TaskStateEnum.QUEUED,
            TaskStateEnum.PROCESSING, TaskStateEnum.USER_TASK, TaskStateEnum.BLOCKED,
        )
        val existing = taskRepository.findFirstByTopicIdAndStateIn(topicId, activeStates)

        if (existing != null) {
            val now = java.time.Instant.now()
            val updated = existing.copy(
                content = "${existing.content}\n\n---\n\n$content",
                lastActivityAt = now,
                needsQualification = true,
                taskName = taskName,
            )
            taskRepository.save(updated)
            messages.forEach { markAsState(it, "PROCESSED") }
            logger.debug { "Appended ${messages.size} scrape messages to existing task ${existing.id} (topic=$topicId)" }
            return
        }

        // Create new task
        val sourceUrn = SourceUrn("teams::conn:$connectionId,scrape:$chatName")
        val newTask = taskService.createTask(
            taskType = TaskTypeEnum.SYSTEM,
            content = content,
            clientId = ClientId(ObjectId(clientId)),
            correlationId = "teams-scrape:${first.messageHash}",
            sourceUrn = sourceUrn,
            taskName = taskName,
        )

        // Set topic ID for future message consolidation
        taskService.setTopicId(newTask.id, topicId)

        messages.forEach { markAsState(it, "PROCESSED") }
        logger.info { "Created task from ${messages.size} scrape messages: $taskName (topic=$topicId)" }
    }

    private suspend fun markAsState(doc: O365ScrapeMessageDocument, newState: String) {
        val update = Update().set("state", newState)
        mongoTemplate.updateFirst(
            Query(Criteria.where("_id").`is`(doc.id)),
            update,
            O365ScrapeMessageDocument::class.java,
        ).awaitSingle()
    }
}
