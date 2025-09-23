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
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/**
 * Web page opening tool for retrieving and parsing web page content.
 * Uses JSoup for proper text extraction, removing HTML tags while preserving links and text content.
 */
@Service
class WebPageOpenTool(
    override val promptRepository: PromptRepository,
    @Qualifier("searxngWebClient") private val webClient: WebClient,
) : McpTool {
    private val logger = KotlinLogging.logger {}
    
    override val name: PromptTypeEnum = PromptTypeEnum.WEB_PAGE_OPEN

    @Serializable
    data class PageOpenRequest(
        val url: String = "",
        val maxContentLength: Int = 5000, // Maximum content length to extract
        val includeLinks: Boolean = true, // Whether to include links in the output
    )

    @Serializable
    data class PageOpenParams(
        val requests: List<PageOpenRequest> = listOf(PageOpenRequest())
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "WEB_PAGE_OPEN_START: Opening web page for context: ${context.id}" }
        
        return try {
            val pageParams = parseTaskDescription(taskDescription)
            logger.debug { "WEB_PAGE_OPEN_PARAMS: ${pageParams.requests.size} requests" }

            val results = pageParams.requests.map { request ->
                openAndParsePage(request)
            }

            val combinedResults = results.joinToString("\n\n---\n\n")
            
            logger.info { "WEB_PAGE_OPEN_SUCCESS: Completed ${pageParams.requests.size} operations" }
            ToolResult.ok(combinedResults)
            
        } catch (e: Exception) {
            logger.error(e) { "WEB_PAGE_OPEN_ERROR: Failed to open web page" }
            ToolResult.error(
                "Web page opening failed: ${e.message}",
                "Error during web page opening operation: ${e.message}"
            )
        }
    }

    private fun parseTaskDescription(taskDescription: String): PageOpenParams {
        return try {
            Json.decodeFromString<PageOpenParams>(taskDescription)
        } catch (e: Exception) {
            logger.debug { "WEB_PAGE_OPEN_PARSE_FALLBACK: Using simple URL parsing" }
            // Fallback: treat the entire description as a URL
            PageOpenParams(listOf(PageOpenRequest(url = taskDescription.trim())))
        }
    }

    private suspend fun openAndParsePage(request: PageOpenRequest): String {
        val url = request.url.takeIf { it.isNotBlank() } 
            ?: return "No URL provided for page opening"
        
        logger.debug { "WEB_PAGE_OPEN_URL: $url" }
        
        return try {
            val responseBody = webClient.get()
                .uri(url)
                .retrieve()
                .awaitBody<String>()

            // Use JSoup for proper HTML parsing and text extraction
            val document = Jsoup.parse(responseBody)
            
            // Remove script and style elements
            document.select("script, style").remove()
            
            // Extract title
            val title = document.title().takeIf { it.isNotBlank() } ?: "No title"
            
            // Extract main text content
            val textContent = document.body()?.text() ?: document.text()
            val cleanedContent = textContent
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(request.maxContentLength)
            
            // Extract links if requested
            val links = if (request.includeLinks) {
                document.select("a[href]")
                    .take(20) // Limit to first 20 links
                    .mapNotNull { element ->
                        val href = element.attr("href")
                        val linkText = element.text().trim()
                        if (href.isNotBlank() && linkText.isNotBlank()) {
                            "$linkText: $href"
                        } else null
                    }
            } else emptyList()
            
            buildString {
                appendLine("Page opened: $url")
                appendLine("Title: $title")
                appendLine()
                appendLine("Content:")
                appendLine(cleanedContent)
                if (textContent.length > request.maxContentLength) {
                    appendLine("\n... (content truncated)")
                }
                
                if (links.isNotEmpty()) {
                    appendLine()
                    appendLine("Links found:")
                    links.forEach { link ->
                        appendLine("- $link")
                    }
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "WEB_PAGE_OPEN_URL_ERROR: Failed to open page $url" }
            "Failed to open page $url: ${e.message}"
        }
    }
}