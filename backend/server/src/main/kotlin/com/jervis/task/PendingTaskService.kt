package com.jervis.task

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
    ): List<PendingTaskDto> {
        val parsedType = taskType?.let { TaskTypeEnum.valueOf(it) }
        val parsedState = state?.let { TaskStateEnum.valueOf(it) }

        val tasks = when {
            parsedType != null && parsedState != null ->
                taskRepository.findByTypeAndStateOrderByCreatedAtAsc(parsedType, parsedState)
            parsedType != null ->
                taskRepository.findByTypeOrderByCreatedAtAsc(parsedType)
            parsedState != null ->
                taskRepository.findByStateOrderByCreatedAtAsc(parsedState)
            else ->
                taskRepository.findAllByOrderByCreatedAtAsc()
        }

        return tasks.map { it.toPendingTaskDto() }.toList()
    }

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
    ): Long {
        val parsedType = taskType?.let { TaskTypeEnum.valueOf(it) }
        val parsedState = state?.let { TaskStateEnum.valueOf(it) }

        return when {
            parsedType != null && parsedState != null ->
                taskRepository.countByTypeAndState(parsedType, parsedState)
            parsedType != null ->
                taskRepository.countByType(parsedType)
            parsedState != null ->
                taskRepository.countByState(parsedState)
            else ->
                taskRepository.count()
        }
    }

    override suspend fun listTasksPaged(
        taskType: String?,
        state: String?,
        page: Int,
        pageSize: Int,
    ): PagedPendingTasksResult {
        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, 100)

        val criteria = Criteria()
        taskType?.let { criteria.and("type").`is`(it) }
        state?.let { criteria.and("state").`is`(it) }

        val baseQuery = Query(criteria)

        // Count total (lightweight)
        val totalCount = mongoTemplate.count(baseQuery, "tasks").awaitSingle()

        // Paginated fetch
        val pagedQuery = Query(criteria)
            .with(Sort.by(Sort.Direction.ASC, "createdAt"))
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

    override suspend fun deletePendingTask(id: String) {
        taskRepository.deleteById(TaskId.fromString(id))
    }
}
