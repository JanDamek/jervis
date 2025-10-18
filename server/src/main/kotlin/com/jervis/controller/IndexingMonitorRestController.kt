package com.jervis.controller

import com.jervis.dto.AddLogRequest
import com.jervis.dto.FailIndexingRequest
import com.jervis.dto.StartIndexingRequest
import com.jervis.dto.UpdateStepRequest
import com.jervis.service.IIndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.indexing.monitoring.ProjectIndexingState
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/indexing-monitor")
class IndexingMonitorRestController(
    private val indexingMonitorService: IndexingMonitorService,
) : IIndexingMonitorService {
    @PostMapping("/start")
    override suspend fun startProjectIndexing(
        @RequestBody request: StartIndexingRequest,
    ) {
        indexingMonitorService.startProjectIndexing(
            projectId = ObjectId(request.projectId),
            projectName = request.projectName,
        )
    }

    @PostMapping("/update-step")
    override suspend fun updateStepProgress(
        @RequestBody request: UpdateStepRequest,
    ) {
        indexingMonitorService.updateStepProgress(
            projectId = ObjectId(request.projectId),
            stepType = IndexingStepType.valueOf(request.stepType),
            status = IndexingStepStatus.valueOf(request.status),
            progress = request.progress,
            message = request.message,
            errorMessage = request.errorMessage,
            logs = request.logs ?: emptyList(),
        )
    }

    @PostMapping("/add-log")
    override suspend fun addStepLog(
        @RequestBody request: AddLogRequest,
    ) {
        indexingMonitorService.addStepLog(
            projectId = ObjectId(request.projectId),
            stepType = IndexingStepType.valueOf(request.stepType),
            logMessage = request.logMessage,
        )
    }

    @PostMapping("/complete/{projectId}")
    override suspend fun completeProjectIndexing(
        @PathVariable projectId: String,
    ) {
        indexingMonitorService.completeProjectIndexing(ObjectId(projectId))
    }

    @PostMapping("/fail")
    override suspend fun failProjectIndexing(
        @RequestBody request: FailIndexingRequest,
    ) {
        indexingMonitorService.failProjectIndexing(
            projectId = ObjectId(request.projectId),
            errorMessage = request.errorMessage,
        )
    }

    @GetMapping("/states")
    override fun getAllProjectStates(): Map<String, ProjectIndexingState> =
        indexingMonitorService.getAllProjectStates().mapKeys { it.key.toHexString() }
}
