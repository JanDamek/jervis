package com.jervis.koog.qualifier

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.configuration.KtorClientFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * Koog-native toolset for the Qualifier agent (pre-orchestrator).
 *
 * Intentionally independent of Plan/MCP; safe to use in the qualification phase.
 */
class KoogQualifierTools(
    private val ktorClientFactory: KtorClientFactory,
) : ToolSet {
    private val logger = KotlinLogging.logger {}
    private val httpClient: HttpClient by lazy { ktorClientFactory.getHttpClient("searxng") }

    @Tool
    @LLMDescription("Web search via Searxng. Use when the input lacks context or you need quick facts. Returns concise list of results.")
    fun webSearch(
        @LLMDescription("Query to search on the web")
        query: String,

        @LLMDescription("Maximum number of results to return (default 5)")
        maxResults: Int = 5,
    ): String {
        val q = query.trim()
        require(q.isNotBlank()) { "webSearch: query must not be blank" }

        val response = runBlocking {
            httpClient.get("/search") {
                parameter("q", q)
                parameter("format", "json")
                parameter("safesearch", "1")
            }.body<SearxngResponse>()
        }

        logger.info { "KOOG_QUALIFIER_TOOLS.webSearch ok: engines=${'$'}{response.engines.size} results=${'$'}{response.number_of_results}" }
        return formatResults(q, response, maxResults)
    }

    private fun formatResults(query: String, response: SearxngResponse, max: Int): String = buildString {
        appendLine("Web search results for: '${'$'}query'")
        appendLine("Found ${'$'}{response.number_of_results} results")
        if (response.engines.isNotEmpty()) {
            appendLine("Engines: ${'$'}{response.engines.joinToString(\", \")}")
        }
        appendLine()
        response.results.take(max.coerceAtLeast(1)).forEachIndexed { idx, r ->
            appendLine("${'$'}{idx + 1}. ${'$'}{r.title}")
            appendLine("   URL: ${'$'}{r.url}")
            if (r.content.isNotBlank()) {
                val snippet = if (r.content.length > 220) r.content.take(220) + "..." else r.content
                appendLine("   Snippet: ${'$'}snippet")
            }
            if (r.engine.isNotBlank()) appendLine("   Source: ${'$'}{r.engine}")
            if (r.score > 0) appendLine("   Score: ${'$'}{r.score}")
            appendLine()
        }
        if (response.results.size > max) {
            appendLine("... and ${'$'}{response.results.size - max} more")
        }
    }

    data class SearxngResult(
        val title: String,
        val url: String,
        val content: String,
        val engine: String,
        val score: Double,
    )

    data class SearxngResponse(
        val query: String,
        val results: List<SearxngResult>,
        val number_of_results: Int,
        val engines: List<String>,
    )
}
