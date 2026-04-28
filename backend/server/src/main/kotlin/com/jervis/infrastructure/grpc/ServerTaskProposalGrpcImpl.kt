package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.contracts.server.DedupCandidate
import com.jervis.contracts.server.DedupRequest
import com.jervis.contracts.server.DedupResponse
import com.jervis.contracts.server.InsertProposalRequest
import com.jervis.contracts.server.InsertProposalResponse
import com.jervis.contracts.server.ProposalActionResponse
import com.jervis.contracts.server.RejectTaskRequest
import com.jervis.contracts.server.ServerTaskProposalServiceGrpcKt
import com.jervis.contracts.server.TaskIdRequest
import com.jervis.contracts.server.UpdateProposalRequest
import com.jervis.contracts.server.UpdateProposalResponse
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.ProcessingMode
import com.jervis.task.ProposalStage
import com.jervis.task.ProposalTaskType
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * gRPC facade for the Claude CLI proposal lifecycle.
 *
 * Orchestrator (Python) is the *write logic* layer — it does embedding,
 * dedup, and decides whether to persist. This service is the *write
 * surface* — atomic Mongo transitions, no business logic. All stage
 * transitions use [ReactiveMongoTemplate.findAndModify] with stage
 * predicates so out-of-order callers get `INVALID_STATE` rather than
 * silently overwriting.
 *
 * BackgroundEngine pickup is governed by
 * [TaskRepository.findApprovedByProcessingModeAndStateOrderByDeadlineAscPriorityScoreDescCreatedAtAsc]
 * which already excludes DRAFT/AWAITING_APPROVAL/REJECTED, so a
 * proposal in flight never reaches dispatch even if state=NEW.
 */
