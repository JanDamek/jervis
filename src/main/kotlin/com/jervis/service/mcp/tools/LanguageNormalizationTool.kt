package com.jervis.service.mcp.tools

import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpAction
import com.jervis.service.mcp.McpTool
import com.jervis.service.agent.coordinator.LanguageOrchestrator
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Tool that normalizes the current task's initial query to English using LanguageOrchestrator.
 * Returns the normalized English text; does not persist it (keeps tool side-effect minimal).
 */
@Service
class LanguageNormalizationTool(
    private val taskContextRepo: TaskContextMongoRepository,
    private val language: LanguageOrchestrator,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.LANGUAGE_NORMALIZE

    override val action: McpAction = McpAction(
        type = "language",
        content = "normalize",
        parameters = emptyMap(),
    )

    override suspend fun execute(action: String, contextId: ObjectId): String {
        val ctx = taskContextRepo.findByContextId(contextId)
        val query = ctx?.initialQuery?.trim().orEmpty()
        if (query.isEmpty()) return ""
        val lang = language.detectLanguage(query)
        return if (lang == "en") query else language.translateToEnglish(query, lang)
    }
}
