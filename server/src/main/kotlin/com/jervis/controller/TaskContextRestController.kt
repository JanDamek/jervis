package com.jervis.controller

import com.jervis.domain.context.TaskContext
import com.jervis.service.ITaskContextService
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
@RequestMapping("/api/task-context")
class TaskContextRestController(
    private val taskContextService: ITaskContextService,
) {
    @PostMapping("/create")
    suspend fun create(
        @RequestBody request: CreateContextRequest,
    ): TaskContext =
        taskContextService.create(
            clientId = request.clientId,
            projectId = request.projectId,
            quick = request.quick,
            contextName = request.contextName,
        )

    @PostMapping("/save")
    suspend fun save(
        @RequestBody context: TaskContext,
    ) {
        taskContextService.save(context)
    }

    @GetMapping("/{contextId}")
    suspend fun findById(
        @PathVariable contextId: String,
    ): TaskContext? = taskContextService.findById(ObjectId(contextId))

    @GetMapping("/list")
    suspend fun listFor(
        @RequestParam clientId: String,
        @RequestParam(required = false) projectId: String?,
    ): List<TaskContext> =
        taskContextService.listFor(
            ObjectId(clientId),
            projectId?.let { ObjectId(it) },
        )

    @DeleteMapping("/{contextId}")
    suspend fun delete(
        @PathVariable contextId: String,
    ) {
        taskContextService.delete(ObjectId(contextId))
    }

    data class CreateContextRequest(
        val clientId: ObjectId,
        val projectId: ObjectId,
        val quick: Boolean = false,
        val contextName: String = "New Context",
    )
}