@Component
class ServerTaskProposalGrpcImpl(
    private val taskRepository: TaskRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
) : ServerTaskProposalServiceGrpcKt.ServerTaskProposalServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun insertProposal(request: InsertProposalRequest): InsertProposalResponse {
        return try {
            val clientId = parseClientIdRequired(request.clientId)
                ?: return failInsert("client_id required")
            // project_id is optional only for Klient-scoped proposals; the
            // Project session always sends one. Treat blank as
            // null-projectId task (== client-scope proposal).
            val projectId = parseProjectId(request.projectId)
            val proposalType = parseProposalTaskType(request.proposalTaskType)
                ?: return failInsert(
                    "Unknown proposal_task_type: '${request.proposalTaskType}' — " +
                        "accepted: ${ProposalTaskType.entries.joinToString(", ")}",
                )
            val title = request.title.trim()
            val description = request.description.trim()
            if (title.isBlank()) return failInsert("title is required")
            if (description.isBlank()) return failInsert("description is required")
            val proposedBy = request.proposedBy.ifBlank { "claude-cli:unknown" }
            val scheduledAt = parseInstant(request.scheduledAtIso)
            val parentTaskId = parseTaskId(request.parentTaskId)
            val dependsOn = request.dependsOnTaskIdsList
                .orEmpty()
                .filter { it.isNotBlank() }
                .map { TaskId(ObjectId(it)) }

            val titleEmbedding = request.titleEmbeddingList?.toList()?.takeIf { it.isNotEmpty() }
            val descriptionEmbedding =
                request.descriptionEmbeddingList?.toList()?.takeIf { it.isNotEmpty() }

            val source = SourceUrn("agent://claude-cli/${proposedBy}")
            val task = TaskDocument(
                type = TaskTypeEnum.SYSTEM,
                taskName = title,
                content = description,
                projectId = projectId,
                clientId = clientId,
                state = TaskStateEnum.NEW,
                processingMode = ProcessingMode.BACKGROUND,
                sourceUrn = source,
                proposedBy = proposedBy,
                proposalReason = request.reason.takeIf { it.isNotBlank() },
                proposalStage = ProposalStage.DRAFT,
                proposalTaskType = proposalType,
                scheduledAt = scheduledAt,
                parentTaskId = parentTaskId,
                blockedByTaskIds = dependsOn,
                titleEmbedding = titleEmbedding,
                descriptionEmbedding = descriptionEmbedding,
            )
            val saved = taskRepository.save(task)
            logger.info {
                "PROPOSAL_INSERTED: id=${saved.id} clientId=$clientId projectId=$projectId " +
                    "proposedBy=$proposedBy proposalTaskType=$proposalType"
            }
            InsertProposalResponse.newBuilder()
                .setOk(true)
                .setTaskId(saved.id.toString())
                .build()
        } catch (e: IllegalArgumentException) {
            failInsert(e.message ?: "invalid argument")
        } catch (e: Exception) {
            logger.error(e) { "insertProposal failed" }
            failInsert(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun updateProposal(request: UpdateProposalRequest): UpdateProposalResponse {
        return try {
            val taskId = parseTaskIdRequired(request.taskId)
                ?: return UpdateProposalResponse.newBuilder()
                    .setOk(false)
                    .setError("task_id required")
                    .build()

            // Mutable stages: DRAFT (initial composition) and REJECTED
            // (user gave feedback, Claude can re-propose). Anything else
            // (AWAITING_APPROVAL / APPROVED) is immutable — don't let
            // Claude rewrite a proposal the user is currently looking at
            // or has already signed off on.
            val query = Query(
                Criteria.where("_id").`is`(taskId.value)
                    .and("proposalStage")
                    .`in`(ProposalStage.DRAFT.name, ProposalStage.REJECTED.name),
            )

            val update = Update()
            // findAndModify with an empty $set is a no-op — guard against
            // an "all fields blank" call so we don't silently succeed.
            var hasChange = false
            request.title.takeIf { it.isNotBlank() }?.let {
                update.set("taskName", it.trim())
                hasChange = true
            }
            request.description.takeIf { it.isNotBlank() }?.let {
                update.set("content", it.trim())
                hasChange = true
            }
            request.reason.takeIf { it.isNotBlank() }?.let {
                update.set("proposalReason", it.trim())
                hasChange = true
            }
            request.proposalTaskType.takeIf { it.isNotBlank() }?.let { raw ->
                val parsed = parseProposalTaskType(raw)
                    ?: return UpdateProposalResponse.newBuilder()
                        .setOk(false)
                        .setError(
                            "Unknown proposal_task_type: '$raw' — accepted: ${
                                ProposalTaskType.entries.joinToString(", ")
                            }",
                        )
                        .build()
                update.set("proposalTaskType", parsed.name)
                hasChange = true
            }
            request.scheduledAtIso.takeIf { it.isNotBlank() }?.let {
                update.set("scheduledAt", parseInstant(it))
                hasChange = true
            }
            val tEmb = request.titleEmbeddingList?.toList()?.takeIf { it.isNotEmpty() }
            if (tEmb != null) {
                update.set("titleEmbedding", tEmb)
                hasChange = true
            }
            val dEmb = request.descriptionEmbeddingList?.toList()?.takeIf { it.isNotEmpty() }
            if (dEmb != null) {
                update.set("descriptionEmbedding", dEmb)
                hasChange = true
            }
            // REJECTED → DRAFT: any update on a REJECTED proposal moves
            // it back to DRAFT and clears the rejection reason so the
            // user sees a fresh draft when it's resubmitted for
            // approval. Without this, the UI would still see
            // "REJECTED" status with the old reason.
            update.set("proposalStage", ProposalStage.DRAFT.name)
            update.set("proposalRejectionReason", null)

            if (!hasChange) {
                return UpdateProposalResponse.newBuilder()
                    .setOk(false)
                    .setError("no fields to update")
                    .build()
            }

            val options = FindAndModifyOptions.options().returnNew(true)
            val saved = mongoTemplate
                .findAndModify(query, update, options, TaskDocument::class.java)
                .awaitSingleOrNull()

            if (saved == null) {
                UpdateProposalResponse.newBuilder()
                    .setOk(false)
                    .setError("INVALID_STATE: proposal not in DRAFT/REJECTED (or not found)")
                    .build()
            } else {
                logger.info {
                    "PROPOSAL_UPDATED: id=${saved.id} stage=${saved.proposalStage} " +
                        "type=${saved.proposalTaskType}"
                }
                UpdateProposalResponse.newBuilder().setOk(true).build()
            }
        } catch (e: IllegalArgumentException) {
            UpdateProposalResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "invalid argument")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "updateProposal failed" }
            UpdateProposalResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    override suspend fun sendForApproval(request: TaskIdRequest): ProposalActionResponse =
        atomicStageTransition(
            taskIdHex = request.taskId,
            from = setOf(ProposalStage.DRAFT),
            to = ProposalStage.AWAITING_APPROVAL,
            extraSets = emptyMap(),
            log = "PROPOSAL_SENT_FOR_APPROVAL",
        )

    override suspend fun approveTask(request: TaskIdRequest): ProposalActionResponse =
        atomicStageTransition(
            taskIdHex = request.taskId,
            from = setOf(ProposalStage.AWAITING_APPROVAL),
            to = ProposalStage.APPROVED,
            // Skip NEW/INDEXING — proposal already carries title +
            // description + scope; jump straight to QUEUED so
            // BackgroundEngine pickup happens on the next loop tick.
            extraSets = mapOf("state" to TaskStateEnum.QUEUED.name),
            log = "PROPOSAL_APPROVED",
        )

    override suspend fun rejectTask(request: RejectTaskRequest): ProposalActionResponse {
        val reason = request.reason.trim().takeIf { it.isNotBlank() }
            ?: "rejected by user without explicit reason"
        return atomicStageTransition(
            taskIdHex = request.taskId,
            from = setOf(ProposalStage.AWAITING_APPROVAL),
            to = ProposalStage.REJECTED,
            extraSets = mapOf("proposalRejectionReason" to reason),
            log = "PROPOSAL_REJECTED",
        )
    }

    override suspend fun listPendingProposalsForDedup(request: DedupRequest): DedupResponse {
        return try {
            val proposedBy = request.proposedBy.takeIf { it.isNotBlank() }
                ?: return DedupResponse.newBuilder()
                    .setOk(false)
                    .setError("proposed_by required")
                    .build()
            val since = Instant.now().minus(Duration.ofDays(DEDUP_LOOKBACK_DAYS))
            val stages = listOf(ProposalStage.DRAFT, ProposalStage.AWAITING_APPROVAL)

            val candidates = if (request.clientId.isNotBlank()) {
                val clientId = ClientId(ObjectId(request.clientId))
                taskRepository
                    .findByClientIdAndProposedByAndProposalStageInAndCreatedAtAfter(
                        clientId = clientId,
                        proposedBy = proposedBy,
                        proposalStages = stages,
                        createdAt = since,
                    )
                    .toList()
            } else {
                taskRepository
                    .findByProposedByAndProposalStageInAndCreatedAtAfter(
                        proposedBy = proposedBy,
                        proposalStages = stages,
                        createdAt = since,
                    )
                    .toList()
            }

            val projectFilter = request.projectId.takeIf { it.isNotBlank() }
            val filtered = if (projectFilter != null) {
                candidates.filter { it.projectId?.toString() == projectFilter }
            } else {
                candidates
            }

            val out = filtered.map(::candidateToProto)
            DedupResponse.newBuilder()
                .setOk(true)
                .addAllCandidates(out)
                .build()
        } catch (e: IllegalArgumentException) {
            DedupResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "invalid argument")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "listPendingProposalsForDedup failed" }
            DedupResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private suspend fun atomicStageTransition(
        taskIdHex: String,
        from: Set<ProposalStage>,
        to: ProposalStage,
        extraSets: Map<String, Any?>,
        log: String,
    ): ProposalActionResponse {
        return try {
            val taskId = parseTaskIdRequired(taskIdHex)
                ?: return ProposalActionResponse.newBuilder()
                    .setOk(false)
                    .setError("task_id required")
                    .build()
            val query = Query(
                Criteria.where("_id").`is`(taskId.value)
                    .and("proposalStage")
                    .`in`(from.map { it.name }),
            )
            val update = Update().set("proposalStage", to.name)
            extraSets.forEach { (k, v) -> update.set(k, v) }

            val options = FindAndModifyOptions.options().returnNew(true)
            val saved = mongoTemplate
                .findAndModify(query, update, options, TaskDocument::class.java)
                .awaitSingleOrNull()

            if (saved == null) {
                val expected = from.joinToString("/") { it.name }
                ProposalActionResponse.newBuilder()
                    .setOk(false)
                    .setError("INVALID_STATE: proposal not in $expected (or not found)")
                    .build()
            } else {
                logger.info { "$log: id=${saved.id} stage=${saved.proposalStage}" }
                ProposalActionResponse.newBuilder()
                    .setOk(true)
                    .setProposalStage(saved.proposalStage?.name.orEmpty())
                    .build()
            }
        } catch (e: IllegalArgumentException) {
            ProposalActionResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "invalid argument")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "$log failed" }
            ProposalActionResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    private fun candidateToProto(t: TaskDocument): DedupCandidate {
        val builder = DedupCandidate.newBuilder()
            .setTaskId(t.id.toString())
            .setTitle(t.taskName)
            .setDescription(t.content)
            .setClientId(t.clientId.toString())
            .setProjectId(t.projectId?.toString().orEmpty())
            .setProposalStage(t.proposalStage?.name.orEmpty())
        t.titleEmbedding?.let { builder.addAllTitleEmbedding(it) }
        t.descriptionEmbedding?.let { builder.addAllDescriptionEmbedding(it) }
        return builder.build()
    }

    private fun failInsert(error: String): InsertProposalResponse =
        InsertProposalResponse.newBuilder()
            .setOk(false)
            .setError(error)
            .build()

    private fun parseClientIdRequired(raw: String): ClientId? =
        raw.takeIf { it.isNotBlank() }?.let { ClientId(ObjectId(it)) }

    private fun parseProjectId(raw: String): ProjectId? =
        raw.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) }

    private fun parseTaskId(raw: String): TaskId? =
        raw.takeIf { it.isNotBlank() }?.let { TaskId(ObjectId(it)) }

    private fun parseTaskIdRequired(raw: String): TaskId? =
        raw.takeIf { it.isNotBlank() }?.let { TaskId(ObjectId(it)) }

    private fun parseProposalTaskType(raw: String): ProposalTaskType? =
        ProposalTaskType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun parseInstant(raw: String): Instant? =
        raw.takeIf { it.isNotBlank() }?.let { Instant.parse(it) }

    companion object {
        // 7-day window keeps the dedup query cheap (Claude session
        // typically spans hours, not weeks; an "old" rejected proposal
        // from a month ago shouldn't block a fresh one).
        private const val DEDUP_LOOKBACK_DAYS = 7L
    }
}
