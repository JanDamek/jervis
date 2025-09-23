package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.indexing.TikaDocumentProcessor
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Tika tool for converting various document formats to plain text.
 * Supports PDF, DOCX, XLSX, PPTX, HTML, and other formats supported by Apache Tika.
 * Provides metadata extraction and plain text conversion for document analysis.
 */
@Service
class TikaTool(
    override val promptRepository: PromptRepository,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.TIKA_TOOL

    @Serializable
    data class TikaParams(
        val filePath: String,
        val includeMetadata: Boolean = true,
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            logger.debug { "TIKA_TOOL_START: Executing document processing for task='$taskDescription'" }

            try {
                val params = parseTaskDescription(taskDescription)
                val documentPath = resolvePath(params.filePath, context)

                logger.debug { "TIKA_TOOL_PROCESSING: Processing document at path: $documentPath" }

                // Validate file exists and is supported
                if (!documentPath.exists()) {
                    return@withContext ToolResult.error("Document not found: ${params.filePath}")
                }

                if (!documentPath.isRegularFile()) {
                    return@withContext ToolResult.error("Path is not a regular file: ${params.filePath}")
                }

                // Process document with Tika
                val processingResult = tikaDocumentProcessor.processDocument(documentPath)

                if (!processingResult.success) {
                    logger.error { "TIKA_TOOL_ERROR: Failed to process document: ${processingResult.errorMessage}" }
                    return@withContext ToolResult.error("Failed to process document: ${processingResult.errorMessage}")
                }

                // Build response with plain text and optional metadata
                logger.debug {
                    "TIKA_TOOL_SUCCESS: Successfully processed document, extracted ${processingResult.plainText.length} characters"
                }

                buildResponse(processingResult, params, documentPath)
            } catch (e: Exception) {
                logger.error(e) { "TIKA_TOOL_ERROR: Unexpected error during document processing" }
                ToolResult.error("Document processing failed: ${e.message}")
            }
        }

    private fun parseTaskDescription(taskDescription: String): TikaParams {
        val filePath =
            extractFilePath(taskDescription)
                ?: throw IllegalArgumentException("No file path specified in task description")

        val includeMetadata =
            !taskDescription.contains("no metadata", ignoreCase = true) &&
                !taskDescription.contains("text only", ignoreCase = true)

        return TikaParams(
            filePath = filePath,
            includeMetadata = includeMetadata,
        )
    }

    private fun extractFilePath(taskDescription: String): String? {
        val patterns =
            listOf(
                """(?:file|document|path)[:\s]+([^\s]+)""".toRegex(RegexOption.IGNORE_CASE),
                """['"]([^'"]*\.[a-zA-Z]{2,5})['"]""".toRegex(), // Quoted file paths with extensions
                """([^\s]+\.[a-zA-Z]{2,5})""".toRegex(), // Simple file paths with extensions
            )

        for (pattern in patterns) {
            val match = pattern.find(taskDescription)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        // If no path found, check if task description itself looks like a path
        if (taskDescription.contains('.') && taskDescription.split('.').last().length in 2..5) {
            return taskDescription.trim()
        }

        return null
    }

    private fun resolvePath(
        filePath: String,
        context: TaskContext,
    ): Path {
        val path = Paths.get(filePath)

        return when {
            path.isAbsolute -> path
            else -> {
                // Resolve relative to project path
                val projectPath = Paths.get(context.projectDocument.path)
                if (Files.exists(projectPath) && Files.isDirectory(projectPath)) {
                    projectPath.resolve(filePath)
                } else {
                    // Fallback to current directory
                    Paths.get(System.getProperty("user.dir")).resolve(filePath)
                }
            }
        }
    }

    private fun buildResponse(
        result: TikaDocumentProcessor.DocumentProcessingResult,
        params: TikaParams,
        documentPath: Path,
    ): ToolResult {
        val summary = "Extracted ${result.plainText.length} characters from ${documentPath.name}"

        val documentInfo =
            buildString {
                appendLine("Document: ${documentPath.name}")
                appendLine("Path: $documentPath")
            }

        val metadata =
            if (params.includeMetadata && result.metadata.title != null) {
                buildString {
                    result.metadata.title.let { appendLine("Title: $it") }
                    result.metadata.author?.let { appendLine("Author: $it") }
                    result.metadata.contentType?.let { appendLine("Content Type: $it") }
                    result.metadata.language?.let { appendLine("Language: $it") }
                    result.metadata.pageCount?.let { appendLine("Pages: $it") }
                    result.metadata.creationDate?.let { appendLine("Created: $it") }
                    result.metadata.lastModified?.let { appendLine("Modified: $it") }

                    if (result.metadata.keywords.isNotEmpty()) {
                        appendLine("Keywords: ${result.metadata.keywords.joinToString(", ")}")
                    }

                    if (result.metadata.customProperties.isNotEmpty()) {
                        appendLine("Custom Properties:")
                        result.metadata.customProperties.forEach { (key, value) ->
                            appendLine("  $key: $value")
                        }
                    }
                }.trim()
            } else {
                ""
            }

        val content =
            buildString {
                append(documentInfo.trim())
                if (metadata.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(metadata)
                }
            }

        return ToolResult.success(
            toolName = "TIKA",
            summary = summary,
            content = content,
            result.plainText,
        )
    }
}
