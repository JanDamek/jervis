package com.jervis.koog.vision

import ai.koog.prompt.dsl.prompt
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.domain.atlassian.shouldProcessWithVision
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.SmartModelSelector
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

@Serializable
data class VisionAnalysisResult(
    val descriptions: List<AttachmentDescription>,
)

@Serializable
data class AttachmentDescription(
    val filename: String,
    val description: String,
    val extractedText: String,
    val entities: List<String>,
    val metadata: List<String>,
)

private const val VISION_SYSTEM_PROMPT =
    "Extract ALL visible information from the attached image/document. " +
        "Provide: description, extracted text (transcribe exactly), " +
        "entities (numbers, dates, codes, identifiers), metadata."

/**
 * Standalone service for analyzing visual attachments (images, PDFs).
 *
 * Can be used across multiple contexts:
 * - Qualifier agent
 * - Task processing
 * - Document analysis
 */
@Service
class VisionAnalysisAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val providerSelector: OllamaProviderSelector,
    private val modelSelector: SmartModelSelector,
    private val directoryStructureService: DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun analyze(attachments: List<AttachmentMetadata>): VisionAnalysisResult {
        val visualAttachments = attachments.filter { it.shouldProcessWithVision() }
        if (visualAttachments.isEmpty()) {
            return VisionAnalysisResult(emptyList())
        }

        logger.info { "VISION_START | attachments=${visualAttachments.size}" }

        val executor = promptExecutorFactory.getExecutor(providerSelector.getProvider())

        // Process each attachment individually
        val descriptions =
            visualAttachments.map { attachment ->
                logger.info { "VISION_PROCESSING | file=${attachment.filename}" }

                // Get image dimensions for this specific attachment
                val imageMetadata =
                    try {
                        if (attachment.mimeType.startsWith("image/")) {
                            val fullPath = directoryStructureService.workspaceRoot().resolve(attachment.storagePath)
                            val imageFile = fullPath.toFile()
                            val bufferedImage: BufferedImage = ImageIO.read(imageFile)
                            SmartModelSelector.ImageMetadata(
                                widthPixels = bufferedImage.width,
                                heightPixels = bufferedImage.height,
                                format = attachment.mimeType,
                            )
                        } else {
                            // For PDFs, estimate dimensions
                            SmartModelSelector.ImageMetadata(1920, 1080, attachment.mimeType)
                        }
                    } catch (e: Exception) {
                        logger.warn { "Failed to read image dimensions for ${attachment.filename}: ${e.message}" }
                        SmartModelSelector.ImageMetadata(1024, 1024, attachment.mimeType)
                    }

                // Select a model for this specific attachment
                val visionModel =
                    modelSelector.selectVisionModel(
                        baseModelName = SmartModelSelector.BaseModelTypeEnum.VL.modelName,
                        textPrompt = VISION_SYSTEM_PROMPT,
                        images = listOf(imageMetadata),
                    )

                logger.info { "MODEL_SELECTED | file=${attachment.filename} | model=${visionModel.id}" }

                // Create a prompt for this single attachment
                val visionPrompt =
                    prompt("Vision analysis - ${attachment.filename}") {
                        system(VISION_SYSTEM_PROMPT)
                        user {
                            text("Analyze this attachment: ${attachment.filename} (${attachment.mimeType})")
                            val fullPath = directoryStructureService.workspaceRoot().resolve(attachment.storagePath)
                            image(Path(fullPath.toString()))
                        }
                    }

                // Execute vision analysis for this single attachment
                val response = executor.execute(prompt = visionPrompt, model = visionModel)
                val responseText = response.toString()

                logger.info {
                    "VISION_FILE_COMPLETE | file=${attachment.filename} | responseLength=${responseText.length}"
                }

                AttachmentDescription(
                    filename = attachment.filename,
                    description = responseText,
                    extractedText = "",
                    entities = emptyList(),
                    metadata = emptyList(),
                )
            }

        logger.info {
            "VISION_ALL_COMPLETE | totalDescriptions=${descriptions.size}"
        }

        return VisionAnalysisResult(descriptions)
    }
}
