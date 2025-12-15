package com.jervis.koog.qualifier

import com.jervis.entity.AttachmentMetadata
import com.jervis.entity.AttachmentType
import com.jervis.entity.VisionAnalysisResult
import com.jervis.service.storage.DirectoryStructureService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Vision Augmentation for Qualifier Agent.
 *
 * Processes attachments with vision models to extract visual information
 * that Tika cannot provide (screenshots, charts, diagrams, etc.).
 */

/**
 * Input to Vision Augmentation subgraph
 */
data class VisionAugmentationInput(
    val textContent: String,
    val attachments: List<AttachmentMetadata>,
)

/**
 * Output from Vision Augmentation subgraph
 */
data class VisionAugmentationOutput(
    val augmentedContent: String, // Original text + vision descriptions
    val processedAttachments: List<AttachmentMetadata>, // Attachments with visionAnalysis populated
)

/**
 * Data for vision analysis of a single attachment
 */
data class AttachmentVisionRequest(
    val attachmentId: String,
    val filename: String,
    val mimeType: String,
    val type: AttachmentType,
    val binaryData: ByteArray,
    val widthPixels: Int?,
    val heightPixels: Int?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttachmentVisionRequest

        if (attachmentId != other.attachmentId) return false
        if (filename != other.filename) return false
        if (mimeType != other.mimeType) return false
        if (type != other.type) return false
        if (!binaryData.contentEquals(other.binaryData)) return false
        if (widthPixels != other.widthPixels) return false
        if (heightPixels != other.heightPixels) return false

        return true
    }

    override fun hashCode(): Int {
        var result = attachmentId.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + binaryData.contentHashCode()
        result = 31 * result + (widthPixels ?: 0)
        result = 31 * result + (heightPixels ?: 0)
        return result
    }
}

/**
 * Result of vision analysis for a single attachment
 */
data class AttachmentVisionResponse(
    val attachmentId: String,
    val visionAnalysis: VisionAnalysisResult,
)

/**
 * Helper to load attachment binary data and prepare for vision analysis.
 *
 * FAIL-FAST: If attachment cannot be loaded, exception propagates.
 * This ensures the entire task fails if required attachment is missing/corrupted.
 */
suspend fun loadAttachmentData(
    attachment: AttachmentMetadata,
    directoryStructureService: DirectoryStructureService,
): AttachmentVisionRequest {
    val binaryData = directoryStructureService.readAttachment(attachment.storagePath)

    return AttachmentVisionRequest(
        attachmentId = attachment.id,
        filename = attachment.filename,
        mimeType = attachment.mimeType,
        type = attachment.type,
        binaryData = binaryData,
        widthPixels = attachment.widthPixels,
        heightPixels = attachment.heightPixels,
    )
}

/**
 * Augment text content with vision descriptions
 */
fun augmentContentWithVision(
    originalContent: String,
    visionResults: List<AttachmentVisionResponse>,
): String {
    if (visionResults.isEmpty()) {
        return originalContent
    }

    return buildString {
        append(originalContent)
        append("\n\n")
        append("## 🔍 Visual Content Analysis\n\n")
        append("The following visual content was analyzed using vision AI:\n\n")

        visionResults.forEach { result ->
            append("### Attachment ID: ${result.attachmentId}\n")
            append("**Vision Model:** ${result.visionAnalysis.model}\n")
            append("**Analysis:**\n")
            append(result.visionAnalysis.description)
            append("\n\n")
        }
    }
}

/**
 * Update attachment metadata with vision analysis results
 */
fun updateAttachmentsWithVision(
    originalAttachments: List<AttachmentMetadata>,
    visionResults: List<AttachmentVisionResponse>,
): List<AttachmentMetadata> {
    val visionMap = visionResults.associateBy { it.attachmentId }

    return originalAttachments.map { attachment ->
        val visionResult = visionMap[attachment.id]
        if (visionResult != null) {
            attachment.copy(visionAnalysis = visionResult.visionAnalysis)
        } else {
            attachment
        }
    }
}

/**
 * Execute vision analysis using Ollama HTTP API directly.
 * Bypasses Koog Prompt since it doesn't support vision yet.
 *
 * FAIL-FAST: If vision analysis fails, exception propagates to caller.
 * This marks the entire task as FAILED (per docs design).
 */
suspend fun executeVisionAnalysis(
    attachment: AttachmentVisionRequest,
    visionModel: ai.koog.prompt.llm.LLModel,
    ollamaBaseUrl: String,
): String {
    // Encode image to base64
    val base64Image = java.util.Base64.getEncoder().encodeToString(attachment.binaryData)

    // Build Ollama API request directly
    val requestBody = buildString {
        append("{")
        append("\"model\":\"${visionModel.id}\",")
        append("\"prompt\":\"$PROMPT_VISION\\n\\nAnalyze this visual content from file: ${attachment.filename}\",")
        append("\"images\":[\"$base64Image\"],")
        append("\"stream\":false")
        append("}")
    }

    // Make HTTP POST to Ollama API
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    try {
        val response: HttpResponse = client.post("$ollamaBaseUrl/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        // Parse JSON response
        val responseJson = response.bodyAsText()
        val jsonObject = Json.parseToJsonElement(responseJson).jsonObject
        val description = jsonObject["response"]?.jsonPrimitive?.content

        if (description.isNullOrBlank()) {
            throw IllegalStateException("Vision model returned no text for ${attachment.filename}")
        }

        return description
    } finally {
        client.close()
    }
}

// Vision prompt constant (will be used by executeVisionAnalysis)
private const val PROMPT_VISION = """
You are a Vision Analysis AI analyzing visual content from documents.

**YOUR TASK:**
Analyze the provided image/PDF and extract ALL relevant information visible in it.

**WHAT TO EXTRACT:**
- Text content (OCR if needed)
- Error messages or stack traces
- UI elements, buttons, forms
- Charts, graphs, diagrams with data
- Screenshots of applications or websites
- Scanned documents or forms
- Any technical details visible

**OUTPUT FORMAT:**
Provide a detailed description in plain text. Be thorough and precise.
Include ALL visible text, numbers, and technical details.
Describe visual elements (charts, UI) clearly.

**EXAMPLES:**
- Screenshot: "Error dialog showing 'NullPointerException at line 42 in UserService.java'. Stack trace shows Spring Boot application crash. Button 'OK' visible at bottom."
- Chart: "Bar chart titled 'Q4 Revenue by Region'. X-axis: North, South, East, West. Y-axis: Revenue in millions. Values: North=15M, South=12M, East=18M, West=10M."
- Form: "Employee registration form with fields: Name (text input), Email (text input), Department (dropdown showing 'Engineering'), Submit button at bottom."

Start your analysis now:
"""
