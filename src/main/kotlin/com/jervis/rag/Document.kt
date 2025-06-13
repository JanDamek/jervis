package com.jervis.rag

/**
 * Represents a document in the RAG system.
 * Contains the document content and metadata.
 */
data class Document(
    /**
     * The content of the document.
     */
    val pageContent: String,
    
    /**
     * Metadata associated with the document.
     */
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * Creates a document with RagMetadata.
     */
    constructor(pageContent: String, metadata: RagMetadata) : this(pageContent, metadata.toMap())
    
    /**
     * Returns a new document with updated metadata.
     */
    fun withMetadata(metadata: Map<String, Any>): Document {
        return copy(metadata = metadata)
    }
    
    /**
     * Returns a new document with updated metadata.
     */
    fun withMetadata(metadata: RagMetadata): Document {
        return copy(metadata = metadata.toMap())
    }
    
    /**
     * Returns a new document with updated content.
     */
    fun withPageContent(pageContent: String): Document {
        return copy(pageContent = pageContent)
    }
}