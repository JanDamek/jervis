package com.jervis.task

import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.SidebarSnapshot
import com.jervis.dto.task.TaskStateEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * Push-only sidebar stream. Owns a [MutableSharedFlow] per scope key.
 * [invalidate] rebuilds the snapshot and emits it to all subscribers of
 * affected scopes. UI never pulls — see `docs/guidelines.md` §9.
 */
@Service
class SidebarStreamService(
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Key format: "{clientId|""}:{active|done}"
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<SidebarSnapshot>>()

    fun subscribe(clientId: String?, showDone: Boolean): Flow<SidebarSnapshot> {
        val normalized = normalizeClientId(clientId)
        val key = scopeKey(normalized, showDone)
        val flow = flows.computeIfAbsent(key) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
        }
        return flow.onSubscription {
            if (flow.replayCache.isEmpty()) {
                emit(buildSnapshot(normalized, showDone))
            }
        }
    }

    /**
     * UI passes "__global__" as the sentinel for "no client filter". The
     * sidebar service does DB ObjectId() parsing, which throws on that value
     * — map it to null here so the snapshot covers all clients.
     */
    private fun normalizeClientId(clientId: String?): String? =
        clientId?.takeIf { it.isNotBlank() && it != "__global__" }

    /**
     * Emit a fresh snapshot to every affected scope. Called from service-layer
     * write paths ([TaskService.updateState], [PendingTaskService.markDone],
     * [PendingTaskService.reopen], requalifier route in [com.jervis.chat.ChatRpcImpl]).
     */
    fun invalidate(clientId: String?) {
        val normalized = normalizeClientId(clientId)
        scope.launch {
            try {
                emitFor(normalized, showDone = false)
                emitFor(normalized, showDone = true)
                // Global scope (null) must also refresh — subscribers with no
                // filter see tasks across all clients.
                if (!normalized.isNullOrBlank()) {
                    emitFor(null, showDone = false)
                    emitFor(null, showDone = true)
                }
            } catch (e: Exception) {
                logger.warn(e) { "SIDEBAR_INVALIDATE_FAILED: clientId=$clientId" }
            }
        }
    }

    private suspend fun emitFor(clientId: String?, showDone: Boolean) {
        val key = scopeKey(clientId, showDone)
        val flow = flows[key] ?: return // no subscribers → skip work
        flow.emit(buildSnapshot(clientId, showDone))
    }

    private suspend fun buildSnapshot(clientId: String?, showDone: Boolean): SidebarSnapshot {
        val criteria = Criteria()
        clientId?.takeIf { it.isNotBlank() }?.let {
            criteria.and("clientId").`is`(ObjectId(it))
        }
        val states = if (showDone) {
            listOf(TaskStateEnum.DONE.name)
        } else {
            ACTIVE_STATES
        }
        criteria.and("state").`in`(states)

        val query = Query(criteria)
            .with(Sort.by(Sort.Direction.DESC, "lastActivityAt", "createdAt"))
            .limit(SIDEBAR_LIMIT)

        val tasks = mongoTemplate
            .find(query, TaskDocument::class.java, "tasks")
            .collectList()
            .awaitSingle()
            .map { it.toPendingTaskDto() }

        // Count DONE for the same client scope so the UI can show an
        // optional history badge without reopening another stream.
        val doneCriteria = Criteria()
        clientId?.takeIf { it.isNotBlank() }?.let {
            doneCriteria.and("clientId").`is`(ObjectId(it))
        }
        doneCriteria.and("state").`is`(TaskStateEnum.DONE.name)
        val doneCount = mongoTemplate.count(Query(doneCriteria), "tasks").awaitSingle()

        return SidebarSnapshot(tasks = tasks, doneCount = doneCount)
    }

    private fun scopeKey(clientId: String?, showDone: Boolean): String =
        "${clientId.orEmpty()}:${if (showDone) "done" else "active"}"

    companion object {
        private val ACTIVE_STATES = listOf(
            TaskStateEnum.USER_TASK.name,
            TaskStateEnum.PROCESSING.name,
            TaskStateEnum.CODING.name,
            TaskStateEnum.QUEUED.name,
            TaskStateEnum.BLOCKED.name,
            TaskStateEnum.INDEXING.name,
            TaskStateEnum.NEW.name,
            TaskStateEnum.ERROR.name,
        )
        private const val SIDEBAR_LIMIT = 200
    }
}
