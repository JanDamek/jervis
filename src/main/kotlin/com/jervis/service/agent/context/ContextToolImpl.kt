package com.jervis.service.agent.context

import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ContextToolImpl : ContextTool {
    private val logger = KotlinLogging.logger {}

    override suspend fun applyContext(
        clientName: String?,
        projectName: String?,
    ) {
        // Placeholder for UI integration: currently logs the applied context
        logger.info { "ContextTool.applyContext -> client=$clientName, project=$projectName" }
    }
}
