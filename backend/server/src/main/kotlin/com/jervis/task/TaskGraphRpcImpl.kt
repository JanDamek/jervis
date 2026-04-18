package com.jervis.task

import com.jervis.agent.PythonOrchestratorClient
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.service.task.ITaskGraphService
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class TaskGraphRpcImpl(
    private val pythonClient: PythonOrchestratorClient,
) : ITaskGraphService {

    override suspend fun getGraph(taskId: String, clientId: String?): TaskGraphDto? {
        return try {
            pythonClient.getTaskGraph(taskId, clientId)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch task graph for taskId=$taskId" }
            null
        }
    }
}
