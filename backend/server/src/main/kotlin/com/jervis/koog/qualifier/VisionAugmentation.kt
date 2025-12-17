package com.jervis.koog.qualifier

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.markdown.markdown
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.domain.atlassian.VisionAnalysisResult
import com.jervis.domain.atlassian.shouldProcessWithVision
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.qualifier.types.ContentType
import com.jervis.koog.qualifier.types.VisionContext
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.writeBytes

private val json = Json { ignoreUnknownKeys = true }

/**
 * Vision analysis result for a single attachment.
 * Stage 1: General description
 */
@Serializable
data class GeneralVisionResult(
    val items: List<Item>,
) {
    @Serializable
    data class Item(
        val attachmentId: String,
        val generalDescription: String, // What is in the image (general)
    )
}

/**
 * Vision analysis result for type-specific details.
 * Stage 2: Detailed extraction based on content type
 */
@Serializable
data class TypeSpecificVisionResult(
    val items: List<Item>,
) {
    @Serializable
    data class Item(
        val attachmentId: String,
        val specificDetails: String, // Type-specific details (e.g., JIRA key, error message, UI elements)
    )
}

/**
 * Two-stage vision analysis using Koog attachments DSL.
 *
 * Stage 1: General description - what's in the image (screenshots, charts, diagrams, text)
 * Stage 2: Type-specific extraction - based on content type (JIRA keys, email addresses, error codes, etc.)
 *
 * @param executorFactory Factory for creating Koog prompt executors
 * @param providerSelector Selector for Ollama provider configuration
 * @param model Vision model to use (e.g., qwen3-vl)
 * @param originalText Original text content from task
 * @param attachments List of attachments to analyze
 * @param contentType Content type for stage 2 analysis (null if not yet determined)
 * @param directoryStructureService Service for reading attachment data
 * @return VisionContext with general and type-specific vision analysis
 */
