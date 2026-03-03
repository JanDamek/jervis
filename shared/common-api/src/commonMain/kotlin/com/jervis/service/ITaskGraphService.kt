package com.jervis.service

import com.jervis.dto.graph.TaskGraphDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ITaskGraphService {
    /** Get the full task graph for a given task ID. Returns null if not found. */
    suspend fun getGraph(taskId: String): TaskGraphDto?
}
