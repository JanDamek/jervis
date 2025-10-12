package com.jervis.service

import com.jervis.service.indexing.monitoring.IndexingProgress
import com.jervis.service.indexing.monitoring.IndexingProgressEvent
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.indexing.monitoring.ProjectIndexingState
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId

interface IIndexingMonitorService {
    val progressFlow: Flow<IndexingProgressEvent>

    suspend fun startProjectIndexing(
        projectId: ObjectId,
        projectName: String,
    )

    suspend fun updateStepProgress(
        projectId: ObjectId,
        stepType: IndexingStepType,
        status: IndexingStepStatus,
        progress: IndexingProgress? = null,
        message: String? = null,
        errorMessage: String? = null,
        logs: List<String> = emptyList(),
    )

    suspend fun addStepLog(
        projectId: ObjectId,
        stepType: IndexingStepType,
        logMessage: String,
    )

    suspend fun completeProjectIndexing(projectId: ObjectId)

    suspend fun failProjectIndexing(
        projectId: ObjectId,
        errorMessage: String,
    )

    fun getAllProjectStates(): Map<ObjectId, ProjectIndexingState>
}
