package com.jervis.task

import com.jervis.common.types.TaskId
import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.TaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Push-only per-task stream. Used by the chat breadcrumb / task brief — the
 * UI subscribes when a task is opened and receives fresh snapshots on every
 * state change. Replaces the stale `getById(taskId)` call that caused the
 * "Otevřít znovu" button to mismatch the real live state.
 */
@Service
class TaskStreamService(
    private val taskRepository: TaskRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val flows = ConcurrentHashMap<String, MutableSharedFlow<TaskSnapshot>>()

    fun subscribe(taskId: String): Flow<TaskSnapshot> {
        if (taskId.isBlank()) return emptyFlow()
        val flow = flows.computeIfAbsent(taskId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
        }
        return flow.onSubscription {
            if (flow.replayCache.isEmpty()) {
                val snap = buildSnapshot(taskId) ?: return@onSubscription
                emit(snap)
            }
        }
    }

    /** Called from service-layer write paths (task save / markDone / reopen / progress). */
    fun invalidate(taskId: String) {
        val flow = flows[taskId] ?: return // no subscriber → skip work
        scope.launch {
            try {
                val snap = buildSnapshot(taskId) ?: return@launch
                flow.emit(snap)
            } catch (e: Exception) {
                logger.warn(e) { "TASK_STREAM_INVALIDATE_FAILED: taskId=$taskId" }
            }
        }
    }

    private suspend fun buildSnapshot(taskId: String): TaskSnapshot? {
        val tid = try { TaskId.fromString(taskId) } catch (_: Exception) { return null }
        val task = taskRepository.getById(tid) ?: return null
        val dto = task.toPendingTaskDto()
        val related = buildRelatedTasks(taskId, task.kbEntities)
        return TaskSnapshot(task = dto, relatedTasks = related)
    }

    private suspend fun buildRelatedTasks(taskId: String, entities: List<String>): List<PendingTaskDto> {
        val limited = entities.take(10)
        if (limited.isEmpty()) return emptyList()
        val query = Query(
            Criteria.where("kbEntities").`in`(limited)
                .and("_id").ne(ObjectId(taskId)),
        ).with(Sort.by(Sort.Direction.DESC, "lastActivityAt", "createdAt"))
            .limit(10)
        return mongoTemplate.find(query, TaskDocument::class.java, "tasks")
            .collectList()
            .awaitSingle()
            .map { it.toPendingTaskDto() }
    }
}
