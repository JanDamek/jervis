package com.jervis.service

import com.jervis.dto.CreateContextRequestDto
import com.jervis.dto.TaskContextDto

interface ITaskContextService {
    suspend fun findById(contextId: String): TaskContextDto?

    suspend fun listForClient(clientId: String): List<TaskContextDto>

    suspend fun listForClientAndProject(
        clientId: String,
        projectId: String,
    ): List<TaskContextDto>

    suspend fun create(requestDto: CreateContextRequestDto): TaskContextDto

    suspend fun save(context: TaskContextDto): TaskContextDto

    suspend fun delete(contextId: String)
}
