package com.jervis.service

import com.jervis.dto.AddLogRequest
import com.jervis.dto.FailIndexingRequest
import com.jervis.dto.StartIndexingRequest
import com.jervis.dto.UpdateStepRequest
import com.jervis.service.indexing.monitoring.ProjectIndexingState

interface IIndexingMonitorService {
    suspend fun startProjectIndexing(request: StartIndexingRequest)

    suspend fun updateStepProgress(request: UpdateStepRequest)

    suspend fun addStepLog(request: AddLogRequest)

    suspend fun completeProjectIndexing(projectId: String)

    suspend fun failProjectIndexing(request: FailIndexingRequest)

    fun getAllProjectStates(): Map<String, ProjectIndexingState>
}
