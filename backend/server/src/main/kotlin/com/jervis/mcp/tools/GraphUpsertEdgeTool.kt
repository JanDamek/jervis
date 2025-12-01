package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphEdge
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GraphUpsertEdgeTool(
    private val graphDBService: GraphDBService,
    override val promptRepository: PromptRepository,
) : McpTool<GraphUpsertEdgeTool.Description> {
    companion object { private val logger = KotlinLogging.logger {} }

    @Serializable
    data class Description(
        val edgeType: String,
        val fromKey: String,
        val toKey: String,
        val props: Map<String, String> = emptyMap(),
    )

    override val name = ToolTypeEnum.GRAPH_UPSERT_EDGE_TOOL

    override val descriptionObject =
        Description(
            edgeType = "Type of the edge e.g. 'mentions', 'defines'",
            fromKey = "Source node key, e.g. entity::users::UserService",
            toKey = "Target node key, e.g. document::README.md",
            props = mapOf("weight" to "1.0")
        )

    override suspend fun execute(
        plan: Plan,
        request: Description,
    ): ToolResult {
        val clientId = plan.clientDocument.id.toHexString()
        return try {
            val edge = GraphEdge(
                edgeType = request.edgeType,
                fromKey = request.fromKey,
                toKey = request.toKey,
                props = request.props,
            )
            val result = graphDBService.upsertEdge(clientId, edge)
            val summary = if (result.ok) {
                val createdUpdated = if (result.created) "created" else "updated"
                "Edge ${'$'}{request.edgeType} ${'$'}createdUpdated: ${'$'}{request.fromKey} -> ${'$'}{request.toKey}"
            } else "Graph edge upsert failed for ${'$'}{request.edgeType}"
            val content = """{"ok": ${'$'}{result.ok}, "edgeType": "${'$'}{result.edgeType}", "fromKey": "${'$'}{result.fromKey}", "toKey": "${'$'}{result.toKey}", "created": ${'$'}{result.created}, "warnings": ${'$'}{result.warnings}}"""
            ToolResult.success(
                toolName = name.name,
                summary = summary,
                content = content,
            )
        } catch (e: Exception) {
            logger.warn(e) { "GraphUpsertEdgeTool failed" }
            ToolResult.error("Graph edge upsert failed: ${'$'}{e.message}")
        }
    }
}
