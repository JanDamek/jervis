package com.jervis.task

import com.jervis.agent.PythonOrchestratorClient
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.service.task.ITaskGraphService
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

private val lenientJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

@Component
class TaskGraphRpcImpl(
    private val pythonClient: PythonOrchestratorClient,
) : ITaskGraphService {

    override suspend fun getGraph(taskId: String, clientId: String?): TaskGraphDto? {
        return try {
            val json = pythonClient.getTaskGraph(taskId, clientId) ?: return null
            lenientJson.decodeFromString<TaskGraphDto>(json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch task graph for taskId=$taskId" }
            null
        }
    }
}
