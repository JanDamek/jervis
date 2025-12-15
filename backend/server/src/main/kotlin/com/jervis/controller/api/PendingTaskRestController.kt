package com.jervis.controller.api

import com.jervis.dto.PendingTaskDto
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.service.IPendingTaskService
import com.jervis.service.background.PendingTaskService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * REST API for PendingTask management.
 *
 * Note: Context has been removed - all data is now in content field.
 * Tasks are created with complete content, no need for manual updates.
 */
@RestController
@RequestMapping("/api/pending-tasks")
class PendingTaskRestController(
    private val pendingTaskService: PendingTaskService,
) : IPendingTaskService {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    @GetMapping
    override suspend fun listPendingTasks(
        @RequestParam("taskType") taskType: String?,
        @RequestParam("state") state: String?,
    ): List<PendingTaskDto> {
        val taskTypeEnum = taskType?.let { runCatching { PendingTaskTypeEnum.valueOf(it) }.getOrNull() }
        val stateEnum = state?.let { runCatching { PendingTaskStateEnum.valueOf(it) }.getOrNull() }

        return pendingTaskService
            .findAllTasks(taskTypeEnum, stateEnum)
            .map { task ->
                PendingTaskDto(
                    id = task.id.toString(),
                    taskType = task.type.name,
                    content = task.content,
                    projectId = task.projectId?.toString(),
                    clientId = task.clientId.toString(),
                    createdAt = fmt.format(task.createdAt),
                    state = task.state.name,
                )
            }.toList()
    }

    @GetMapping("/count")
    override suspend fun countPendingTasks(
        @RequestParam("taskType") taskType: String?,
        @RequestParam("state") state: String?,
    ): Long {
        val taskTypeEnum = taskType?.let { runCatching { PendingTaskTypeEnum.valueOf(it) }.getOrNull() }
        val stateEnum = state?.let { runCatching { PendingTaskStateEnum.valueOf(it) }.getOrNull() }
        return pendingTaskService.countTasks(taskTypeEnum, stateEnum)
    }

    @DeleteMapping("/{id}")
    override suspend fun deletePendingTask(
        @PathVariable("id") id: String,
    ) {
        val objectId = ObjectId(id)
        pendingTaskService.deleteTask(objectId)
        logger.info { "Deleted pending task via API: $id" }
    }
}
