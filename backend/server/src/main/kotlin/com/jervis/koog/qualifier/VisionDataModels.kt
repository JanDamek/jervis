package com.jervis.koog.qualifier

/**
 * Vision-related data models for Qualifier Agent.
 *
 * These models extend the base qualifier data flow to support vision analysis.
 */
data class AttachmentData(
    val id: String,
    val filename: String,
    val mimeType: String,
    val type: com.jervis.entity.AttachmentType,
    val binaryData: ByteArray,
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

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + binaryData.contentHashCode()
        return result
    }
}

/**
 * Vision description from vision model.
 */
data class VisionDescription(
    val attachmentId: String,
    val filename: String,
    val model: String,
    val description: String,
)
