package com.jervis.dto.indexing

import kotlinx.serialization.Serializable

/**
 * Type of item in the indexing queue.
 */
@Serializable
enum class IndexingItemType {
    GIT_COMMIT,
    EMAIL,
    BUGTRACKER_ISSUE,
    WIKI_PAGE,
}

/**
 * A single item in the indexing queue (pending or indexed).
 */
@Serializable
data class IndexingQueueItemDto(
    val id: String,
    val type: IndexingItemType,
    val connectionName: String,
    val connectionId: String,
    val clientName: String,
    val projectName: String?,
    /** Commit message / email subject / issue key+summary / wiki page title */
    val title: String,
    /** ISO 8601 timestamp */
    val createdAt: String?,
    /** NEW, INDEXED, FAILED, INDEXING */
    val state: String,
    val errorMessage: String? = null,
    /** Source URN for KB reference (e.g. "github-issue::conn:abc,issueKey:PROJ-123") */
    val sourceUrn: String? = null,
)

/**
 * Paginated response of indexing queue items.
 */
@Serializable
data class IndexingQueuePageDto(
    val items: List<IndexingQueueItemDto>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
)

// ── Dashboard DTOs (hierarchical: connection → capability → client) ──

/**
 * Items grouped by client within a capability.
 */
@Serializable
data class ClientItemGroupDto(
    val clientId: String,
    val clientName: String,
    /** Items for this client (may be truncated). */
    val items: List<IndexingQueueItemDto>,
    val totalItemCount: Int,
)

/**
 * Items grouped by capability within a connection.
 * Each capability has its own polling interval and next check time.
 */
@Serializable
data class CapabilityGroupDto(
    /** Capability enum name: REPOSITORY, BUGTRACKER, WIKI, EMAIL_READ */
    val capability: String,
    /** ISO 8601 – next scheduled poll for this capability. */
    val nextCheckAt: String?,
    /** Current polling interval in minutes. */
    val intervalMinutes: Int,
    /** Items grouped by client. */
    val clients: List<ClientItemGroupDto>,
    val totalItemCount: Int,
)

/**
 * Top-level group: one connection with its capabilities and their items.
 * Hierarchy: Connection → Capability → Client → Items
 */
@Serializable
data class ConnectionIndexingGroupDto(
    /** Connection ID, empty string for Git commits (no connection entity). */
    val connectionId: String,
    val connectionName: String,
    /** ProviderEnum name or "GIT" for synthetic group. */
    val provider: String,
    /** ISO 8601 – last time this connection was polled. */
    val lastPolledAt: String?,
    /** Capabilities with their items, grouped by client. */
    val capabilityGroups: List<CapabilityGroupDto>,
    /** Total pending item count across all capabilities. */
    val totalItemCount: Int,
)

// ── Qualification step DTO (persisted progress history) ──

/**
 * A single qualification progress step, transferred from backend to UI.
 */
@Serializable
data class QualificationStepDto(
    /** ISO 8601 timestamp of the step. */
    val timestamp: String,
    /** Step identifier (start, content_ready, rag_done, summary_done, routing, done, etc.) */
    val step: String,
    /** Human-readable message. */
    val message: String,
    /** Structured metadata (counts, decisions, etc.) */
    val metadata: Map<String, String> = emptyMap(),
)

// ── Pipeline item DTO (used for KB queue + execution pipeline) ──

/**
 * Item in the processing pipeline (KB qualification or execution).
 * Represents a task flowing through: KB wait → KB processing → execution wait → executing.
 */
@Serializable
data class PipelineItemDto(
    val id: String,
    val type: IndexingItemType,
    val title: String,
    val connectionName: String,
    val clientName: String,
    /** Source URN for KB reference. */
    val sourceUrn: String?,
    /** ISO 8601 – when the item entered this stage. */
    val createdAt: String?,
    /** Pipeline state: WAITING, QUALIFYING, RETRYING, READY_FOR_GPU, DISPATCHED_GPU, PYTHON_ORCHESTRATING */
    val pipelineState: String,
    /** Number of retry attempts (for items in backoff). */
    val retryCount: Int = 0,
    /** ISO 8601 – when next retry will happen (for items in backoff). */
    val nextRetryAt: String? = null,
    /** Error message from last attempt (for retrying items). */
    val errorMessage: String? = null,
    /** Task ID for reordering. */
    val taskId: String? = null,
    /** Queue position (null = FIFO by createdAt). */
    val queuePosition: Int? = null,
    /** Processing mode: FOREGROUND (user chat, critical) or BACKGROUND (autonomous). */
    val processingMode: String? = null,
    /** ISO 8601 – when qualification actually started (not queue creation time). */
    val qualificationStartedAt: String? = null,
    /** Persisted qualification progress steps (for history display in Hotovo). */
    val qualificationSteps: List<QualificationStepDto> = emptyList(),
)

/**
 * Combined dashboard response: indexing queue + full processing pipeline.
 */
@Serializable
data class IndexingDashboardDto(
    /** Pending indexing items grouped by connection → capability → client. */
    val connectionGroups: List<ConnectionIndexingGroupDto>,

    // ── KB qualification stage ──
    /** Items waiting for KB qualification (READY_FOR_QUALIFICATION). */
    val kbWaiting: List<PipelineItemDto>,
    val kbWaitingTotalCount: Long,
    /** Items currently being qualified by KB/Ollama (QUALIFYING). */
    val kbProcessing: List<PipelineItemDto>,
    val kbProcessingCount: Long,

    // ── Execution stage ──
    /** Items waiting for GPU execution (READY_FOR_GPU). */
    val executionWaiting: List<PipelineItemDto>,
    val executionWaitingCount: Long,
    /** Items currently being executed (DISPATCHED_GPU + PYTHON_ORCHESTRATING). */
    val executionRunning: List<PipelineItemDto>,
    val executionRunningCount: Long,

    // ── Completed items ──
    /** Items successfully inserted into KB (INDEXED). */
    val kbIndexed: List<PipelineItemDto>,
    val kbIndexedTotalCount: Long,

    // ── Pagination (for kbWaiting section) ──
    val kbPage: Int = 0,
    val kbPageSize: Int = 20,
)
