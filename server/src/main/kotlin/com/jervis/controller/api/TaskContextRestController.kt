package com.jervis.controller.api

import com.jervis.dto.CreateContextRequestDto
import com.jervis.dto.TaskContextDto
import com.jervis.mapper.toDomain
import com.jervis.mapper.toDto
import com.jervis.service.ITaskContextService
import com.jervis.service.agent.context.TaskContextService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TaskContextRestController(
    private val taskContextService: TaskContextService,
) : ITaskContextService {
    override suspend fun create(
        @RequestBody requestDto: CreateContextRequestDto,
    ): TaskContextDto =
        taskContextService
            .create(
                clientId = ObjectId(requestDto.clientId),
                projectId = ObjectId(requestDto.projectId),
                quick = requestDto.quick,
                contextName = requestDto.contextName,
            ).toDto()

    override suspend fun save(
        @RequestBody context: TaskContextDto,
    ): TaskContextDto {
        taskContextService.save(context.toDomain())
        return context
    }

    override suspend fun findById(
        @PathVariable contextId: String,
    ): TaskContextDto? = taskContextService.findById(ObjectId(contextId))?.toDto()

    override suspend fun listForClient(
        @RequestParam clientId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), null)
            .map { it.toDto() }
            .toList()

    suspend fun listForClientPath(
        @PathVariable clientId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), null)
            .map { it.toDto() }
            .toList()

    override suspend fun listForClientAndProject(
        @RequestParam clientId: String,
        @RequestParam projectId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), ObjectId(projectId))
            .map { it.toDto() }
            .toList()

    suspend fun listForClientAndProjectPath(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), ObjectId(projectId))
            .map { it.toDto() }
            .toList()

    override suspend fun delete(
        @PathVariable contextId: String,
    ) {
        taskContextService.delete(ObjectId(contextId))
    }
}
