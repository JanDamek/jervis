package com.jervis.rag

import org.bson.types.ObjectId

data class SearchRequest(
    val query: String,
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val maxResults: Int = 20,
    val minScore: Double = 0.15,
    val embeddingType: EmbeddingType = EmbeddingType.TEXT,
    val knowledgeTypes: Set<KnowledgeType>? = null,
)

@JvmInline
value class SearchResult(
    val text: String,
)

@JvmInline
value class DocumentResult(
    val text: String,
)

data class StoreRequest(
    val documents: List<DocumentToStore>,
)

data class DocumentToStore(
    val documentId: String,
    val content: String,
    val clientId: ObjectId,
    val type: KnowledgeType,
    val embeddingType: EmbeddingType = EmbeddingType.TEXT,
    val severity: KnowledgeSeverity? = null,
    val title: String? = null,
    val location: String? = null,
    val relatedDocs: List<String> = emptyList(),
    val projectId: ObjectId? = null,
)

data class StoreResult(
    val documents: List<StoredDocument>,
)

data class StoredDocument(
    val documentId: String,
    val chunkIds: List<String>,
    val totalChunks: Int,
)

enum class KnowledgeType {
    CODE,
    RULE,
    MEMORY,
    DOCUMENT,
}

enum class KnowledgeSeverity {
    MUST,
    SHOULD,
    INFO,
}

enum class EmbeddingType {
    TEXT,
    CODE,
}
