package com.jervis.service

import com.jervis.dto.CreateContextRequestDto
import com.jervis.dto.TaskContextDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/task-contexts")
interface ITaskContextService {
    @GetExchange("/{contextId}")
    suspend fun findById(
        @PathVariable contextId: String,
    ): TaskContextDto?

    @GetExchange("/client/{clientId}")
    suspend fun listForClient(
        @PathVariable clientId: String,
    ): List<TaskContextDto>

    @GetExchange("/client/{clientId}/project/{projectId}")
    suspend fun listForClientAndProject(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): List<TaskContextDto>

    @PostExchange
    suspend fun create(@RequestBody requestDto: CreateContextRequestDto): TaskContextDto

    @PostExchange("/save")
    suspend fun save(@RequestBody context: TaskContextDto): TaskContextDto

    @DeleteExchange("/{contextId}")
    suspend fun delete(@PathVariable contextId: String)
}
