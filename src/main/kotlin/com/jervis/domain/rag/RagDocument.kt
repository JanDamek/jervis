package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Range of lines in a source file for precise location tracking
 */
data class LineRange(
    val start: Int,
    val end: Int
)

/**
 * Type of embedding used for the document content
 */
enum class EmbeddingType {
    /** Text embeddings with 768 dimensions for semantic text search */
    EMBEDDING_TEXT,
    /** Code embeddings with 3584 dimensions for code-specific search */
    EMBEDDING_CODE
}

/**
 * Relations to other symbols in the codebase (call graph, inheritance, imports)
 */
data class SymbolRelation(
    val type: RelationType,
    val targetSymbol: String,
    val targetPath: String? = null,
    val targetLineRange: LineRange? = null
)

/**
 * Types of relations between symbols
 */
enum class RelationType {
    CALLS,
    CALLED_BY,
    INHERITS_FROM,
    INHERITED_BY,
    IMPORTS,
    IMPORTED_BY,
    DEPENDS_ON,
    DEPENDENCY_OF
}

/**
 * Status of a document in the RAG system for historical versioning
 */
enum class DocumentStatus {
    /** Current active document that is searched by default */
    CURRENT,

    /** Historical version of a document, searchable only when specifically requested */
    HISTORICAL,

    /** Archived document that is not searchable but preserved for audit purposes */
    ARCHIVED,
}

/**
 * Represents a document with content and metadata.
 * This class is used for storing and retrieving documents from the vector database.
 */
data class RagDocument(
    val projectId: ObjectId,
    val documentType: RagDocumentType,
    val ragSourceType: RagSourceType,
    val pageContent: String,
    val clientId: ObjectId,
    val source: String? = null,
    val language: String? = null,
    val module: String? = null,
    val path: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val methodName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isDefaultBranch: Boolean = true,
    val inspirationOnly: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val documentStatus: DocumentStatus = DocumentStatus.CURRENT,
    val gitCommitHash: String? = null,
    val version: Int = 1,
    val archivedAt: Instant? = null,
    val lastModified: Instant = Instant.now(),
    
    // Enhanced metadata fields for Joern-based indexing
    /** Precise line range in source file (from Joern analysis) */
    val lineRange: LineRange? = null,
    /** Symbol name (method/class name from Joern) */
    val symbolName: String? = null,
    /** Chunk ID for methods split into multiple parts */
    val chunkId: String? = null,
    /** Type of embedding used for this document */
    val embeddingType: EmbeddingType? = null,
    /** Joern CPG node ID for traceability */
    val joernNodeId: String? = null,
    /** Relations to other symbols (call graph, inheritance, imports) */
    val relations: List<SymbolRelation> = emptyList()
)
