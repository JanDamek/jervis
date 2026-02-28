package com.jervis.dto.kb

import kotlinx.serialization.Serializable

/**
 * State of a KB document in its lifecycle.
 */
@Serializable
enum class KbDocumentStateEnum {
    /** File uploaded, awaiting content extraction / classification. */
    UPLOADED,

    /** Content extracted (via Tika or direct text read), ready for KB ingest. */
    EXTRACTED,

    /** Ingested into KB (RAG + graph). */
    INDEXED,

    /** Processing or ingestion failed. */
    FAILED,
}

/**
 * Classification category for an uploaded document.
 */
@Serializable
enum class KbDocumentCategoryEnum {
    TECHNICAL,
    BUSINESS,
    LEGAL,
    PROCESS,
    MEETING_NOTES,
    REPORT,
    SPECIFICATION,
    OTHER,
}

/**
 * Full DTO returned to the UI (list + detail views).
 */
@Serializable
data class KbDocumentDto(
    val id: String = "",
    val clientId: String,
    val projectId: String? = null,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val state: KbDocumentStateEnum = KbDocumentStateEnum.UPLOADED,
    val category: KbDocumentCategoryEnum = KbDocumentCategoryEnum.OTHER,
    val title: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val extractedTextPreview: String? = null,
    val pageCount: Int? = null,
    val contentHash: String? = null,
    val sourceUrn: String? = null,
    val errorMessage: String? = null,
    val uploadedAt: String = "",
    val indexedAt: String? = null,
)

/**
 * Summary DTO for listing (lighter than full DTO).
 */
@Serializable
data class KbDocumentSummaryDto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val state: KbDocumentStateEnum,
    val category: KbDocumentCategoryEnum,
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val uploadedAt: String,
    val errorMessage: String? = null,
)

/**
 * Request DTO for uploading a document.
 * The binary data is sent as base64 (same pattern as GPG certificates and audio chunks).
 */
@Serializable
data class KbDocumentUploadDto(
    val clientId: String,
    val projectId: String? = null,
    val filename: String,
    val mimeType: String,
    val dataBase64: String,
    val title: String? = null,
    val description: String? = null,
    val category: KbDocumentCategoryEnum = KbDocumentCategoryEnum.OTHER,
    val tags: List<String> = emptyList(),
)

/**
 * Request DTO for updating document metadata (classify, retag, etc.).
 */
@Serializable
data class KbDocumentUpdateDto(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val category: KbDocumentCategoryEnum? = null,
    val tags: List<String>? = null,
)

/**
 * Request DTO for deleting a document.
 */
@Serializable
data class KbDocumentDeleteDto(
    val id: String,
)

/**
 * Response DTO for downloading document content.
 */
@Serializable
data class KbDocumentContentDto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val dataBase64: String,
)
