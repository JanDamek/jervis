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
import java.io.File
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

        // Get actual image dimensions for accurate token estimation
        val imageMetadata =
            visualAttachments.map { attachment ->
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
            }

        val visionModel =
            modelSelector.selectVisionModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.VL.modelName,
                textPrompt = "Analyze attachments and extract all information",
                images = imageMetadata,
            )

        logger.info {
            "MODEL_SELECTED | model=${visionModel.id} | images=${imageMetadata.size}"
        }

        val visionPrompt =
            prompt("Vision analysis") {
                system(
                    "Extract ALL visible information from the attached images/documents. " +
                        "For each file provide: description, extracted text (transcribe exactly), " +
                        "entities (numbers, dates, codes, identifiers), metadata.",
                )
                user {
                    text("Analyze each attachment:\n" + visualAttachments.joinToString("\n") { "File: ${it.filename} (${it.mimeType})" })
                    visualAttachments.forEach { attachment ->
                        val fullPath = directoryStructureService.workspaceRoot().resolve(attachment.storagePath)
                        image(Path(fullPath.toString()))
                    }
                }
            }

        val executor = promptExecutorFactory.getExecutor(providerSelector.getProvider())
        val response = executor.execute(prompt = visionPrompt, model = visionModel)

        val responseText = response.toString()

        val descriptions =
            visualAttachments.map { attachment ->
                AttachmentDescription(
                    filename = attachment.filename,
                    description = responseText,
                    extractedText = "",
                    entities = emptyList(),
                    metadata = emptyList(),
                )
            }

        logger.info {
            "VISION_COMPLETE | descriptions=${descriptions.size} | responseLength=${responseText.length}"
        }

        return VisionAnalysisResult(descriptions)
    }
}
