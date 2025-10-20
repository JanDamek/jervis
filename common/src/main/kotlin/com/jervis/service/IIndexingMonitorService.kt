package com.jervis.service

import com.jervis.dto.AddLogRequestDto
import com.jervis.dto.FailIndexingRequestDto
import com.jervis.dto.StartIndexingRequestDto
import com.jervis.dto.UpdateStepRequestDto
import com.jervis.dto.monitoring.ProjectIndexingStateDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/indexing-monitor")
interface IIndexingMonitorService {
    @PostExchange("/start")
    suspend fun startProjectIndexing(
        @RequestBody request: StartIndexingRequestDto,
    )

    @PostExchange("/update-step")
    suspend fun updateStepProgress(
        @RequestBody request: UpdateStepRequestDto,
    )

    @PostExchange("/add-log")
    suspend fun addStepLog(
        @RequestBody request: AddLogRequestDto,
    )

    @PostExchange("/complete/{projectId}")
    suspend fun completeProjectIndexing(
        @PathVariable projectId: String,
    )

    @PostExchange("/fail")
    suspend fun failProjectIndexing(
        @RequestBody request: FailIndexingRequestDto,
    )

    @GetExchange("/states")
    fun getAllProjectStates(): Map<String, ProjectIndexingStateDto>
}
