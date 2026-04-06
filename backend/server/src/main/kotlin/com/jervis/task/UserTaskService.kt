package com.jervis.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.TaskId
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

@Service
class UserTaskService(
    private val userTaskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun failAndEscalateToUserTask(
        task: TaskDocument,
        reason: String,
        error: Throwable? = null,
        errorMessage: String? = null,
        pendingQuestion: String? = null,
        questionContext: String? = null,
        interruptAction: String? = null,
        isApproval: Boolean = false,
    ) {
        val isError = pendingQuestion == null  // no question → it's a failure, not clarification
        val taskTypeLabel = TASK_TYPE_LABELS[task.type] ?: task.type.name
        val errorText = errorMessage ?: error?.message

        val title = when {
            pendingQuestion != null -> pendingQuestion
            errorText != null -> "$taskTypeLabel selhalo: ${errorText.take(100)}"
            else -> "$taskTypeLabel selhalo"
        }

        val description =
            buildString {
                if (pendingQuestion != null) {
                    appendLine("Dotaz agenta:")
                    appendLine(pendingQuestion)
                    appendLine()
                    if (questionContext != null) {
                        appendLine("Kontext:")
                        appendLine(questionContext)
                        appendLine()
                    }
                    appendLine("Původní úloha:")
                    appendLine(task.content)
                } else {
                    appendLine("$taskTypeLabel selhalo")
                    appendLine("Důvod: $reason")
                    errorText?.let { appendLine("Chyba: $it") }
                    appendLine()
                    appendLine("Obsah úlohy:")
                    appendLine(task.content)
                }
            }

        // Update existing task to USER_TASK and refresh its content for UI display.
        // Default priority 60 for escalated tasks (higher than auto-discovered 50, lower than urgent 70+)
        val updatedTask =
            task.copy(
                taskName = title,
                content = description,
                state = com.jervis.dto.task.TaskStateEnum.USER_TASK,
                type = com.jervis.dto.task.TaskTypeEnum.USER_TASK,
                pendingUserQuestion = pendingQuestion,
                userQuestionContext = questionContext,
                priorityScore = task.priorityScore ?: 60,
                lastActivityAt = java.time.Instant.now(),
            )
        userTaskRepository.save(updatedTask)

        // Notify client via kRPC stream
        notificationRpc.emitUserTaskCreated(
            clientId = task.clientId.toString(),
            taskId = task.id.toString(),
            title = title,
            interruptAction = interruptAction,
            interruptDescription = pendingQuestion,
            isApproval = isApproval,
            projectId = task.projectId?.toString(),
            isError = isError,
            errorDetail = if (isError) description else null,
        )

        // Send push notifications to all platforms (broadcast — first responder wins)
        val activeCount = countActiveTasksByClient(task.clientId)
        val pushTitle = when {
            isError -> "Úloha selhala"
            isApproval -> "Schválení vyžadováno"
            else -> "Úloha potřebuje odpověď"
        }
        val pushBody = when {
            isError -> "$taskTypeLabel: ${errorText?.take(80) ?: reason}"
            else -> title
        }
        val pushData = buildMap {
            put("taskId", task.id.toString())
            put("type", when {
                isError -> "error"
                isApproval -> "approval"
                else -> "user_task"
            })
            interruptAction?.let { put("interruptAction", it) }
            put("isApproval", isApproval.toString())
            put("isError", isError.toString())
            put("badgeCount", activeCount.toString())
        }

        // FCM → Android
        try {
            fcmPushService.sendPushNotification(
                clientId = task.clientId.toString(),
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        } catch (e: Exception) {
            logger.warn { "FCM push failed for task ${task.id}: ${e.message}" }
        }

        // APNs → iOS
        try {
            apnsPushService.sendPushNotification(
                clientId = task.clientId.toString(),
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        } catch (e: Exception) {
            logger.warn { "APNs push failed for task ${task.id}: ${e.message}" }
        }

        logger.info { "TASK_FAILED_ESCALATED: id=${task.id} reason=$reason isError=$isError pendingQuestion=${pendingQuestion != null}" }
    }

    /**
     * Send push notification + kRPC stream event for a newly created USER_TASK.
     * Called by AutoTaskCreationService when qualifier creates tasks that need user attention.
     */
    suspend fun notifyUserTaskCreated(task: TaskDocument) {
        // kRPC stream notification
        notificationRpc.emitUserTaskCreated(
            clientId = task.clientId.toString(),
            taskId = task.id.toString(),
            title = task.taskName ?: "Nová úloha",
            interruptAction = null,
            interruptDescription = null,
            isApproval = false,
            projectId = task.projectId?.toString(),
            isError = false,
            errorDetail = null,
        )

        val activeCount = countActiveTasksByClient(task.clientId)
        val pushTitle = "Nová úloha k reakci"
        val pushBody = task.taskName ?: "Úloha vyžaduje pozornost"
        val pushData = buildMap {
            put("taskId", task.id.toString())
            put("type", "user_task")
            put("badgeCount", activeCount.toString())
        }

        // FCM → Android
        try {
            fcmPushService.sendPushNotification(
                clientId = task.clientId.toString(),
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        } catch (e: Exception) {
            logger.warn { "FCM push failed for new USER_TASK ${task.id}: ${e.message}" }
        }

        // APNs → iOS
        try {
            apnsPushService.sendPushNotification(
                clientId = task.clientId.toString(),
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        } catch (e: Exception) {
            logger.warn { "APNs push failed for new USER_TASK ${task.id}: ${e.message}" }
        }

        logger.info { "USER_TASK_NOTIFY: id=${task.id} title=${task.taskName}" }
    }

    companion object {
        /** Human-readable Czech labels for task types. */
        val TASK_TYPE_LABELS = mapOf(
            com.jervis.dto.task.TaskTypeEnum.MEETING_PROCESSING to "Zpracování schůzky",
            com.jervis.dto.task.TaskTypeEnum.EMAIL_PROCESSING to "Zpracování emailu",
            com.jervis.dto.task.TaskTypeEnum.IDLE_REVIEW to "Pravidelný přehled",
            com.jervis.dto.task.TaskTypeEnum.BUGTRACKER_PROCESSING to "Zpracování Jira issue",
            com.jervis.dto.task.TaskTypeEnum.WIKI_PROCESSING to "Zpracování Confluence stránky",
            com.jervis.dto.task.TaskTypeEnum.GIT_PROCESSING to "Zpracování Git repozitáře",
            com.jervis.dto.task.TaskTypeEnum.LINK_PROCESSING to "Zpracování odkazu",
            com.jervis.dto.task.TaskTypeEnum.USER_INPUT_PROCESSING to "Zpracování uživatelského vstupu",
            com.jervis.dto.task.TaskTypeEnum.SCHEDULED_TASK to "Naplánovaná úloha",
            com.jervis.dto.task.TaskTypeEnum.USER_TASK to "Uživatelská úloha",
        )
    }

    /**
     * Priority-based sort for "K reakci" display:
     * 1. priorityScore DESC (highest priority first, nulls last)
     * 2. lastActivityAt DESC (recent activity first, nulls last)
     * 3. createdAt ASC (oldest tasks first within same priority)
     */
    private fun prioritySort() = Sort.by(
        Sort.Order.desc("priorityScore"),
        Sort.Order.desc("lastActivityAt"),
        Sort.Order.asc("createdAt"),
    )

    data class PagedTasks(
        val items: List<TaskDocument>,
        val totalCount: Int,
        val total: Long = totalCount.toLong(),
        val hasMore: Boolean,
    )

    suspend fun findPagedTasks(
        query: String?,
        offset: Int,
        limit: Int,
        stateFilter: com.jervis.dto.task.TaskStateEnum? = null,
    ): PagedTasks {
        val criteria = if (stateFilter != null) {
            Criteria.where("state").`is`(stateFilter.name)
        } else {
            Criteria.where("type").`is`(com.jervis.dto.task.TaskTypeEnum.USER_TASK.name)
                .and("state").`is`(com.jervis.dto.task.TaskStateEnum.USER_TASK.name)
        }

        if (!query.isNullOrBlank()) {
            val regex = ".*${Regex.escape(query)}.*"
            criteria.orOperator(
                Criteria.where("taskName").regex(regex, "i"),
                Criteria.where("content").regex(regex, "i"),
            )
        }

        val countQuery = Query(criteria)
        val totalCount = mongoTemplate.count(countQuery, TaskDocument::class.java).awaitSingle().toInt()

        val dataQuery = Query(criteria)
            .with(prioritySort())
            .skip(offset.toLong())
            .limit(limit)

        val items = mongoTemplate.find(dataQuery, TaskDocument::class.java)
            .collectList()
            .awaitSingle()

        return PagedTasks(
            items = items,
            totalCount = totalCount,
            hasMore = offset + items.size < totalCount,
        )
    }

    /**
     * Lightweight paginated query for list view.
     * Uses MongoDB $text index on taskName + content when search query is provided.
     * Falls back to regex if text index is not available.
     * Applies field projection to exclude content, attachments, agentCheckpointJson.
     */
    suspend fun findPagedTasksLightweight(
        query: String?,
        offset: Int,
        limit: Int,
    ): PagedTasks {
        val criteria = Criteria.where("type").`is`(com.jervis.dto.task.TaskTypeEnum.USER_TASK.name)
            .and("state").`is`(com.jervis.dto.task.TaskStateEnum.USER_TASK.name)

        if (!query.isNullOrBlank()) {
            // Try $text search first (requires text index on taskName + content)
            try {
                val textCriteria = org.springframework.data.mongodb.core.query.TextCriteria
                    .forDefaultLanguage()
                    .matching(query)
                val textQuery = org.springframework.data.mongodb.core.query.TextQuery.queryText(textCriteria)
                    .addCriteria(criteria)
                    .with(prioritySort())

                val totalCount = mongoTemplate.count(textQuery, TaskDocument::class.java).awaitSingle().toInt()

                val dataQuery = org.springframework.data.mongodb.core.query.TextQuery.queryText(textCriteria)
                    .addCriteria(criteria)
                    .with(prioritySort())
                    .skip(offset.toLong())
                    .limit(limit)

                // Exclude large fields
                dataQuery.fields()
                    .exclude("agentCheckpointJson")

                val items = mongoTemplate.find(dataQuery, TaskDocument::class.java)
                    .collectList()
                    .awaitSingle()

                return PagedTasks(
                    items = items,
                    totalCount = totalCount,
                    hasMore = offset + items.size < totalCount,
                )
            } catch (e: Exception) {
                // Text index may not exist — fall back to regex
                logger.debug { "Text search failed, falling back to regex: ${e.message}" }
            }

            // Regex fallback
            val regex = ".*${Regex.escape(query)}.*"
            criteria.orOperator(
                Criteria.where("taskName").regex(regex, "i"),
                Criteria.where("content").regex(regex, "i"),
            )
        }

        val countQuery = Query(criteria)
        val totalCount = mongoTemplate.count(countQuery, TaskDocument::class.java).awaitSingle().toInt()

        val dataQuery = Query(criteria)
            .with(prioritySort())
            .skip(offset.toLong())
            .limit(limit)

        // Exclude large fields from projection
        dataQuery.fields()
            .exclude("agentCheckpointJson")

        val items = mongoTemplate.find(dataQuery, TaskDocument::class.java)
            .collectList()
            .awaitSingle()

        return PagedTasks(
            items = items,
            totalCount = totalCount,
            hasMore = offset + items.size < totalCount,
        )
    }

    /**
     * Search all tasks (not just USER_TASK type). Used by chat agent's search_tasks tool.
     */
    suspend fun searchAllTasks(
        query: String?,
        offset: Int,
        limit: Int,
        stateFilter: com.jervis.dto.task.TaskStateEnum? = null,
    ): PagedTasks {
        val criteria = Criteria()

        if (stateFilter != null) {
            criteria.and("state").`is`(stateFilter.name)
        }

        if (!query.isNullOrBlank()) {
            val regex = ".*${Regex.escape(query)}.*"
            criteria.orOperator(
                Criteria.where("taskName").regex(regex, "i"),
                Criteria.where("content").regex(regex, "i"),
            )
        }

        val countQuery = Query(criteria)
        val totalCount = mongoTemplate.count(countQuery, TaskDocument::class.java).awaitSingle().toInt()

        val dataQuery = Query(criteria)
            .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .skip(offset.toLong())
            .limit(limit)

        val items = mongoTemplate.find(dataQuery, TaskDocument::class.java)
            .collectList()
            .awaitSingle()

        return PagedTasks(
            items = items,
            totalCount = totalCount,
            hasMore = offset + items.size < totalCount,
        )
    }

    suspend fun findActiveTasksByClient(clientId: ClientId): List<TaskDocument> =
        userTaskRepository.findByClientIdAndType(clientId, com.jervis.dto.task.TaskTypeEnum.USER_TASK).toList()

    suspend fun countActiveTasksByClient(clientId: ClientId): Long =
        userTaskRepository.countByStateAndTypeAndClientId(
            com.jervis.dto.task.TaskStateEnum.USER_TASK,
            com.jervis.dto.task.TaskTypeEnum.USER_TASK,
            clientId,
        )

    suspend fun cancelTask(taskId: TaskId): TaskDocument {
        val task = getTaskByIdOrNull(taskId) ?: throw IllegalArgumentException("Task not found: $taskId")
        userTaskRepository.delete(task)
        notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
        logger.info { "TASK_CANCELLED: id=$taskId" }
        return task
    }

    suspend fun getTaskById(taskId: TaskId) = getTaskByIdOrNull(taskId)

    suspend fun getTaskByIdOrNull(taskId: TaskId): TaskDocument? = userTaskRepository.getById(taskId)

    suspend fun deleteTaskById(id: TaskId) {
        getTaskByIdOrNull(id)?.let { userTaskRepository.delete(it) }
    }
}
