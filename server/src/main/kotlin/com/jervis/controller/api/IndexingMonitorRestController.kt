package com.jervis.controller.api

import com.jervis.domain.IndexingStepStatusEnum
import com.jervis.domain.IndexingStepTypeEnum
import com.jervis.dto.AddLogRequestDto
import com.jervis.dto.FailIndexingRequestDto
import com.jervis.dto.StartIndexingRequestDto
import com.jervis.dto.UpdateStepRequestDto
import com.jervis.dto.monitoring.ProjectIndexingStateDto
import com.jervis.service.IIndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController

@RestController
class IndexingMonitorRestController(
    private val indexingMonitorService: IndexingMonitorService,
) : IIndexingMonitorService {
    override suspend fun startProjectIndexing(request: StartIndexingRequestDto) {
        indexingMonitorService.startProjectIndexing(
            projectId = ObjectId(request.projectId),
            projectName = request.projectName,
        )
    }

    override suspend fun updateStepProgress(request: UpdateStepRequestDto) {
        indexingMonitorService.updateStepProgress(
            projectId = ObjectId(request.projectId),
            stepType = IndexingStepTypeEnum.valueOf(request.stepType),
            status = IndexingStepStatusEnum.valueOf(request.status),
            progress = request.progress,
            message = request.message,
            errorMessage = request.errorMessage,
            logs = request.logs ?: emptyList(),
        )
    }

    override suspend fun addStepLog(request: AddLogRequestDto) {
        indexingMonitorService.addStepLog(
            projectId = ObjectId(request.projectId),
            stepType = IndexingStepTypeEnum.valueOf(request.stepType),
            logMessage = request.logMessage,
        )
    }

    override suspend fun completeProjectIndexing(projectId: String) {
        indexingMonitorService.completeProjectIndexing(ObjectId(projectId))
    }

    override suspend fun failProjectIndexing(request: FailIndexingRequestDto) {
        indexingMonitorService.failProjectIndexing(
            projectId = ObjectId(request.projectId),
            errorMessage = request.errorMessage,
        )
    }

    override fun getAllProjectStates(): Map<String, ProjectIndexingStateDto> =
        indexingMonitorService.getAllProjectStates().mapKeys { it.key.toHexString() }
}
