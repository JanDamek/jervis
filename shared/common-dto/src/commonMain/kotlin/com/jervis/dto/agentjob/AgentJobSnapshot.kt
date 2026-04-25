package com.jervis.dto.agentjob

import kotlinx.serialization.Serializable

/**
 * UI-facing snapshot of one `AgentJobRecord` row. Same wire shape as the
 * fields used by the sidebar Background section (Fáze K) and the
 * `JervisEvent.AgentJobStateChanged` push event (Fáze G); IAgentJobRpc
 * exposes both unary list reads and a live `subscribeAgentJobs` Flow.
 *
 * `state`, `flavor` are stringified enum names so the wire format stays
 * stable when new variants land.
 */
@Serializable
data class AgentJobSnapshot(
    val id: String,
    val flavor: String,                        // AgentJobFlavor.name
    val state: String,                         // AgentJobState.name
    val title: String,
    val clientId: String? = null,
    val projectId: String? = null,
    val resourceId: String? = null,
    val gitBranch: String? = null,
    val gitCommitSha: String? = null,
    val resultSummary: String? = null,
    val errorMessage: String? = null,
    val artifacts: List<String> = emptyList(),
    val createdAt: String,                     // ISO-8601
    val startedAt: String? = null,
    val completedAt: String? = null,
    /** Free-form audit string: claude-session:<sid>, scheduler:<trigger>, ... */
    val dispatchedBy: String = "",
    /**
     * Path to the agent's .jervis/claude-stream.jsonl on the shared PVC.
     * Set once the worktree exists; used by `subscribeAgentNarrative`
     * to tail / replay the Claude CLI event log.
     */
    val workspacePath: String? = null,
)
