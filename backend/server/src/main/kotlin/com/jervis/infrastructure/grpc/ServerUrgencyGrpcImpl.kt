package com.jervis.infrastructure.grpc

import com.jervis.common.types.TaskId
import com.jervis.contracts.server.BumpDeadlineRequest
import com.jervis.contracts.server.BumpDeadlineResponse
import com.jervis.contracts.server.FastPathDeadlines
import com.jervis.contracts.server.GetUrgencyConfigRequest
import com.jervis.contracts.server.GetUserPresenceRequest
import com.jervis.contracts.server.PresenceFactor
import com.jervis.contracts.server.ServerUrgencyServiceGrpcKt
import com.jervis.contracts.server.UpdateUrgencyConfigRequest
import com.jervis.contracts.server.UrgencyConfig
import com.jervis.contracts.server.UserPresence
import com.jervis.dto.urgency.FastPathDeadlinesDto
import com.jervis.dto.urgency.Presence
import com.jervis.dto.urgency.PresenceFactorDto
import com.jervis.dto.urgency.UrgencyConfigDto
import com.jervis.dto.urgency.UserPresenceDto
import com.jervis.task.TaskRepository
import com.jervis.urgency.UrgencyConfigRpcImpl
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

    override suspend fun getConfig(request: GetUrgencyConfigRequest): UrgencyConfig {
        require(request.clientId.isNotBlank()) { "client_id required" }
        return urgencyConfig.getUrgencyConfig(request.clientId).toProto()
    }

    override suspend fun updateConfig(request: UpdateUrgencyConfigRequest): UrgencyConfig {
        val dto = request.config.toDto()
        return urgencyConfig.updateUrgencyConfig(dto).toProto()
    }

    override suspend fun getPresence(request: GetUserPresenceRequest): UserPresence =
        urgencyConfig.getUserPresence(request.userId, request.platform).toProto()

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

private fun UrgencyConfigDto.toProto(): UrgencyConfig =
    UrgencyConfig.newBuilder()
        .setClientId(clientId)
        .setDefaultDeadlineMinutes(defaultDeadlineMinutes)
        .setFastPathDeadlineMinutes(fastPathDeadlineMinutes.toProto())
        .setPresenceFactor(presenceFactor.toProto())
        .setPresenceTtlSeconds(presenceTtlSeconds)
        .setClassifierBudgetPerHourPerSender(classifierBudgetPerHourPerSender)
        .setApproachingDeadlineThresholdPct(approachingDeadlineThresholdPct)
        .build()

private fun FastPathDeadlinesDto.toProto(): FastPathDeadlines =
    FastPathDeadlines.newBuilder()
        .setDirectMessage(directMessage)
        .setChannelMention(channelMention)
        .setReplyMyThreadActive(replyMyThreadActive)
        .setReplyMyThreadStale(replyMyThreadStale)
        .build()

private fun PresenceFactorDto.toProto(): PresenceFactor =
    PresenceFactor.newBuilder()
        .setActive(active)
        .setAwayRecent(awayRecent)
        .setAwayOld(awayOld)
        .setOffline(offline)
        .setUnknown(unknown)
        .build()

private fun UserPresenceDto.toProto(): UserPresence =
    UserPresence.newBuilder()
        .setUserId(userId)
        .setPlatform(platform)
        .setPresence(presence.name)
        .setLastActiveAtIso(lastActiveAtIso ?: "")
        .build()

private fun UrgencyConfig.toDto(): UrgencyConfigDto =
    UrgencyConfigDto(
        clientId = clientId,
        defaultDeadlineMinutes = if (defaultDeadlineMinutes > 0) defaultDeadlineMinutes else 30,
        fastPathDeadlineMinutes = fastPathDeadlineMinutes.toDto(),
        presenceFactor = presenceFactor.toDto(),
        presenceTtlSeconds = if (presenceTtlSeconds > 0) presenceTtlSeconds else 120,
        classifierBudgetPerHourPerSender =
            if (classifierBudgetPerHourPerSender > 0) classifierBudgetPerHourPerSender else 5,
        approachingDeadlineThresholdPct =
            if (approachingDeadlineThresholdPct > 0) approachingDeadlineThresholdPct else 0.20,
    )

private fun FastPathDeadlines.toDto(): FastPathDeadlinesDto =
    FastPathDeadlinesDto(
        directMessage = if (directMessage > 0) directMessage else 2,
        channelMention = if (channelMention > 0) channelMention else 5,
        replyMyThreadActive = if (replyMyThreadActive > 0) replyMyThreadActive else 5,
        replyMyThreadStale = if (replyMyThreadStale > 0) replyMyThreadStale else 10,
    )

private fun PresenceFactor.toDto(): PresenceFactorDto =
    PresenceFactorDto(
        active = if (active > 0) active else 1.0,
        awayRecent = if (awayRecent > 0) awayRecent else 1.5,
        awayOld = if (awayOld > 0) awayOld else 5.0,
        offline = if (offline > 0) offline else 10.0,
        unknown = if (unknown > 0) unknown else 1.0,
    )
