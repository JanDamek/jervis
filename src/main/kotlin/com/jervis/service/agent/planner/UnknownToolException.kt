package com.jervis.service.agent.planner

/**
 * Thrown when a generated plan references a tool that is not registered in McpToolRegistry.
 */
class UnknownToolException(
    message: String,
) : RuntimeException(message)
