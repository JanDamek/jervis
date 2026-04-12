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
) : IPendingTaskService {
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

        // Paginated fetch — newest first so the user sees fresh tasks at the top.
        val pagedQuery = Query(criteria)
            .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .skip((safePage * safePageSize).toLong())
            .limit(safePageSize)

        val tasks = mongoTemplate.find(pagedQuery, TaskDocument::class.java, "tasks")
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
        return saved.toPendingTaskDto()
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
        // Reopen → NEW + needsQualification=true so the re-entrant qualifier
        // picks it up and decides what to do with the (possibly outdated) task.
        val updated = task.copy(
            state = TaskStateEnum.NEW,
            content = newContent,
            lastActivityAt = now,
            needsQualification = true,
        )
        val saved = taskRepository.save(updated)
        return saved.toPendingTaskDto()
    }
}
