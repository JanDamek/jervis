package com.jervis.dto.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for transferring AgentGraph data to the UI.
 *
 * Mirrors the Python agent models (snake_case in MongoDB)
 * with Kotlin naming conventions.
 *
 * GraphType values: "memory_map" (Paměťová mapa), "thinking_map" (Myšlenková mapa)
 * Legacy DB values "master" and "task_subgraph" are also accepted.
 */

@Serializable
data class TaskGraphDto(
    val id: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("client_id") val clientId: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val status: String = "",
    @SerialName("root_vertex_id") val rootVertexId: String = "",
    val vertices: Map<String, GraphVertexDto> = emptyMap(),
    val edges: List<GraphEdgeDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("total_token_count") val totalTokenCount: Int = 0,
    @SerialName("total_llm_calls") val totalLlmCalls: Int = 0,
)

@Serializable
data class GraphVertexDto(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("vertex_type") val vertexType: String = "",
    val status: String = "",
    @SerialName("agent_name") val agentName: String? = null,
    @SerialName("input_request") val inputRequest: String = "",
    val result: String = "",
    @SerialName("result_summary") val resultSummary: String = "",
    @SerialName("local_context") val localContext: String = "",
    @SerialName("parent_id") val parentId: String? = null,
    val depth: Int = 0,
    @SerialName("tools_used") val toolsUsed: List<String> = emptyList(),
    @SerialName("token_count") val tokenCount: Int = 0,
    @SerialName("llm_calls") val llmCalls: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class GraphEdgeDto(
    val id: String = "",
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("target_id") val targetId: String = "",
    @SerialName("edge_type") val edgeType: String = "",
    val payload: EdgePayloadDto? = null,
)

@Serializable
data class EdgePayloadDto(
    @SerialName("source_vertex_id") val sourceVertexId: String = "",
    @SerialName("source_vertex_title") val sourceVertexTitle: String = "",
    val summary: String = "",
    val context: String = "",
)
