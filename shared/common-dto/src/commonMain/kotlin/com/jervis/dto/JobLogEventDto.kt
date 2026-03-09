package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Single log event from a coding agent K8s Job.
 *
 * Streamed via kRPC Flow to UI for live output display.
 */
@Serializable
data class JobLogEventDto(
    /** Event type: "text", "tool_call", "result", "log", "status", "error", "done" */
    val type: String,
    /** Human-readable content */
    val content: String,
    /** Tool name (for tool_call events) */
    val tool: String = "",
)
