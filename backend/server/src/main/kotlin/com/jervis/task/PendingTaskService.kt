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
    ): PagedPendingTasksResult {
        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, 100)

        val criteria = Criteria()
        taskType?.let { criteria.and("type").`is`(it) }
        state?.let { criteria.and("state").`is`(it) }
        clientId?.takeIf { it.isNotBlank() }?.let {
            criteria.and("clientId").`is`(ObjectId(it))
        }

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
        val taskId = TaskId.fromString(id)
        taskRepository.deleteById(taskId)
    }
}
