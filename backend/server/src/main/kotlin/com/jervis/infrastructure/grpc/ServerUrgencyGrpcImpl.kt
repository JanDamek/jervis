package com.jervis.infrastructure.grpc

import com.jervis.common.types.TaskId
import com.jervis.contracts.server.BumpDeadlineRequest
import com.jervis.contracts.server.BumpDeadlineResponse
import com.jervis.contracts.server.GetUrgencyConfigRequest
import com.jervis.contracts.server.GetUserPresenceRequest
import com.jervis.contracts.server.ServerUrgencyServiceGrpcKt
import com.jervis.contracts.server.UpdateUrgencyConfigRequest
import com.jervis.contracts.server.UrgencyPayload
import com.jervis.dto.urgency.UrgencyConfigDto
import com.jervis.task.TaskRepository
import com.jervis.urgency.UrgencyConfigRpcImpl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerUrgencyGrpcImpl(
    private val urgencyConfig: UrgencyConfigRpcImpl,
    private val taskRepository: TaskRepository,
) : ServerUrgencyServiceGrpcKt.ServerUrgencyServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun getConfig(request: GetUrgencyConfigRequest): UrgencyPayload {
        require(request.clientId.isNotBlank()) { "client_id required" }
        val dto = urgencyConfig.getUrgencyConfig(request.clientId)
        return UrgencyPayload.newBuilder().setBodyJson(json.encodeToString(dto)).build()
    }

    override suspend fun updateConfig(request: UpdateUrgencyConfigRequest): UrgencyPayload {
        val dto = json.decodeFromString<UrgencyConfigDto>(request.configJson)
        val saved = urgencyConfig.updateUrgencyConfig(dto)
        return UrgencyPayload.newBuilder().setBodyJson(json.encodeToString(saved)).build()
    }

    override suspend fun getPresence(request: GetUserPresenceRequest): UrgencyPayload {
        val dto = urgencyConfig.getUserPresence(request.userId, request.platform)
        return UrgencyPayload.newBuilder().setBodyJson(json.encodeToString(dto)).build()
    }

    override suspend fun bumpDeadline(request: BumpDeadlineRequest): BumpDeadlineResponse {
        val existing = taskRepository.findById(TaskId(ObjectId(request.taskId)))
            ?: return BumpDeadlineResponse.newBuilder()
                .setOk(false)
                .setError("task not found")
                .build()
        val newDeadline = Instant.parse(request.deadlineIso)
        taskRepository.save(existing.copy(deadline = newDeadline))
        logger.info {
            "URGENCY_BUMP: task=${request.taskId} deadline=${request.deadlineIso} reason=${request.reason.ifBlank { "(none)" }}"
        }
        return BumpDeadlineResponse.newBuilder().setOk(true).build()
    }
}
