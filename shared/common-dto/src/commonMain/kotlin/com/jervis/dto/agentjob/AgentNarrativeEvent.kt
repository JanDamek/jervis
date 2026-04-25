package com.jervis.dto.agentjob

import kotlinx.serialization.Serializable

/**
 * Structured Claude CLI narrative event — semantic projection of a single
 * line in the agent's `.jervis/claude-stream.jsonl` file (Fáze I).
 *
 * Sidebar Background detail panel (Fáze K) renders these as scrollable
 * activity log:
 *  - 💬 AssistantText  — Claude's user-facing prose
 *  - 🔧 ToolUse        — tool name + a one-line params summary
 *  - ✓/✗ ToolResult    — tool name + isError + short output preview
 *  - ⚙ SystemEvent    — control messages (compact, init, etc.)
 *
 * `subscribeAgentNarrative(agentJobId)`:
 *  - tails the file live for RUNNING jobs (last-position read, then
 *    inotify / poll on the worktree path)
 *  - replays the entire file as a batch for terminal jobs
 *
 * `timestamp` is the moment the line was parsed on the server (the file
 * itself doesn't carry per-line timestamps in stream-json — Claude CLI
 * writes them in some events but not all).
 */
@Serializable
sealed class AgentNarrativeEvent {
    abstract val timestamp: String

    @Serializable
    data class AssistantText(
        override val timestamp: String,
        val text: String,
    ) : AgentNarrativeEvent()

    @Serializable
    data class ToolUse(
        override val timestamp: String,
        val toolName: String,
        /** Short, redacted params summary — never the full input. */
        val paramsPreview: String,
    ) : AgentNarrativeEvent()

    @Serializable
    data class ToolResult(
        override val timestamp: String,
        val toolName: String,
        val isError: Boolean,
        val outputPreview: String,
    ) : AgentNarrativeEvent()

    @Serializable
    data class SystemEvent(
        override val timestamp: String,
        val kind: String,
        val content: String,
    ) : AgentNarrativeEvent()

    /**
     * Emitted once at the head of a `subscribeAgentNarrative` flow when
     * the server cannot find the agent's stream file (worktree
     * cleaned up after TTL, or the agent was dispatched before Fáze I
     * landed and never wrote a JSONL file). UI shows a placeholder
     * "no narrative recorded" message.
     */
    @Serializable
    data class NarrativeUnavailable(
        override val timestamp: String,
        val reason: String,
    ) : AgentNarrativeEvent()
}
