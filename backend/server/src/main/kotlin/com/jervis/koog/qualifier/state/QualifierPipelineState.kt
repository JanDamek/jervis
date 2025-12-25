package com.jervis.koog.qualifier.state

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.entity.TaskDocument
import com.jervis.koog.qualifier.types.ContentType
import com.jervis.koog.qualifier.types.ContentTypeDetection
import com.jervis.koog.qualifier.types.ExtractionResult
import com.jervis.koog.qualifier.types.VisionContext

/**
 * Qualifier Pipeline State - Single Source of Truth.
 *
 * This is the ONLY state object that flows through the entire qualifier strategy graph.
 * It accumulates all intermediate results from each phase without ever losing context.
 *
 * **Design Principles:**
 * 1. Immutability - all updates via `.copy()`
 * 2. Never null out previous results - VisionContext must persist through all phases
 * 3. Type-safe - sealed classes for extraction results
 * 4. Fail-fast - errors collected in metrics, not silently ignored
 *
 * **Anti-patterns eliminated:**
 * - ❌ Dummy state (returning empty/default state)
 * - ❌ Lost vision context (creating new empty VisionContext)
 * - ❌ Mutable external state (var outside nodes)
 * - ❌ String-based state (parsing instead of types)
 *
 * **Phase progression:**
 * - Phase 0: Initialize → Run Vision Stage 1 → state.vision populated
 * - Phase 0.5: Detect content type → Run Vision Stage 2 (if applicable) → state.vision enriched
 * - Phase 1: Content type detection → state.contentType set
 * - Phase 2: Type-specific extraction → state.extraction populated
 * - Phase 3: Build indexing state → Chunk processing → state.indexing updated
 * - Phase 4: Routing decision → state.routingDecision set
 *
 * @property taskMeta Immutable task context (never changes)
 * @property originalText Original text content from task
 * @property attachments List of attachments (may be enriched with vision analysis)
 * @property vision Vision analysis results (Stage 1 + Stage 2) - NEVER null after Stage 1!
 * @property contentTypeDetection LLM's content type detection (structured output)
 * @property contentType Detected content type (enum)
 * @property extraction Type-specific extraction result (sealed class)
 * @property indexing Unified indexing state (base node + chunks)
 * @property routingDecision Final routing decision (DONE or LIFT_UP)
 * @property metrics Processing metrics and errors
 */
data class QualifierPipelineState(
    // Immutable context
    val taskMeta: TaskMetadata,
    val originalText: String,
    val attachments: List<AttachmentMetadata>,
    // Phase 0: Vision (NEVER lost after Stage 1!)
    val vision: VisionContext,
    // Phase 1: Content type detection
    val contentTypeDetection: ContentTypeDetection?,
    val contentType: ContentType,
    // Phase 2: Type-specific extraction
    val extraction: ExtractionResult?,
    // Phase 3: Indexing
    val indexing: IndexingState,
    // Phase 4: Routing
    val routingDecision: RoutingDecision?,
    // Diagnostics
    val metrics: ProcessingMetrics,
) {
    /**
     * Update vision context (Stage 1 or Stage 2).
     */
    fun withVision(vision: VisionContext): QualifierPipelineState = copy(vision = vision)

    /**
     * Update content type detection results.
     */
    fun withContentType(
        detection: ContentTypeDetection?,
        contentType: ContentType,
    ): QualifierPipelineState = copy(contentTypeDetection = detection, contentType = contentType)

    /**
     * Update extraction result.
     */
    fun withExtraction(extraction: ExtractionResult): QualifierPipelineState = copy(extraction = extraction)

    /**
     * Update indexing state.
     */
    fun withIndexing(indexing: IndexingState): QualifierPipelineState = copy(indexing = indexing)

    /**
     * Update routing decision.
     */
    fun withRouting(decision: RoutingDecision): QualifierPipelineState = copy(routingDecision = decision)

    /**
     * Update enriched attachments (after vision analysis).
     */
    fun withAttachments(attachments: List<AttachmentMetadata>): QualifierPipelineState = copy(attachments = attachments)

    /**
     * Add error to metrics.
     */
    fun withError(error: String): QualifierPipelineState = copy(metrics = metrics.withError(error))

    /**
     * Update metrics.
     */
    fun withMetrics(updater: (ProcessingMetrics) -> ProcessingMetrics): QualifierPipelineState = copy(metrics = updater(metrics))

    companion object {
        /**
         * Initialize pipeline state from task document.
         *
         * This is the ONLY entry point for creating QualifierPipelineState.
         * Called once at the beginning of the strategy graph (nodeStart).
         *
         * @param task Pending task document
         * @return Initial pipeline state with empty/default values
         */
        fun initial(task: TaskDocument): QualifierPipelineState =
            QualifierPipelineState(
                taskMeta =
                    TaskMetadata(
                        correlationId = task.correlationId,
                        clientId = task.clientId,
                        projectId = task.projectId,
                        sourceUrn = task.sourceUrn,
                    ),
                originalText = task.content,
                attachments = task.attachments,
                vision =
                    VisionContext(
                        originalText = task.content,
                        generalVisionSummary = null,
                        typeSpecificVisionDetails = null,
                        attachments = task.attachments,
                    ),
                contentTypeDetection = null,
                contentType = ContentType.GENERIC, // Default, will be detected in Phase 1
                extraction = null,
                indexing = IndexingState.empty(),
                routingDecision = null,
                metrics = ProcessingMetrics(),
            )
    }
}
