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
        context: TaskContext,
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
                quick = context.quick,
                responseSchema = ContentSearchWebParams(),
            )
        return llmResponse.result
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

        // Try multiple compatible endpoints/param names to support different search backends (SearxNG / OpenGX).
        // Order is chosen to keep backward compatibility with SearxNG first.
        val attempts = listOf(
            SearchAttempt(path = "/search", param = "q"), // SearxNG classic
            SearchAttempt(path = "/search", param = "query"), // some gateways expect 'query'
            SearchAttempt(path = "/api/search", param = "q"), // alternative API prefix
            SearchAttempt(path = "/opengx/search", param = "query"), // OpenGX style (heuristic)
        )

        var lastError: Throwable? = null
        for (attempt in attempts) {
            runCatching {
                val response =
                    webClient
                        .get()
                        .uri { uriBuilder ->
                            uriBuilder
                                .path(attempt.path)
                                .queryParam(attempt.param, query)
                                .queryParam("format", "json")
                                .queryParam("safesearch", "1")
                                .build()
                        }.retrieve()
                        .awaitBody<SearxngResponse>()

                logger.info { "CONTENT_SEARCH_WEB_ENDPOINT_OK: path='${attempt.path}', param='${attempt.param}'" }
                return formatSearchResults(query, response)
            }.onFailure { ex ->
                lastError = ex
                logger.warn { "CONTENT_SEARCH_WEB_ENDPOINT_FAIL: path='${attempt.path}', param='${attempt.param}', error='${ex.message}'" }
            }
        }

        throw IllegalStateException("All search endpoint attempts failed for query: '$query'", lastError)
    }

    private data class SearchAttempt(val path: String, val param: String)

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
