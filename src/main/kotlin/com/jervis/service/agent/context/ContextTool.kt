package com.jervis.service.agent.context

/**
 * Tool responsible for applying resolved context (client/project) to the application.
 * For now, it only acts as a hook; the UI already reflects context via ChatResponse.
 */
fun interface ContextTool {
    suspend fun applyContext(
        clientName: String?,
        projectName: String?,
    )
}
