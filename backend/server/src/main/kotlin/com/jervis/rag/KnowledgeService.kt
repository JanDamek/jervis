package com.jervis.rag

import org.springframework.stereotype.Service

/**
 * Knowledge Service - Public API for RAG system.
 *
 * This is the ONLY public interface for all knowledge/RAG operations.
 * Everything else under com.jervis.rag.* is package-private implementation.
 *
 * Design:
 * - Simple, focused API
 * - Domain objects for input/output
 * - Hides complexity (vector stores, embeddings, chunking)
 */
interface KnowledgeService {
    /**
     * Search for knowledge matching the query.
     * Returns text with embedded documentId references that agent can use.
     */
    suspend fun search(request: SearchRequest): SearchResult

    /**
     * Retrieve complete document by documentId.
     * Agent sees documentId in search results and can request full document.
     */
    suspend fun getDocument(documentId: String): DocumentResult

    /**
     * Store knowledge in the system.
     * Returns documentIds that can be used for getDocument() or deleteDocument().
     */
    suspend fun store(request: StoreRequest): StoreResult

    /**
     * Delete complete document with all its chunks.
     * Uses documentId that agent sees in search results.
     */
    suspend fun deleteDocument(documentId: String)
}
