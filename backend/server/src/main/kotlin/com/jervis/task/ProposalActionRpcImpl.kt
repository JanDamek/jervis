package com.jervis.task

import com.jervis.contracts.server.RejectTaskRequest
import com.jervis.contracts.server.TaskIdRequest
import com.jervis.contracts.server.UpdateProposalRequest
import com.jervis.dto.proposal.ProposalActionResultDto
import com.jervis.dto.proposal.UpdateProposalRequestDto
import com.jervis.infrastructure.grpc.ServerTaskProposalGrpcImpl
import com.jervis.service.proposal.IProposalActionService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * UI-facing kRPC bridge for the Claude CLI proposal lifecycle (PR4).
 *
 * Delegates approve/reject/update to [ServerTaskProposalGrpcImpl] (which
 * already owns the atomic findAndModify transitions used by the
 * orchestrator side) so business rules stay in a single place. This
 * class is purely a UI surface adapter:
 *
 * 1. Translate kRPC DTOs ↔ proto messages.
 * 2. Maintain the `subscribePendingProposalsCount` push-flow used by the
 *    sidebar badge "Návrhy ke schválení (N)".
 *
 * The count flow is fed by a 5-second internal recompute tick — pull
 * cadence is server-internal and does not break the UI's push-only
 * contract (rule #9). Long-term: hook the recompute into a Mongo change
 * stream or a TaskRepository save event so the count is truly
 * event-driven.
 */
@Component
class ProposalActionRpcImpl(
    private val proposalGrpcImpl: ServerTaskProposalGrpcImpl,
    private val taskRepository: TaskRepository,
) : IProposalActionService {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val countFlow = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 4)
    private var pollJob: Job? = null

    @PostConstruct
    fun start() {
        pollJob = scope.launch {
            // Seed immediately so first subscriber gets a value within
            // a single tick rather than waiting for [POLL_INTERVAL_MS].
            emitCurrentCount()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                emitCurrentCount()
            }
        }
        logger.info {
            "ProposalActionRpcImpl started — recomputing AWAITING_APPROVAL " +
                "count every ${POLL_INTERVAL_MS}ms"
        }
    }

    @PreDestroy
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        logger.info { "ProposalActionRpcImpl stopped" }
    }

    private suspend fun emitCurrentCount() {
        runCatching {
            taskRepository.countByProposalStage(ProposalStage.AWAITING_APPROVAL).toInt()
        }.onSuccess { count ->
            countFlow.emit(count)
        }.onFailure { e ->
            // Connection-level / Mongo blip → keep last replay=1 value;
            // the next tick will refresh.
            logger.debug(e) { "PROPOSAL_COUNT_RECOMPUTE_FAIL: ${e.message}" }
        }
    }

    override suspend fun approveProposal(taskId: String): ProposalActionResultDto {
        val request = TaskIdRequest.newBuilder().setTaskId(taskId).build()
        val resp = proposalGrpcImpl.approveTask(request)
        // Refresh count immediately so sidebar reflects the change
        // without waiting for the next 5s tick.
        emitCurrentCount()
        return ProposalActionResultDto(
            ok = resp.ok,
            error = resp.error.takeIf { it.isNotBlank() },
            newStage = resp.proposalStage.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun rejectProposal(taskId: String, reason: String): ProposalActionResultDto {
        // UI enforces min 5 chars; double-check here so a future
        // non-UI caller can't sneak a blank reason past the gRPC layer
        // (which would default to "rejected by user without explicit reason").
        if (reason.trim().length < MIN_REASON_LENGTH) {
            return ProposalActionResultDto(
                ok = false,
                error = "reason must be at least $MIN_REASON_LENGTH characters",
            )
        }
        val request = RejectTaskRequest.newBuilder()
            .setTaskId(taskId)
            .setReason(reason.trim())
            .build()
        val resp = proposalGrpcImpl.rejectTask(request)
        emitCurrentCount()
        return ProposalActionResultDto(
            ok = resp.ok,
            error = resp.error.takeIf { it.isNotBlank() },
            newStage = resp.proposalStage.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun updateProposal(
        taskId: String,
        request: UpdateProposalRequestDto,
    ): ProposalActionResultDto {
        val proto = UpdateProposalRequest.newBuilder()
            .setTaskId(taskId)
            .apply {
                request.title?.takeIf { it.isNotBlank() }?.let { setTitle(it) }
                request.description?.takeIf { it.isNotBlank() }?.let { setDescription(it) }
                request.reason?.takeIf { it.isNotBlank() }?.let { setReason(it) }
                request.scheduledAtIso?.takeIf { it.isNotBlank() }?.let { setScheduledAtIso(it) }
                request.proposalTaskType?.takeIf { it.isNotBlank() }?.let { setProposalTaskType(it) }
            }
            .build()
        val resp = proposalGrpcImpl.updateProposal(proto)
        // Update on REJECTED moves stage back to DRAFT so the count is
        // unchanged; on DRAFT the stage doesn't change either. Skip the
        // refresh — gRPC layer is the only writer that flips
        // AWAITING_APPROVAL counts (sendForApproval / approve / reject).
        return ProposalActionResultDto(
            ok = resp.ok,
            error = resp.error.takeIf { it.isNotBlank() },
            newStage = if (resp.ok) ProposalStage.DRAFT.name else null,
        )
    }

    override suspend fun sendForApproval(taskId: String): ProposalActionResultDto {
        val request = TaskIdRequest.newBuilder().setTaskId(taskId).build()
        val resp = proposalGrpcImpl.sendForApproval(request)
        // DRAFT → AWAITING_APPROVAL flips the badge count. Refresh
        // immediately so the sidebar reflects the change without
        // waiting for the next 5s tick.
        emitCurrentCount()
        return ProposalActionResultDto(
            ok = resp.ok,
            error = resp.error.takeIf { it.isNotBlank() },
            newStage = resp.proposalStage.takeIf { it.isNotBlank() },
        )
    }

    override fun subscribePendingProposalsCount(): Flow<Int> = countFlow

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MIN_REASON_LENGTH = 5
    }
}
