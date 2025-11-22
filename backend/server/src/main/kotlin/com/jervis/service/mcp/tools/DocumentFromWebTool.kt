package com.jervis.service.mcp.tools

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.link.LinkIndexer
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Instant
import java.util.Base64

/**
 * Document from web tool for retrieving and parsing web page content.
 * Fetches fresh content directly, converts to plain text via Tika, indexes to RAG in background.
 */
@Service
class DocumentFromWebTool(
    override val promptRepository: PromptRepository,
    @Qualifier("searxngWebClient") private val webClient: WebClient,
    private val linkIndexer: LinkIndexer,
    private val tikaClient: ITikaClient,
) : McpTool<DocumentFromWebTool.DocumentFromWebParams> {
    private val logger = KotlinLogging.logger {}

    override val name = ToolTypeEnum.DOCUMENT_FROM_WEB_TOOL

    override val descriptionObject =
        DocumentFromWebParams(
            url =
                "Absolute URL to fetch (required)\n" +
                    "https://example.com/article.html",
            maxContentLength = 3000,
        )

    @Serializable
    data class DocumentFromWebParams(
        val url: String,
        val maxContentLength: Int,
    )

    override suspend fun execute(
        plan: Plan,
        request: DocumentFromWebParams,
    ): ToolResult = executeDocumentOperation(request, plan)

    private suspend fun executeDocumentOperation(
        params: DocumentFromWebParams,
        plan: Plan,
    ): ToolResult {
        // Fetch fresh content directly
        logger.info { "Fetching fresh content from ${params.url}" }

        val responseBody =
            try {
                webClient
                    .get()
                    .uri(params.url)
                    .retrieve()
                    .awaitBody<String>()
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch ${params.url}" }
                return ToolResult.error(
                    output = "Failed to fetch ${params.url}: ${e.message}",
                    message = "Network error",
                )
            }

        // Use Tika to extract plain text (consistent with email processing)
        val plainText =
            try {
                val result =
                    tikaClient.process(
                        TikaProcessRequest(
                            source =
                                TikaProcessRequest.Source.FileBytes(
                                    fileName = "webpage.html",
                                    dataBase64 = Base64.getEncoder().encodeToString(responseBody.toByteArray()),
                                ),
                            includeMetadata = false,
                        ),
                    )
                if (result.success) {
                    result.plainText
                } else {
                    return ToolResult.error(
                        output = "Failed to parse ${params.url}: ${result.errorMessage}",
                        message = "Parsing error",
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Tika parsing failed for ${params.url}" }
                return ToolResult.error(
                    output = "Failed to parse ${params.url}: ${e.message}",
                    message = "Parsing error",
                )
            }

        val truncatedContent = plainText.substring(0, kotlin.math.min(plainText.length, params.maxContentLength))

        val output =
            buildString {
                appendLine("URL: ${params.url}")
                appendLine("Fetched: ${Instant.now()}")
                appendLine()
                appendLine(truncatedContent)
                if (plainText.length > params.maxContentLength) {
                    appendLine("\n... (truncated to ${params.maxContentLength} chars)")
                }
            }

        // Background: index to RAG for future knowledge_search queries (non-blocking)
        // Pass already fetched content to avoid double fetch
        CoroutineScope(Dispatchers.IO).launch {
            try {
                linkIndexer.indexLink(
                    url = params.url,
                    projectId = plan.projectDocument?.id,
                    clientId = plan.clientDocument.id,
                    content = plainText,
                )
                logger.debug { "Background indexed ${params.url} to RAG" }
            } catch (e: Exception) {
                logger.warn(e) { "Background indexing failed for ${params.url}" }
            }
        }

        return ToolResult.success("WEB", "Page fetched", output)
    }
}
