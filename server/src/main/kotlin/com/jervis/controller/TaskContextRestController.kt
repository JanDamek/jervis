package com.jervis.controller

import com.jervis.dto.CreateContextRequestDto
import com.jervis.dto.TaskContextDto
import com.jervis.mapper.toDomain
import com.jervis.mapper.toDto
import com.jervis.service.agent.context.TaskContextService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/task-contexts")
class TaskContextRestController(
    private val taskContextService: TaskContextService,
) {
    @PostMapping("/create")
    suspend fun create(
        @RequestBody requestDto: CreateContextRequestDto,
    ): TaskContextDto =
        taskContextService
            .create(
                clientId = ObjectId(requestDto.clientId),
                projectId = ObjectId(requestDto.projectId),
                quick = requestDto.quick,
                contextName = requestDto.contextName,
            ).toDto()

    @PostMapping("/save")
    suspend fun save(
        @RequestBody context: TaskContextDto,
    ): TaskContextDto {
        taskContextService.save(context.toDomain())
        return context
    }

    @GetMapping("/{contextId}")
    suspend fun findById(
        @PathVariable contextId: String,
    ): TaskContextDto? = taskContextService.findById(ObjectId(contextId))?.toDto()

    @GetMapping("/list")
    suspend fun listForClient(
        @RequestParam clientId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), null)
            .map { it.toDto() }

    @GetMapping("/client/{clientId}")
    suspend fun listForClientPath(
        @PathVariable clientId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), null)
            .map { it.toDto() }

    @GetMapping("/list-for-project")
    suspend fun listForClientAndProject(
        @RequestParam clientId: String,
        @RequestParam projectId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), ObjectId(projectId))
            .map { it.toDto() }

    @GetMapping("/client/{clientId}/project/{projectId}")
    suspend fun listForClientAndProjectPath(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): List<TaskContextDto> =
        taskContextService
            .listFor(ObjectId(clientId), ObjectId(projectId))
            .map { it.toDto() }

    @DeleteMapping("/{contextId}")
    suspend fun delete(
        @PathVariable contextId: String,
    ) {
        taskContextService.delete(ObjectId(contextId))
    }
}
