package com.jervis.koog.qualifier.state

import java.time.Instant

/**
 * Processing metrics for diagnostics and monitoring.
 * Tracks timing, token usage, and errors throughout the pipeline.
 */
data class ProcessingMetrics(
    val startTime: Instant = Instant.now(),
    val phase0VisionDuration: Long? = null, // milliseconds
    val phase1DetectionDuration: Long? = null,
    val phase2ExtractionDuration: Long? = null,
    val phase3IndexingDuration: Long? = null,
    val phase4RoutingDuration: Long? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val errors: List<String> = emptyList(),
) {
    fun withError(error: String): ProcessingMetrics = copy(errors = errors + error)

    fun withPhase0Duration(duration: Long): ProcessingMetrics = copy(phase0VisionDuration = duration)

    fun withPhase1Duration(duration: Long): ProcessingMetrics = copy(phase1DetectionDuration = duration)

    fun withPhase2Duration(duration: Long): ProcessingMetrics = copy(phase2ExtractionDuration = duration)

    fun withPhase3Duration(duration: Long): ProcessingMetrics = copy(phase3IndexingDuration = duration)

    fun withPhase4Duration(duration: Long): ProcessingMetrics = copy(phase4RoutingDuration = duration)
}
