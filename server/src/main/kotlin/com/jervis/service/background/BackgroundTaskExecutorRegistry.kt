package com.jervis.service.background

import com.jervis.domain.background.BackgroundTaskType
import com.jervis.service.background.executor.BackgroundTaskExecutor
import org.springframework.stereotype.Component

/**
 * Registry for background task executors.
 *
 * Maps task types to their corresponding executor implementations.
 */
@Component
class BackgroundTaskExecutorRegistry(
    executors: List<BackgroundTaskExecutor>,
) {
    private val executorMap = mutableMapOf<BackgroundTaskType, BackgroundTaskExecutor>()

    init {
        executors.forEach { executor ->
            when (executor) {
                is com.jervis.service.background.executor.RagGapDiscoveryExecutor ->
                    executorMap[BackgroundTaskType.RAG_GAP_DISCOVERY] = executor

                is com.jervis.service.background.executor.ReplySuggestionsExecutor ->
                    executorMap[BackgroundTaskType.REPLY_SUGGESTIONS_PREP] = executor
            }
        }
    }

    fun getExecutor(taskType: BackgroundTaskType): BackgroundTaskExecutor =
        executorMap[taskType]
            ?: throw IllegalArgumentException("No executor registered for task type: $taskType")

    fun hasExecutor(taskType: BackgroundTaskType): Boolean = executorMap.containsKey(taskType)
}
