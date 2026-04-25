package com.jervis.service.agentjob

import com.jervis.dto.agentjob.AgentJobListSnapshot
import com.jervis.dto.agentjob.AgentNarrativeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * IAgentJobService — kRPC push surface for the sidebar Background section.
 *
 * Two streams:
 *  - `subscribeAgentJobs(...)` — replay-1 Flow of `AgentJobListSnapshot`.
 *    First emission is the current snapshot; every subsequent emission
 *    is a fresh full snapshot triggered by an
 *    `AgentJobStateChanged` server event (Fáze G).
 *  - `subscribeAgentNarrative(agentJobId)` — Claude CLI parsed JSONL
 *    stream from `.jervis/claude-stream.jsonl` in the agent worktree.
 *    Live tail for RUNNING jobs; batch replay for terminal jobs.
 *
 * Per `architecture-push-only-streams.md` rule #9 — sidebar lives on a
 * single Flow per scope; no refresh button, no event→reload bounce.
 */
@Rpc
interface IAgentJobService {
    /**
     * Push stream of grouped agent-job snapshots.
     *
     * @param clientId Optional scope filter — null means all clients
     *                  (sidebar Background section is GLOBAL by default).
     * @param projectId Optional further scope filter.
     * @param includeTerminalForHours How far back to include terminal
     *                  records (DONE / ERROR / CANCELLED) in the
     *                  `recent` group. Default 24 h matches the brief.
     */
    fun subscribeAgentJobs(
        clientId: String? = null,
        projectId: String? = null,
        includeTerminalForHours: Int = 24,
    ): Flow<AgentJobListSnapshot>

    /**
     * Live narrative stream for one specific agent job — strikingly
     * different from `get_pod_logs` raw stdout. The event types are
     * semantic (assistant text vs tool call vs tool result vs system),
     * parsed from the agent's `.jervis/claude-stream.jsonl` (Fáze I).
     *
     * For RUNNING jobs the file is tailed live (poll every 1 s). For
     * terminal jobs the existing file is replayed in one batch then
     * the flow completes.
     */
    fun subscribeAgentNarrative(agentJobId: String): Flow<AgentNarrativeEvent>
}
