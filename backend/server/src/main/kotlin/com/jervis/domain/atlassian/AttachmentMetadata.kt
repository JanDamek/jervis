package com.jervis.domain.atlassian

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Attachment metadata for vision analysis.
 *
 * Stored in TaskDocument to enable vision model processing.
 */
@Serializable
data class AttachmentMetadata(
    /** Unique attachment ID */
    val id: String,
    /** Original filename */
    val filename: String,
    /** MIME type (e.g., "image/png", "application/pdf") */
    val mimeType: String,
    /** File size in bytes */
    val sizeBytes: Long,
    /** Storage path relative to DirectoryStructureService */
    val storagePath: String,
    /** Attachment type classification */
    val type: AttachmentType,
    /** Vision analysis result (populated by Qualifier Agent) */
    var visionAnalysis: VisionAnalysisResult? = null,
)

/**
 * Attachment type classification for processing strategy.
 */
enum class AttachmentType {
    /** PNG, JPG, JPEG, WEBP - high priority for vision analysis */
    IMAGE,

    /** PDF where Tika returned empty/minimal content (needs OCR) */
    PDF_SCANNED,

    /** PDF where Tika successfully extracted text (may still have charts/images) */
    PDF_STRUCTURED,

    /** Office documents (DOCX, XLSX, PPTX) */
    DOCUMENT,

    /** Unknown or unsupported type */
    UNKNOWN,
}

/**
 * Vision analysis result from vision model (e.g., qwen3-vl).
 */
@Serializable
data class VisionAnalysisResult(
    /** Model used for analysis (e.g., "qwen3-vl-tool-16k:latest") */
    val model: String,
    /** Textual description of visual content */
    val description: String,
    /** Detected entities (e.g., error messages, metrics) */
    val entities: List<String> = emptyList(),
    /** Confidence score 0.0-1.0 */
    val confidence: Double,
    /** Analysis timestamp */
    @Contextual
    val analyzedAt: Instant,
)

/**
 * Check if attachment should be processed with a vision model.
 */
fun AttachmentMetadata.shouldProcessWithVision(): Boolean =
    when (type) {
        AttachmentType.IMAGE -> true

        AttachmentType.PDF_SCANNED -> true

        AttachmentType.PDF_STRUCTURED -> false

        // Tika already extracted text
        AttachmentType.DOCUMENT -> false

        AttachmentType.UNKNOWN -> false
    }

/**
 * Helper to classify an attachment type based on a MIME type.
 */
fun classifyAttachmentType(
    mimeType: String,
    tikaContentLength: Int = 0,
): AttachmentType =
    when {
        mimeType.startsWith("image/") -> {
            AttachmentType.IMAGE
        }

        mimeType == "application/pdf" && tikaContentLength < 100 -> {
            AttachmentType.PDF_SCANNED
        }

        mimeType == "application/pdf" -> {
            AttachmentType.PDF_STRUCTURED
        }

        mimeType.contains("word") || mimeType.contains("excel") || mimeType.contains("powerpoint") -> {
            AttachmentType.DOCUMENT
        }

        else -> {
            AttachmentType.UNKNOWN
        }
    }
