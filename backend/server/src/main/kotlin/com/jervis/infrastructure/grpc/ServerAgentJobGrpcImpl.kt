package com.jervis.infrastructure.grpc

import com.jervis.agentjob.AgentJobDispatcher
import com.jervis.agentjob.AgentJobRecord
import com.jervis.common.types.AgentJobId
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.AbortAgentJobRequest
import com.jervis.contracts.server.AbortAgentJobResponse
import com.jervis.contracts.server.AgentJobIdRequest
import com.jervis.contracts.server.DispatchAgentJobRequest
import com.jervis.contracts.server.DispatchAgentJobResponse
import com.jervis.contracts.server.GetAgentJobStatusResponse
import com.jervis.contracts.server.ReportAgentDoneRequest
import com.jervis.contracts.server.ReportAgentDoneResponse
import com.jervis.contracts.server.ServerAgentJobServiceGrpcKt
import com.jervis.dto.agentjob.AgentJobFlavor
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * gRPC facade over [AgentJobDispatcher].
 *
 * Called from the Python MCP server (tools `dispatch_agent_job`,
 * `get_agent_job_status`, `abort_agent_job`) — Claude invokes the MCP
 * tool, which delegates here so the Kotlin side remains the single
 * writer for `agent_job_records`.
 *
 * The surface is intentionally narrow: dispatch creates+persists
 * records, status is read-only, abort transitions to CANCELLED.
 * The watcher ([com.jervis.agentjob.AgentJobWatcher]) handles all
 * lifecycle reconciliation — this impl never mutates state outside
 * the three explicit operations.
 */
@Component
class ServerAgentJobGrpcImpl(
    private val dispatcher: AgentJobDispatcher,
) : ServerAgentJobServiceGrpcKt.ServerAgentJobServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun dispatchAgentJob(request: DispatchAgentJobRequest): DispatchAgentJobResponse {
        return try {
            val flavor = parseFlavor(request.flavor)
                ?: return DispatchAgentJobResponse.newBuilder()
                    .setOk(false)
                    .setError("Unknown flavor: '${request.flavor}'")
                    .build()

            val clientId = parseClientId(request.clientId)
            val projectId = parseProjectId(request.projectId)
            val record = dispatcher.dispatch(
                flavor = flavor,
                title = request.title,
                description = request.description,
                clientId = clientId,
                projectId = projectId,
                resourceId = request.resourceId.takeIf { it.isNotBlank() },
                dispatchedBy = request.dispatchedBy.ifBlank { "mcp:unknown" },
                branchName = request.branchName.takeIf { it.isNotBlank() },
            )

            DispatchAgentJobResponse.newBuilder()
                .setOk(true)
                .setAgentJobId(record.id.toString())
                .setState(record.state.name)
                .setKubernetesJobName(record.kubernetesJobName.orEmpty())
                .setWorkspacePath(record.workspacePath.orEmpty())
                .setBranch(record.gitBranch.orEmpty())
                .build()
        } catch (e: IllegalArgumentException) {
            DispatchAgentJobResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "invalid argument")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "dispatchAgentJob failed" }
            DispatchAgentJobResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    override suspend fun getAgentJobStatus(request: AgentJobIdRequest): GetAgentJobStatusResponse {
        return try {
            val jobId = AgentJobId(ObjectId(request.agentJobId))
            val snap = dispatcher.getStatus(jobId)
            buildStatusResponse(snap.record, snap.kubernetesJobPhase)
        } catch (e: NoSuchElementException) {
            GetAgentJobStatusResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "not found")
                .build()
        } catch (e: IllegalArgumentException) {
            GetAgentJobStatusResponse.newBuilder()
                .setOk(false)
                .setError("Invalid agent_job_id: '${request.agentJobId}'")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "getAgentJobStatus failed" }
            GetAgentJobStatusResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    override suspend fun reportAgentDone(request: ReportAgentDoneRequest): ReportAgentDoneResponse {
        return try {
            val jobId = AgentJobId(ObjectId(request.agentJobId))
            val record = dispatcher.completeFromAgent(
                jobId = jobId,
                success = request.success,
                summary = request.summary.takeIf { it.isNotBlank() },
                commitSha = request.commitSha.takeIf { it.isNotBlank() },
                branch = request.branch.takeIf { it.isNotBlank() },
                changedFiles = request.changedFilesList.orEmpty().toList(),
            )
            ReportAgentDoneResponse.newBuilder()
                .setOk(true)
                .setState(record.state.name)
                .build()
        } catch (e: NoSuchElementException) {
            ReportAgentDoneResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "not found")
                .build()
        } catch (e: IllegalArgumentException) {
            ReportAgentDoneResponse.newBuilder()
                .setOk(false)
                .setError("Invalid agent_job_id: '${request.agentJobId}'")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "reportAgentDone failed" }
            ReportAgentDoneResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    override suspend fun abortAgentJob(request: AbortAgentJobRequest): AbortAgentJobResponse {
        return try {
            val jobId = AgentJobId(ObjectId(request.agentJobId))
            val reason = request.reason.ifBlank { "aborted via MCP without explicit reason" }
            val record = dispatcher.abort(jobId, reason)
            AbortAgentJobResponse.newBuilder()
                .setOk(true)
                .setState(record.state.name)
                .build()
        } catch (e: NoSuchElementException) {
            AbortAgentJobResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "not found")
                .build()
        } catch (e: IllegalArgumentException) {
            AbortAgentJobResponse.newBuilder()
                .setOk(false)
                .setError("Invalid agent_job_id: '${request.agentJobId}'")
                .build()
        } catch (e: Exception) {
            logger.error(e) { "abortAgentJob failed" }
            AbortAgentJobResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: e::class.simpleName.orEmpty())
                .build()
        }
    }

    private fun parseFlavor(raw: String): AgentJobFlavor? =
        AgentJobFlavor.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun parseClientId(raw: String): ClientId? =
        raw.takeIf { it.isNotBlank() }?.let { ClientId(ObjectId(it)) }

    private fun parseProjectId(raw: String): ProjectId? =
        raw.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) }

    private fun buildStatusResponse(record: AgentJobRecord, phase: String?): GetAgentJobStatusResponse =
        GetAgentJobStatusResponse.newBuilder()
            .setOk(true)
            .setAgentJobId(record.id.toString())
            .setFlavor(record.flavor.name)
            .setState(record.state.name)
            .setKubernetesJobName(record.kubernetesJobName.orEmpty())
            .setKubernetesJobPhase(phase.orEmpty())
            .setClientId(record.clientId?.toString().orEmpty())
            .setProjectId(record.projectId?.toString().orEmpty())
            .setResourceId(record.resourceId.orEmpty())
            .setWorkspacePath(record.workspacePath.orEmpty())
            .setBranch(record.gitBranch.orEmpty())
            .setGitCommitSha(record.gitCommitSha.orEmpty())
            .setTitle(record.title)
            .setDescription(record.description)
            .setDispatchedBy(record.dispatchedBy)
            .setResultSummary(record.resultSummary.orEmpty())
            .addAllArtifacts(record.artifacts)
            .setAskUserQuestion(record.askUserQuestion.orEmpty())
            .setErrorMessage(record.errorMessage.orEmpty())
            .setCreatedAt(record.createdAt.toString())
            .setStartedAt(record.startedAt?.toString().orEmpty())
            .setCompletedAt(record.completedAt?.toString().orEmpty())
            .build()
}
