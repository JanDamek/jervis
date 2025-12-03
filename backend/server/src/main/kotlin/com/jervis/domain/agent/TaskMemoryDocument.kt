package com.jervis.domain.agent

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * TaskMemoryDocument – Context storage for passing information between Qualifier and Workflow agents.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Stores context from KoogQualifierAgent (CPU) for KoogWorkflowAgent (GPU)
 * - Avoids re-reading full documents - GPU agent loads structured context
 * - Includes: key findings, action items, Graph references, RAG document IDs
 * - One-to-one mapping with PendingTask (via correlationId)
 *
 * Lifecycle:
 * 1. Qualifier creates TaskMemory with contextSummary after structuring data
 * 2. If task routed to GPU: Workflow agent loads TaskMemory instead of raw content
 * 3. TaskMemory persists for audit trail (what Qualifier found/decided)
 * 4. Deleted when parent PendingTask is deleted
 *
 * Content:
 * - contextSummary: Brief overview for GPU agent (findings, action items, questions)
 * - graphNodeKeys: List of Graph nodes created by Qualifier
 * - ragDocumentIds: List of RAG documents indexed by Qualifier
 * - structuredData: Key-value metadata extracted during qualification
 * - routingDecision: DONE or READY_FOR_GPU (from TaskRoutingTool)
 * - routingReason: Why this routing decision was made
 */
@Document(collection = "task_memory")
data class TaskMemoryDocument(
    @Id
    val id: ObjectId = ObjectId(),

    /** PendingTask correlation ID (unique per task) */
    @Indexed(unique = true)
    val correlationId: String,

    /** Client ID (isolation per-client) */
    @Indexed
    val clientId: ObjectId,

    /** Project ID (optional) */
    @Indexed
    val projectId: ObjectId? = null,

    /** When this memory was created (typically after Qualifier completes) */
    val createdAt: Instant = Instant.now(),

    /** Context summary for GPU agent (brief overview, findings, action items) */
    val contextSummary: String,

    /** Graph node keys created during qualification (e.g., ['email::msg123', 'user::john']) */
    val graphNodeKeys: List<String> = emptyList(),

    /** RAG document IDs created during qualification (e.g., ['doc123', 'doc123:chunk:0']) */
    val ragDocumentIds: List<String> = emptyList(),

    /** Structured metadata extracted during qualification */
    val structuredData: Map<String, String> = emptyMap(),

    /** Routing decision: DONE or READY_FOR_GPU */
    val routingDecision: String,

    /** Reason for routing decision */
    val routingReason: String,

    /** Source document type: EMAIL, JIRA, GIT_COMMIT, CONFLUENCE, etc. */
    val sourceType: String? = null,

    /** Source document ID (e.g., email MongoDB ID, Jira issue key, commit hash) */
    val sourceId: String? = null,
)
