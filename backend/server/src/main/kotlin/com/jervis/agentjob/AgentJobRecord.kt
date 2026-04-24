package com.jervis.agentjob

import com.jervis.common.types.AgentJobId
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.agentjob.AgentJobFlavor
import com.jervis.dto.agentjob.AgentJobState
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * `AgentJobRecord` — persistent lifecycle record of a Claude CLI K8s Job
 * delegated by the Jervis chat manager or the Kotlin scheduler.
 *
 * Scope is derived from (`clientId`, `projectId`):
 *
 * | clientId | projectId | scope   |
 * |----------|-----------|---------|
 * | null     | null      | GLOBAL  |
 * | set      | null      | CLIENT  |
 * | set      | set       | PROJECT |
 *
 * The record survives orchestrator pod restart — its state is authoritative
 * in MongoDB, no in-memory cache holds the lifecycle. A watcher
 * reconciles the record against the real K8s Job state on boot and
 * through cluster events, so a killed orchestrator reattaches to live
 * Jobs automatically.
 *
 * This entity intentionally does NOT replicate the legacy `TaskDocument`
 * sprawl (qualification steps, orchestrator threads, parent-child
 * task graph, presence flags, …). Those were artifacts of the
 * LangGraph pipeline; the Claude CLI manager keeps its own narrative
 * in `compact_snapshots`, its strategic context in Thought Map, and
 * its tactical state in `claude_scratchpad`. Audit fields that would
 * only feed an operator dashboard are omitted by design — nothing in
 * the new pipeline reads them.
 *
 * Writing is the Kotlin server's responsibility (dispatcher, watcher,
 * scheduler). Claude reads through MCP tools (`list_agent_jobs`,
 * `get_agent_job_status`) and triggers state changes via explicit
 * write tools (`dispatch_agent_job`, `abort_agent_job`); Claude
 * never mutates this document directly.
 */
@Document(collection = "agent_job_records")
@CompoundIndexes(
    CompoundIndex(name = "state_createdAt", def = "{'state': 1, 'createdAt': -1}"),
    CompoundIndex(name = "client_state", def = "{'clientId': 1, 'state': 1, 'createdAt': -1}"),
    CompoundIndex(name = "project_state", def = "{'projectId': 1, 'state': 1, 'createdAt': -1}"),
    CompoundIndex(name = "flavor_state", def = "{'flavor': 1, 'state': 1}"),
)
data class AgentJobRecord(
    @Id
    val id: AgentJobId = AgentJobId.generate(),
    @Indexed
    val flavor: AgentJobFlavor,
    /** Null for GLOBAL scope. */
    @Indexed
    val clientId: ClientId? = null,
    /** Null for CLIENT or GLOBAL scope. */
    val projectId: ProjectId? = null,
    val title: String,
    /** Brief / prompt the Job uses as its task description. For coding
     * flavor this lands in CLAUDE.md; for analysis/research in a
     * system prompt. */
    val description: String,
    /** Origin of the dispatch — free-form string used for audit only.
     * Conventional values: `claude-session:<sid>`, `scheduler:<triggerName>`,
     * `user:<userId>`. Must be non-empty. */
    val dispatchedBy: String,
    @Indexed
    val state: AgentJobState = AgentJobState.QUEUED,
    /** K8s Job name once dispatched. Null while QUEUED. */
    @Indexed
    val kubernetesJobName: String? = null,
    /** Which `ProjectResource` (repository) the Job operates on — matches
     * `ProjectDocument.resources[].id`. Required for CODING flavor when
     * a project owns more than one repo, so the dispatcher knows which
     * checkout to materialise. Null for non-coding flavors. */
    val resourceId: String? = null,
    /** Per-agent-job worktree path on the shared PVC.
     * `clients/<cid>/projects/<pid>/agent-jobs/<agentJobId>/` — created by
     * `AgentWorkspaceService.prepareWorktreeForJob`. Null while QUEUED
     * or for flavors that don't need code access. */
    val workspacePath: String? = null,
    /** Git branch the Job operates on (created + checked out by the
     * server before Job start). The Job verifies but never switches.
     * CODING flavor only. */
    val gitBranch: String? = null,
    /** Commit SHA pushed by the Job. Populated on DONE for CODING
     * flavor; null otherwise. */
    val gitCommitSha: String? = null,
    /** Short summary Claude emits on done (`report_done` callback). */
    val resultSummary: String? = null,
    /** Opaque artifact references — KB node keys, file paths, URLs.
     * Free-form list the handler decides how to fill. */
    val artifacts: List<String> = emptyList(),
    /** Set when state=WAITING_USER. UI "K reakci" surfaces this text. */
    val askUserQuestion: String? = null,
    /** Set when state=ERROR. */
    val errorMessage: String? = null,
    @Indexed
    val createdAt: Instant = Instant.now(),
    /** Moment the K8s Job was created (transition QUEUED → RUNNING). */
    val startedAt: Instant? = null,
    /** Moment the Job reached a terminal state. */
    val completedAt: Instant? = null,
) {
    companion object {
        /**
         * Spring Data factory with primitive types to work around Kotlin
         * inline value class parameter name mangling. Value class
         * parameters get mangled bytecode names (`id` → `id-<hash>`),
         * which breaks Spring Data's constructor resolution. This
         * factory uses raw ObjectId/String so Spring Data can match
         * parameter names.
         */
        @PersistenceCreator
        @JvmStatic
        fun create(
            id: ObjectId,
            flavor: AgentJobFlavor,
            clientId: ObjectId?,
            projectId: ObjectId?,
            title: String,
            description: String,
            dispatchedBy: String,
            state: AgentJobState?,
            kubernetesJobName: String?,
            resourceId: String?,
            workspacePath: String?,
            gitBranch: String?,
            gitCommitSha: String?,
            resultSummary: String?,
            artifacts: List<String>?,
            askUserQuestion: String?,
            errorMessage: String?,
            createdAt: Instant?,
            startedAt: Instant?,
            completedAt: Instant?,
        ): AgentJobRecord = AgentJobRecord(
            id = AgentJobId(id),
            flavor = flavor,
            clientId = clientId?.let { ClientId(it) },
            projectId = projectId?.let { ProjectId(it) },
            title = title,
            description = description,
            dispatchedBy = dispatchedBy,
            state = state ?: AgentJobState.QUEUED,
            kubernetesJobName = kubernetesJobName,
            resourceId = resourceId,
            workspacePath = workspacePath,
            gitBranch = gitBranch,
            gitCommitSha = gitCommitSha,
            resultSummary = resultSummary,
            artifacts = artifacts ?: emptyList(),
            askUserQuestion = askUserQuestion,
            errorMessage = errorMessage,
            createdAt = createdAt ?: Instant.now(),
            startedAt = startedAt,
            completedAt = completedAt,
        )
    }
}
