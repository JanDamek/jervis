package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphNode
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GraphUpsertNodeTool(
    private val graphDBService: GraphDBService,
    override val promptRepository: PromptRepository,
) : McpTool<GraphUpsertNodeTool.Description> {
    companion object { private val logger = KotlinLogging.logger {} }

    @Serializable
    data class Description(
        val entityType: String,
        val key: String,
        val props: Map<String, String> = emptyMap(),
        val ragChunks: List<String> = emptyList(),
    )

    override val name = ToolTypeEnum.GRAPH_UPSERT_NODE_TOOL

    override val descriptionObject =
        Description(
            entityType = "Type of the node e.g. 'entity', 'document', 'class'",
            key = "Stable node key, e.g. entity::users::UserService",
            props = mapOf("projectId" to "<ObjectId as string>", "language" to "kotlin"),
            ragChunks = listOf("weaviate://<class>/<id>")
        )

    override suspend fun execute(
        plan: Plan,
        request: Description,
    ): ToolResult {
        val clientId = plan.clientDocument.id.toHexString()
        return try {
            val node = GraphNode(
                key = request.key,
                entityType = request.entityType,
                props = request.props,
                ragChunks = request.ragChunks,
            )
            val result = graphDBService.upsertNode(clientId, node)
            val summary = if (result.ok) {
                if (result.created) "Node created in graph: ${'$'}{result.key}" else "Node updated in graph: ${'$'}{result.key}"
            } else {
                "Graph node upsert failed for key: ${'$'}{request.key}"
            }
            val content = """{"ok": ${'$'}{result.ok}, "key": "${'$'}{result.key}", "created": ${'$'}{result.created}, "warnings": ${'$'}{result.warnings}}"""
            ToolResult.success(
                toolName = name.name,
                summary = summary,
                content = content,
            )
        } catch (e: Exception) {
            logger.warn(e) { "GraphUpsertNodeTool failed" }
            ToolResult.error("Graph node upsert failed: ${'$'}{e.message}")
        }
    }
}
