package com.jervis.service.mcp

/**
 * Minimal DTO representing an MCP action used for storing action traces in vector DB.
 */
data class McpAction(
    val type: String,
    val content: String,
    val parameters: Map<String, Any?> = emptyMap()
)
