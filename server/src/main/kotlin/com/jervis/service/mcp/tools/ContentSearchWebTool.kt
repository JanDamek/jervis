package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
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

    override val name: PromptTypeEnum = PromptTypeEnum.CONTENT_SEARCH_WEB_TOOL

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
        plan: Plan,
        stepContext: String = "",
    ): ContentSearchWebParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CONTENT_SEARCH_WEB_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = plan.quick,
                responseSchema = ContentSearchWebParams(),
                backgroundMode = plan.backgroundMode,
            )
        return llmResponse.result
    }

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, plan, stepContext)

        return executeContentSearchWebOperation(parsed, plan)
    }

    private suspend fun executeContentSearchWebOperation(
        params: ContentSearchWebParams,
        plan: Plan,
    ): ToolResult {
        logger.info { "CONTENT_SEARCH_WEB_START: Executing web search for query='${params.query}'" }

        val query = params.query.trim()
        if (query.isBlank()) {
            return ToolResult.error(
                output = "Web search failed",
                message = "Query must not be blank.",
            )
        }

        val searchResult = performSearch(query)

        logger.info { "CONTENT_SEARCH_WEB_SUCCESS: Completed web search operation" }
        return ToolResult.ok(searchResult)
    }

    private suspend fun performSearch(query: String): String {
        logger.debug { "CONTENT_SEARCH_WEB_QUERY: '$query'" }

        val response =
            webClient
                .get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .queryParam("format", "json")
                        .queryParam("safesearch", "1")
                        .build()
                }.retrieve()
                .awaitBody<SearxngResponse>()

        logger.info { "CONTENT_SEARCH_WEB_ENDPOINT_OK: path='/search', param='q'" }
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
