package com.jervis.koog.qualifier

import com.jervis.entity.AttachmentMetadata
import java.time.Instant

/**
 * Vision-related data models for Qualifier Agent.
 *
 * These models extend the base qualifier data flow to support vision analysis.
 */

/**
 * Attachment data loaded in memory for vision processing.
 */
data class AttachmentData(
    val id: String,
    val filename: String,
    val mimeType: String,
    val type: com.jervis.entity.AttachmentType,
    val binaryData: ByteArray,
    val widthPixels: Int,
    val heightPixels: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttachmentData

        if (id != other.id) return false
        if (filename != other.filename) return false
        if (mimeType != other.mimeType) return false
        if (type != other.type) return false
        if (!binaryData.contentEquals(other.binaryData)) return false
        if (widthPixels != other.widthPixels) return false
        if (heightPixels != other.heightPixels) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + binaryData.contentHashCode()
        result = 31 * result + widthPixels
        result = 31 * result + heightPixels
        return result
    }
}

/**
 * Chunk context with attachments for vision analysis.
 */
data class ChunkWithContext(
    val chunkText: String,
    val chunkIndex: Int,
    val referencedAttachments: List<AttachmentData>,
)

/**
 * Vision description from vision model.
 */
data class VisionDescription(
    val attachmentId: String,
    val filename: String,
    val model: String,
    val description: String,
)

/**
 * Augmented chunk with vision descriptions.
 */
data class AugmentedChunk(
    val originalText: String,
    val visionDescriptions: List<VisionDescription>,
    val chunkIndex: Int,
) {
    /**
     * Combine original text with vision descriptions.
     */
    fun toCombinedText(): String =
        buildString {
            append(originalText)
            if (visionDescriptions.isNotEmpty()) {
                append("\n\n## Visual Content Analysis\n\n")
                visionDescriptions.forEach { vision ->
                    append("### ${vision.filename}\n")
                    append(vision.description)
                    append("\n\n")
                }
            }
        }
}

/**
 * Helper to convert AttachmentMetadata to AttachmentData (load binary).
 */
suspend fun AttachmentMetadata.toAttachmentData(
    directoryStructureService: com.jervis.service.storage.DirectoryStructureService,
): AttachmentData {
    val binaryData = directoryStructureService.readAttachment(storagePath)

    return AttachmentData(
        id = id,
        filename = filename,
        mimeType = mimeType,
        type = type,
        binaryData = binaryData,
        widthPixels = widthPixels ?: 1024, // Default if not available
        heightPixels = heightPixels ?: 1024,
    )
}
