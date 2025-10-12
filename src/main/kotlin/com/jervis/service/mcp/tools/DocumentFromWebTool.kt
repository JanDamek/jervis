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
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/**
 * Document from web tool for retrieving and parsing web page content.
 * Uses JSoup for proper text extraction, removing HTML tags while preserving links and text content.
 */
@Service
class DocumentFromWebTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
    @Qualifier("searxngWebClient") private val webClient: WebClient,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.DOCUMENT_FROM_WEB_TOOL

    @Serializable
    data class DocumentFromWebParams(
        val url: String = "",
        val maxContentLength: Int = 5000,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String,
    ): DocumentFromWebParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.DOCUMENT_FROM_WEB_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = context.quick,
                responseSchema = DocumentFromWebParams(),
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

        return executeDocumentOperation(parsed, context)
    }

    private suspend fun executeDocumentOperation(
        params: DocumentFromWebParams,
        context: TaskContext,
    ): ToolResult {
        val responseBody =
            webClient
                .get()
                .uri(params.url)
                .retrieve()
                .awaitBody<String>()

        val document = Jsoup.parse(responseBody)
        document.select("script, style").remove()

        val title = document.title().takeIf { it.isNotBlank() } ?: "No title"
        val textContent = document.body()?.text() ?: document.text()
        val cleanedContent =
            textContent
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(params.maxContentLength)

        val output =
            buildString {
                appendLine("Page: ${params.url}")
                appendLine("Title: $title")
                appendLine()
                appendLine("Content:")
                appendLine(cleanedContent)
                if (textContent.length > params.maxContentLength) {
                    appendLine("\n... (content truncated)")
                }
            }

        return ToolResult.success("WEB", "Page extracted", output)
    }
}
