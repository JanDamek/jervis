package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Internet search tool using Searxng for web search capabilities.
 * Uses configured Searxng WebClient for search operations.
 */
@Service
class InternetSearchTool(
    override val promptRepository: PromptRepository,
    @Qualifier("searxngWebClient") private val webClient: WebClient,
) : McpTool {
    private val logger = KotlinLogging.logger {}
    
    override val name: PromptTypeEnum = PromptTypeEnum.INTERNET_SEARCH

    @Serializable
    data class SearchRequest(
        val query: String = "",
        val engines: String? = null, // Specific search engines to use
        val categories: String? = null, // Search categories
        val maxResults: Int = 10
    )

    @Serializable
    data class SearchParams(
        val requests: List<SearchRequest> = listOf(SearchRequest())
    )

    @Serializable
    data class SearxngResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
        val engine: String = "",
        val score: Double = 0.0
    )

    @Serializable
    data class SearxngResponse(
        val query: String = "",
        val results: List<SearxngResult> = emptyList(),
        val number_of_results: Int = 0,
        val engines: List<String> = emptyList()
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "INTERNET_SEARCH_START: Executing internet search for context: ${context.id}" }
        
        return try {
            val searchParams = parseTaskDescription(taskDescription)
            logger.debug { "INTERNET_SEARCH_PARAMS: ${searchParams.requests.size} requests" }

            val results = searchParams.requests.map { request ->
                performSearch(request)
            }

            val combinedResults = results.joinToString("\n\n---\n\n")
            
            logger.info { "INTERNET_SEARCH_SUCCESS: Completed ${searchParams.requests.size} operations" }
            ToolResult.ok(combinedResults)
            
        } catch (e: Exception) {
            logger.error(e) { "INTERNET_SEARCH_ERROR: Failed to execute internet search" }
            ToolResult.error(
                "Internet search failed: ${e.message}",
                "Error during internet search operation: ${e.message}"
            )
        }
    }

    private fun parseTaskDescription(taskDescription: String): SearchParams {
        return try {
            Json.decodeFromString<SearchParams>(taskDescription)
        } catch (e: Exception) {
            logger.debug { "INTERNET_SEARCH_PARSE_FALLBACK: Using simple query parsing" }
            // Fallback: treat the entire description as a search query
            SearchParams(listOf(SearchRequest(query = taskDescription.trim())))
        }
    }

    private suspend fun performSearch(request: SearchRequest): String {
        logger.debug { "INTERNET_SEARCH_QUERY: '${request.query}'" }
        
        val encodedQuery = URLEncoder.encode(request.query, StandardCharsets.UTF_8)
        val searchPath = buildString {
            append("/search")
            append("?q=$encodedQuery")
            append("&format=json")
            append("&safesearch=1")
            
            request.engines?.let { engines ->
                append("&engines=${URLEncoder.encode(engines, StandardCharsets.UTF_8)}")
            }
            
            request.categories?.let { categories ->
                append("&categories=${URLEncoder.encode(categories, StandardCharsets.UTF_8)}")
            }
        }

        return try {
            val response = webClient.get()
                .uri(searchPath)
                .retrieve()
                .awaitBody<SearxngResponse>()

            formatSearchResults(request.query, response, request.maxResults)
            
        } catch (e: Exception) {
            logger.error(e) { "INTERNET_SEARCH_QUERY_ERROR: Failed to search for '${request.query}'" }
            "Search failed for query '${request.query}': ${e.message}"
        }
    }


    private fun formatSearchResults(query: String, response: SearxngResponse, maxResults: Int): String {
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