suspend fun runTwoStageVision(
    executorFactory: KoogPromptExecutorFactory,
    providerSelector: OllamaProviderSelector,
    model: LLModel,
    originalText: String,
    attachments: List<AttachmentMetadata>,
    contentType: ContentType?,
    directoryStructureService: DirectoryStructureService,
): VisionContext {
    val visual = attachments.filter { it.shouldProcessWithVision() }
    if (visual.isEmpty()) {
        return VisionContext(
            originalText = originalText,
            generalVisionSummary = null,
            typeSpecificVisionDetails = null,
            attachments = attachments,
        )
    }

    // Prepare temp files for image attachments only (Koog attachments DSL requirement)
    val tmpFiles: List<Pair<AttachmentMetadata, java.nio.file.Path>> =
        visual
            .filter { it.mimeType.startsWith("image/") }
            .map { att ->
                val bytes = directoryStructureService.readAttachment(att.storagePath)
                val suffix =
                    when {
                        att.mimeType.contains("png") -> ".png"
                        att.mimeType.contains("jpeg") || att.mimeType.contains("jpg") -> ".jpg"
                        att.mimeType.contains("webp") -> ".webp"
                        else -> ".png"
                    }
                val tmp = Files.createTempFile("koog-vision-${att.id}-", suffix)
                tmp.writeBytes(bytes)
                att to tmp
            }

    if (tmpFiles.isEmpty()) {
        return VisionContext(
            originalText = originalText,
            generalVisionSummary = null,
            typeSpecificVisionDetails = null,
            attachments = attachments,
        )
    }

    try {
        val executor = executorFactory.getExecutor(providerSelector.getProvider())

        // ===================================================================
        // STAGE 1: General description - what's in the image
        // ===================================================================
        val stage1Prompt =
            prompt("vision-stage1-general") {
                system(
                    """
You are a Vision Analysis AI - Stage 1: General Description.
Analyze images and describe what you see in general terms.

Return ONLY valid JSON:
{
  "items": [
    {"attachmentId": "...", "generalDescription": "..."}
  ]
}

Rules:
- Describe WHAT is in the image (screenshot, chart, diagram, error dialog, form, etc.)
- Include visible text, UI elements, chart types
- Be concise but complete
- One item per attachment
                    """.trimIndent(),
                )

                user {
                    markdown {
                        +"Analyze these images and provide general descriptions:"
                        br()
                        tmpFiles.forEach { (att, _) ->
                            +" - ${att.id} (${att.filename})"
                            br()
                        }
                    }

                    tmpFiles.forEach { (_, tmpPath) ->
                        image(Path(tmpPath.toString()))
                    }
                }
            }

        val stage1Responses = executor.execute(stage1Prompt, model)
        val stage1Raw = stage1Responses.firstOrNull()?.content ?: ""
        val stage1Result = json.decodeFromString(GeneralVisionResult.serializer(), stage1Raw)

        val generalSummary =
            buildString {
                append("## 🔍 Visual Content (General)\n\n")
                stage1Result.items.forEach { item ->
                    val att = visual.find { it.id == item.attachmentId }
                    append("### ${att?.filename ?: item.attachmentId}\n")
                    append(item.generalDescription)
                    append("\n\n")
                }
            }.trim()

        // ===================================================================
        // STAGE 2: Type-specific details (only if contentType is known)
        // ===================================================================
        val typeSpecificDetails =
            if (contentType != null) {
                val stage2Prompt =
                    prompt("vision-stage2-specific") {
                        system(
                            """
You are a Vision Analysis AI - Stage 2: Type-Specific Extraction.
Extract specific details based on content type: $contentType

Return ONLY valid JSON:
{
  "items": [
    {"attachmentId": "...", "specificDetails": "..."}
  ]
}

Extraction rules for $contentType:
${getTypeSpecificExtractionRules(contentType)}

One item per attachment with specific extracted data.
                            """.trimIndent(),
                        )

                        user {
                            markdown {
                                +"Extract type-specific details from these images:"
                                br()
                                tmpFiles.forEach { (att, _) ->
                                    +" - ${att.id} (${att.filename})"
                                    br()
                                }
                            }

                            tmpFiles.forEach { (_, tmpPath) ->
                                image(Path(tmpPath.toString()))
                            }
                        }
                    }

                val stage2Responses = executor.execute(stage2Prompt, model)
                val stage2Raw = stage2Responses.firstOrNull()?.content ?: ""
                val stage2Result = json.decodeFromString(TypeSpecificVisionResult.serializer(), stage2Raw)

                buildString {
                    append("## 🎯 Type-Specific Details ($contentType)\n\n")
                    stage2Result.items.forEach { item ->
                        val att = visual.find { it.id == item.attachmentId }
                        append("### ${att?.filename ?: item.attachmentId}\n")
                        append(item.specificDetails)
                        append("\n\n")
                    }
                }.trim()
            } else {
                null
            }

        // Update attachments with vision analysis results
        val updatedAttachments =
            attachments.map { att ->
                val stage1Item = stage1Result.items.find { it.attachmentId == att.id }
                if (stage1Item != null) {
                    att.copy(
                        visionAnalysis =
                            VisionAnalysisResult(
                                model = model.id,
                                description = stage1Item.generalDescription,
                                confidence = 0.0,
                                analyzedAt = Instant.now(),
                            ),
                    )
                } else {
                    att
                }
            }

        return VisionContext(
            originalText = originalText,
            generalVisionSummary = generalSummary,
            typeSpecificVisionDetails = typeSpecificDetails,
            attachments = updatedAttachments,
        )
    } finally {
        // Cleanup temp files
        tmpFiles.forEach { (_, pth) -> runCatching { Files.deleteIfExists(pth) } }
    }
}

/**
 * Get type-specific extraction rules for Stage 2 vision analysis.
 */
private fun getTypeSpecificExtractionRules(contentType: ContentType): String =
    when (contentType) {
        ContentType.EMAIL -> {
            """
            - Extract email addresses (sender, recipients)
            - Extract subject line if visible
            - Identify attachment file names
            - Note any visible dates/timestamps
            """.trimIndent()
        }

        ContentType.JIRA -> {
            """
            - Extract JIRA keys (e.g., SDB-2080, PROJ-123)
            - Extract status (Open, In Progress, Done, etc.)
            - Extract assignee, reporter names
            - Extract epic, sprint information
            - Note error messages, stack traces in screenshots
            - Identify UI elements (buttons, forms, dialogs)
            """.trimIndent()
        }

        ContentType.CONFLUENCE -> {
            """
            - Extract page title if visible
            - Extract author name
            - Identify table data, chart values
            - Note section headings
            - Extract any code snippets visible
            """.trimIndent()
        }

        ContentType.LOG -> {
            """
            - Extract ERROR, WARN, FATAL messages
            - Extract timestamps
            - Extract service/component names
            - Extract exception class names
            - Extract critical numeric values (response times, error counts)
            """.trimIndent()
        }

        ContentType.GENERIC -> {
            """
            - Extract all visible text verbatim
            - Note UI element types (buttons, inputs, dropdowns)
            - Extract chart data (axis labels, values, legend)
            - Note error messages, warnings
            """.trimIndent()
        }
    }
