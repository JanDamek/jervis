package com.jervis.agentjob

import com.jervis.common.types.AgentJobId
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.agentjob.AgentJobFlavor
import com.jervis.dto.agentjob.AgentJobState
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for `agent_job_records` collection.
 *
 * Read path is used by:
 *  - UI "K reakci" list (`findByStateOrderByCreatedAtDesc(WAITING_USER)`)
 *  - Claude MCP tools `list_agent_jobs` / `get_agent_job_status`
 *  - Dispatcher concurrency gate (count RUNNING per scope)
 *  - K8s reconciliation watcher (all non-terminal records on boot)
 *
 * Write path is driven by:
 *  - `AgentJobDispatcher` when `dispatch_agent_job` MCP tool is called
 *    — insert in state QUEUED, later transition to RUNNING once the
 *    K8s Job is created.
 *  - `AgentJobWatcher` when K8s Job state changes — transitions to
 *    RUNNING / WAITING_USER / DONE / ERROR.
 *  - `AgentJobScheduler` when a trigger fires — insert with
 *    `dispatchedBy="scheduler:<name>"`.
 *  - `abort_agent_job` MCP — transitions to CANCELLED.
 *
 * Claude does not write here directly; all writes go through one of
 * the above server-owned paths so the record stays authoritative.
 */
@Repository
interface AgentJobRecordRepository : CoroutineCrudRepository<AgentJobRecord, AgentJobId> {
    /**
     * Fetch by id without touching the AOP proxy path that mishandles
     * Kotlin inline value classes. Mirrors the TaskRepository pattern.
     */
    suspend fun getById(id: AgentJobId): AgentJobRecord?

    suspend fun findByStateOrderByCreatedAtDesc(state: AgentJobState): Flow<AgentJobRecord>

    suspend fun findByClientIdOrderByCreatedAtDesc(clientId: ClientId): Flow<AgentJobRecord>

    suspend fun findByClientIdAndStateOrderByCreatedAtDesc(
        clientId: ClientId,
        state: AgentJobState,
    ): Flow<AgentJobRecord>

    suspend fun findByProjectIdAndStateOrderByCreatedAtDesc(
        projectId: ProjectId,
        state: AgentJobState,
    ): Flow<AgentJobRecord>

    suspend fun findByFlavorAndStateOrderByCreatedAtDesc(
        flavor: AgentJobFlavor,
        state: AgentJobState,
    ): Flow<AgentJobRecord>

    /**
     * Non-terminal records — used by the K8s watcher on orchestrator
     * boot to reconcile against live Jobs in the cluster. The legacy
     * TaskDocument had separate state+state queries for the same
     * purpose; one parameter list covers it cleaner here.
     */
    suspend fun findByStateInOrderByCreatedAtAsc(states: List<AgentJobState>): Flow<AgentJobRecord>

    /**
     * Concurrency gate — dispatcher counts how many Jobs are already
     * consuming the MAX-plan budget before creating another K8s Job.
     */
    suspend fun countByState(state: AgentJobState): Long

    suspend fun countByClientIdAndState(clientId: ClientId, state: AgentJobState): Long

    suspend fun findByKubernetesJobName(kubernetesJobName: String): AgentJobRecord?
}
