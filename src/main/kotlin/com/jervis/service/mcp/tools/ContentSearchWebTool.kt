package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Content Search Web tool using Searxng for web search capabilities.
 * Uses configured Searxng WebClient for search operations.
 */
@Service
class ContentSearchWebTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
    @Qualifier("searxngWebClient") private val webClient: WebClient,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.CONTENT_SEARCH_WEB

    @Serializable
    data class ContentSearchWebParams(
        val query: String = "",
    )

    @Serializable
    data class SearxngResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
        val engine: String = "",
        val score: Double = 0.0,
    )

    @Serializable
    data class SearxngResponse(
        val query: String = "",
        val results: List<SearxngResult> = emptyList(),
        val number_of_results: Int = 0,
        val engines: List<String> = emptyList(),
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): ContentSearchWebParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CONTENT_SEARCH_WEB,
                mappingValue = mapOf("taskDescription" to taskDescription),
                quick = context.quick,
                responseSchema = ContentSearchWebParams(),
                stepContext = stepContext,
            )
        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        return executeContentSearchWebOperation(parsed, context)
    }

    private suspend fun executeContentSearchWebOperation(
        params: ContentSearchWebParams,
        context: TaskContext,
    ): ToolResult {
        logger.info { "CONTENT_SEARCH_WEB_START: Executing web search for query='${params.query}'" }

        val searchResult = performSearch(params.query)

        logger.info { "CONTENT_SEARCH_WEB_SUCCESS: Completed web search operation" }
        return ToolResult.ok(searchResult)
    }

    private suspend fun performSearch(query: String): String {
        logger.debug { "CONTENT_SEARCH_WEB_QUERY: '$query'" }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val searchPath =
            buildString {
                append("/search")
                append("?q=$encodedQuery")
                append("&format=json")
                append("&safesearch=1")
            }

        val response =
            webClient
                .get()
                .uri(searchPath)
                .retrieve()
                .awaitBody<SearxngResponse>()

        return formatSearchResults(query, response)
    }

    private fun formatSearchResults(
        query: String,
        response: SearxngResponse,
    ): String {
        val maxResults = 10

        return buildString {
            appendLine("Search results for: '$query'")
            appendLine("Found ${response.number_of_results} results")

            if (response.engines.isNotEmpty()) {
                appendLine("Search engines used: ${response.engines.joinToString(", ")}")
            }

            appendLine()

            response.results.take(maxResults).forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.title}")
                appendLine("   URL: ${result.url}")
                if (result.content.isNotEmpty()) {
                    appendLine("   Summary: ${result.content.take(200)}${if (result.content.length > 200) "..." else ""}")
                }
                if (result.engine.isNotEmpty()) {
                    appendLine("   Source: ${result.engine}")
                }
                if (result.score > 0) {
                    appendLine("   Score: ${result.score}")
                }
                appendLine()
            }

            if (response.results.size > maxResults) {
                appendLine("... and ${response.results.size - maxResults} more results")
            }
        }
    }
}
