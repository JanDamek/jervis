package com.jervis.common.dto

import kotlinx.serialization.Serializable

@Serializable
data class TikaProcessRequest(
    val source: Source,
    val includeMetadata: Boolean = true,
) {
    @Serializable
    sealed class Source {
        @Serializable
        data class FilePath(
            val path: String,
        ) : Source()

        @Serializable
        data class FileBytes(
            val fileName: String,
            val dataBase64: String,
        ) : Source()
    }
}

@Serializable
data class TikaProcessResult(
    val plainText: String,
    val metadata: TikaMetadata? = null,
    val success: Boolean,
    val errorMessage: String? = null,
)

@Serializable
data class TikaMetadata(
    val title: String? = null,
    val author: String? = null,
    val creationDate: String? = null,
    val lastModified: String? = null,
    val contentType: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val keywords: List<String> = emptyList(),
    val customProperties: Map<String, String> = emptyMap(),
    val sourceLocation: TikaSourceLocation? = null,
)

@Serializable
data class TikaSourceLocation(
    val documentPath: String,
    val pageNumber: Int? = null,
    val paragraphIndex: Int? = null,
    val characterOffset: Int? = null,
    val sectionTitle: String? = null,
)
