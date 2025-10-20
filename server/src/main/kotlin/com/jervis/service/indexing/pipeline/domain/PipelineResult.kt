package com.jervis.service.indexing.pipeline.domain

/**
 * Wrapper for pipeline operations with error handling
 */
sealed class PipelineResult<out T> {
    data class Success<T>(
        val value: T,
    ) : PipelineResult<T>()
}
