package com.jervis.service.background.executor

import com.jervis.domain.background.BackgroundArtifact
import com.jervis.domain.background.BackgroundTask
import com.jervis.domain.background.Checkpoint

/**
 * Base interface for all background task executors.
 *
 * Each executor is responsible for processing one chunk of work for a specific task type.
 */
interface BackgroundTaskExecutor {
    /**
     * Executes a single chunk of the background task.
     *
     * Should be designed to:
     * - Complete within chunkTimeoutSeconds (typically 45s)
     * - Be interruptible (check coroutineContext.isActive)
     * - Return idempotent artifacts (with contentHash for deduplication)
     *
     * @param task The task to execute (includes checkpoint for continuation)
     * @return Result containing artifacts, new checkpoint, and progress delta
     */
    suspend fun executeChunk(task: BackgroundTask): ChunkResult
}

/**
 * Result of executing a single chunk.
 */
data class ChunkResult(
    val artifacts: List<BackgroundArtifact>,
    val checkpoint: Checkpoint?,
    val progressDelta: Double,
    val nextAction: NextAction = NextAction.CONTINUE,
)

enum class NextAction {
    CONTINUE,
    REQUEST_MORE_CONTEXT,
    STOP,
}
