package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.document.TikaDocumentProcessor
import com.jervis.service.gateway.core.LlmGateway
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

/**
 * Document Extract Text tool for converting various document formats to plain text.
 * Supports PDF, DOCX, XLSX, PPTX, HTML, and other formats supported by Apache Tika.
 */
@Service
class DocumentExtractTextTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.DOCUMENT_EXTRACT_TEXT_TOOL

    @Serializable
    data class DocumentExtractTextParams(
        val filePath: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): DocumentExtractTextParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.DOCUMENT_EXTRACT_TEXT_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = context.quick,
                responseSchema = DocumentExtractTextParams(),
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

        return executeDocumentExtractTextOperation(parsed, context)
    }

    private suspend fun executeDocumentExtractTextOperation(
        params: DocumentExtractTextParams,
        context: TaskContext,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            logger.debug { "DOCUMENT_EXTRACT_TEXT_START: Processing document at path: ${params.filePath}" }

            val documentPath = resolvePath(params.filePath, context)

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
                logger.error { "DOCUMENT_EXTRACT_TEXT_ERROR: Failed to process document: ${processingResult.errorMessage}" }
                return@withContext ToolResult.error("Failed to process document: ${processingResult.errorMessage}")
            }

            // Build response with plain text
            logger.debug {
                "DOCUMENT_EXTRACT_TEXT_SUCCESS: Successfully processed document, extracted ${processingResult.plainText.length} characters"
            }

            val content =
                buildString {
                    appendLine("Document: ${documentPath.fileName}")
                    appendLine("Path: ${documentPath.toAbsolutePath()}")
                    appendLine("Content Length: ${processingResult.plainText.length} characters")
                    appendLine()
                    appendLine("Extracted Text:")
                    appendLine("=".repeat(50))
                    append(processingResult.plainText)
                }

            ToolResult.success(
                "DOCUMENT_EXTRACT_TEXT",
                "Successfully extracted text from document",
                content,
            )
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
}
