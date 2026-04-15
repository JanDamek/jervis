package com.jervis.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.TaskId
import com.jervis.dto.task.PagedPendingTasksResult
import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.TaskDocument
import com.jervis.task.toPendingTaskDto
import com.jervis.task.TaskRepository
import com.jervis.service.task.IPendingTaskService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

@Service
class PendingTaskService(
    private val taskRepository: TaskRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
    @org.springframework.context.annotation.Lazy
    private val chatRpcImpl: com.jervis.chat.ChatRpcImpl,
    private val pythonOrchestratorClient: com.jervis.agent.PythonOrchestratorClient,
) : IPendingTaskService {
    private val logger = mu.KotlinLogging.logger {}
    override suspend fun listTasks(
        taskType: String?,
        state: String?,
        clientId: String?,
    ): List<PendingTaskDto> {
        // Use MongoDB template for consistent clientId filtering
        val criteria = Criteria()
        taskType?.let { criteria.and("type").`is`(it) }
        state?.let { criteria.and("state").`is`(it) }
        clientId?.takeIf { it.isNotBlank() }?.let {
            criteria.and("clientId").`is`(ObjectId(it))
        }
        val query = Query(criteria).with(Sort.by(Sort.Direction.ASC, "createdAt"))
        val tasks = mongoTemplate.find(query, TaskDocument::class.java, "tasks")
            .collectList().awaitSingle()
        return tasks.map { it.toPendingTaskDto() }
    }

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
        clientId: String?,
    ): Long {
        val criteria = Criteria()
        taskType?.let { criteria.and("type").`is`(it) }
        state?.let { criteria.and("state").`is`(it) }
        clientId?.takeIf { it.isNotBlank() }?.let {
            criteria.and("clientId").`is`(ObjectId(it))
        }
        return mongoTemplate.count(Query(criteria), "tasks").awaitSingle()
    }

    override suspend fun listTasksPaged(
        taskType: String?,
        state: String?,
        page: Int,
        pageSize: Int,
        clientId: String?,
        sourceScheme: String?,
        parentTaskId: String?,
        textQuery: String?,
    ): PagedPendingTasksResult {
        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, 100)

        val criteria = Criteria()
        taskType?.let { criteria.and("type").`is`(it) }
        state?.let { criteria.and("state").`is`(it) }
        clientId?.takeIf { it.isNotBlank() }?.let {
            criteria.and("clientId").`is`(ObjectId(it))
        }
        // Phase 4: filter by sourceUrn scheme prefix (e.g. "email::", "whatsapp::").
        // Stored sourceUrn is "scheme::key:val,...", so a prefix regex matches it.
        sourceScheme?.takeIf { it.isNotBlank() }?.let {
            val escaped = java.util.regex.Pattern.quote(it)
            criteria.and("sourceUrn").regex("^$escaped::")
        }
        // Phase 4: drill into a parent's sub-tasks. Empty string ⇒ no filter.
        parentTaskId?.takeIf { it.isNotBlank() }?.let {
            criteria.and("parentTaskId").`is`(ObjectId(it))
        }
        // Phase 4: substring match on taskName + content. Used by the search box.
        textQuery?.takeIf { it.isNotBlank() }?.let { q ->
            val escaped = java.util.regex.Pattern.quote(q)
            criteria.orOperator(
                Criteria.where("taskName").regex(escaped, "i"),
                Criteria.where("content").regex(escaped, "i"),
            )
        }

        val baseQuery = Query(criteria)

        // Count total (lightweight)
        val totalCount = mongoTemplate.count(baseQuery, "tasks").awaitSingle()

        // Paginated fetch via aggregation — $ifNull coerces missing deadline to a
        // far-future sentinel so ASC sort places tasks WITH deadline first and null
        // deadlines at the bottom (MongoDB ASC sort would otherwise put nulls first).
        // Secondary sort: priorityScore DESC, createdAt DESC — legacy/batch tasks
        // keep familiar "newest first" ordering within the null bucket.
        val farFuture = java.util.Date.from(java.time.Instant.parse("9999-12-31T23:59:59Z"))
        val aggregation = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
            org.springframework.data.mongodb.core.aggregation.Aggregation.match(criteria),
            org.springframework.data.mongodb.core.aggregation.Aggregation.addFields()
                .addField("_deadlineSort")
                .withValue(org.bson.Document("\$ifNull", listOf("\$deadline", farFuture)))
                .build(),
            org.springframework.data.mongodb.core.aggregation.Aggregation.sort(
                Sort.by(
                    Sort.Order.asc("_deadlineSort"),
                    Sort.Order.desc("priorityScore"),
                    Sort.Order.desc("createdAt"),
                ),
            ),
            org.springframework.data.mongodb.core.aggregation.Aggregation.skip((safePage * safePageSize).toLong()),
            org.springframework.data.mongodb.core.aggregation.Aggregation.limit(safePageSize.toLong()),
        )

        val tasks = mongoTemplate
            .aggregate(aggregation, "tasks", TaskDocument::class.java)
            .collectList().awaitSingle()

        return PagedPendingTasksResult(
            items = tasks.map { it.toPendingTaskDto() },
            totalCount = totalCount,
            page = safePage,
            pageSize = safePageSize,
        )
    }

    override suspend fun getById(id: String): PendingTaskDto? {
        val taskId = TaskId.fromString(id)
        val task = taskRepository.getById(taskId) ?: return null
        // Look up child counts for hierarchy display.
        val childCount = taskRepository.countByParentTaskIdAndStateNot(taskId, TaskStateEnum.DONE)
        // (completedChildCount handled by listChildren when the user expands the section)
        return task.toPendingTaskDto(
            childCount = (childCount + 0).toInt(),
            completedChildCount = 0,
        )
    }

    override suspend fun listChildren(parentTaskId: String): List<PendingTaskDto> {
        val pid = TaskId.fromString(parentTaskId)
        return taskRepository.findByParentTaskId(pid)
            .toList()
            .sortedWith(compareBy({ it.phase ?: "" }, { it.orderInPhase }))
            .map { it.toPendingTaskDto() }
    }

    override suspend fun deletePendingTask(id: String) {
        val taskId = TaskId.fromString(id)
        taskRepository.deleteById(taskId)
    }

    override suspend fun markDone(id: String, note: String?): PendingTaskDto? {
        val taskId = TaskId.fromString(id)
        val task = taskRepository.getById(taskId) ?: return null

        // Cancel running orchestration if task is actively being processed
        val threadId = task.orchestratorThreadId
        if (threadId != null && task.state in setOf(TaskStateEnum.PROCESSING, TaskStateEnum.CODING, TaskStateEnum.QUEUED)) {
            try {
                pythonOrchestratorClient.cancelOrchestration(threadId)
                logger.info { "TASK_CANCEL_ON_DONE: taskId=$id threadId=$threadId" }
            } catch (e: Exception) {
                logger.warn(e) { "TASK_CANCEL_ON_DONE_FAILED: taskId=$id threadId=$threadId" }
            }
        }

        val now = java.time.Instant.now()
        val newContent = if (note.isNullOrBlank()) {
            task.content
        } else {
            "${task.content}\n\n[Hotovo $now] $note"
        }
        val updated = task.copy(
            state = TaskStateEnum.DONE,
            content = newContent,
            lastActivityAt = now,
            needsQualification = false,
            pendingUserQuestion = null,
            userQuestionContext = null,
        )
        val saved = taskRepository.save(updated)
        // Stream-based sidebar push — no polling
        try { chatRpcImpl.emitTaskListChanged(id, "DONE") } catch (_: Exception) {}
        return saved.toPendingTaskDto()
    }

    override suspend fun listRelatedTasks(taskId: String): List<PendingTaskDto> {
        val tid = TaskId.fromString(taskId)
        val task = taskRepository.getById(tid) ?: return emptyList()
        val entities = task.kbEntities.take(10)
        if (entities.isEmpty()) return emptyList()

        // Find tasks sharing at least one kbEntity — this is the MongoDB equivalent
        // of "find tasks connected to the same KB graph nodes". Uses the structured
        // kbEntities array (normalized entity keys from KB extraction), NOT regex
        // on free-text content. Consistent with the project rule: KB semantic
        // matching for relationships, not regex.
        val query = Query(
            Criteria.where("kbEntities").`in`(entities)
                .and("_id").ne(ObjectId(taskId)),
        ).with(Sort.by(Sort.Direction.DESC, "lastActivityAt", "createdAt"))
            .limit(10)
        val matches = mongoTemplate.find(query, TaskDocument::class.java, "tasks")
            .collectList().awaitSingle()
        return matches.map { it.toPendingTaskDto() }
    }

    override suspend fun reopen(id: String, note: String?): PendingTaskDto? {
        val taskId = TaskId.fromString(id)
        val task = taskRepository.getById(taskId) ?: return null
        val now = java.time.Instant.now()
        val newContent = if (note.isNullOrBlank()) {
            task.content
        } else {
            "${task.content}\n\n[Reopen $now] $note"
        }
        val updated = task.copy(
            state = TaskStateEnum.NEW,
            content = newContent,
            lastActivityAt = now,
            needsQualification = true,
        )
        val saved = taskRepository.save(updated)
        // Stream-based sidebar push — no polling
        try { chatRpcImpl.emitTaskListChanged(id, "NEW") } catch (_: Exception) {}
        return saved.toPendingTaskDto()
    }
}
