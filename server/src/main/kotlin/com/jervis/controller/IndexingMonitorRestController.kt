package com.jervis.controller

import com.jervis.service.IIndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingProgress
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
    private val indexingMonitorService: IIndexingMonitorService,
) {
    @PostMapping("/start")
    suspend fun startProjectIndexing(
        @RequestBody request: StartIndexingRequest,
    ) {
        indexingMonitorService.startProjectIndexing(
            projectId = ObjectId(request.projectId),
            projectName = request.projectName,
        )
    }

    @PostMapping("/update-step")
    suspend fun updateStepProgress(
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
    suspend fun addStepLog(
        @RequestBody request: AddLogRequest,
    ) {
        indexingMonitorService.addStepLog(
            projectId = ObjectId(request.projectId),
            stepType = IndexingStepType.valueOf(request.stepType),
            logMessage = request.logMessage,
        )
    }

    @PostMapping("/complete/{projectId}")
    suspend fun completeProjectIndexing(
        @PathVariable projectId: String,
    ) {
        indexingMonitorService.completeProjectIndexing(ObjectId(projectId))
    }

    @PostMapping("/fail")
    suspend fun failProjectIndexing(
        @RequestBody request: FailIndexingRequest,
    ) {
        indexingMonitorService.failProjectIndexing(
            projectId = ObjectId(request.projectId),
            errorMessage = request.errorMessage,
        )
    }

    @GetMapping("/states")
    fun getAllProjectStates(): Map<String, ProjectIndexingState> =
        indexingMonitorService.getAllProjectStates().mapKeys { it.key.toHexString() }
}

data class StartIndexingRequest(
    val projectId: String,
    val projectName: String,
)

data class UpdateStepRequest(
    val projectId: String,
    val stepType: String,
    val status: String,
    val progress: IndexingProgress?,
    val message: String?,
    val errorMessage: String?,
    val logs: List<String>?,
)

data class AddLogRequest(
    val projectId: String,
    val stepType: String,
    val logMessage: String,
)

data class FailIndexingRequest(
    val projectId: String,
    val errorMessage: String,
)
