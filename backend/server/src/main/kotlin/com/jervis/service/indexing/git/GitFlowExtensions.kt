package com.jervis.service.indexing.git

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import mu.KLogger

/**
 * Collect Git operation Flow with standardized logging.
 * Handles all GitOperationResult types with structured logging.
 * Does NOT log errors - lets caller handle error logging to avoid duplication.
 */
suspend fun Flow<GitOperationResult>.collectWithLogging(
    logger: KLogger,
    context: String,
) {
    this
        .onEach { result ->
            when (result) {
                is GitOperationResult.Started -> {
                    logger.debug { "Started ${result.operation} for $context" }
                }

                is GitOperationResult.Success -> {
                    logger.debug { "Success ${result.operation} for $context" }
                }

                is GitOperationResult.Failed -> {
                    logger.warn { "Failed ${result.operation} (attempt ${result.attempt}): ${result.error}" }
                }

                is GitOperationResult.Retry -> {
                    logger.warn {
                        "Retrying ${result.operation} (attempt ${result.attempt}) after ${result.delayMs}ms"
                    }
                }

                is GitOperationResult.Completed -> {
                    logger.info { "Completed ${result.operation} for $context" }
                }
            }
        }.collect()
}
