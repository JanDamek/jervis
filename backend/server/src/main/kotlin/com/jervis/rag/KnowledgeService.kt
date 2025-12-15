package com.jervis.rag

/**
 * Knowledge Service - Public API for RAG system.
 *
 * This is the ONLY public interface for all knowledge/RAG operations.
 * Everything else under com.jervis.rag.* is package-private implementation.
 *
 * Design:
 * - Atomic storage: Agent handles chunking, service just embeds and stores
 * - Hybrid search: BM25 + Vector (keyword + semantic)
 * - Simple, focused API with domain objects
 */
interface KnowledgeService {
    /**
     * Hybrid search using BM25 + Vector similarity.
     * Combines keyword matching (BM25) with semantic search (embeddings).
     *
     * @param alpha Balance: 0.0 = pure BM25, 1.0 = pure vector, 0.5 = hybrid
     */
    suspend fun searchHybrid(request: HybridSearchRequest): SearchResult

    /**
     * Search for knowledge matching the query (legacy vector-only search).
     * Returns text with embedded documentId references that agent can use.
     */
    suspend fun search(request: SearchRequest): SearchResult

    /**
     * Atomically store a single chunk with its embedding.
     * Agent is responsible for chunking and extracting entity context.
     * Service only generates embedding and stores in Weaviate with BM25 indexing.
     *
     * @return chunkId that can be used for graph linking
     */
    suspend fun storeChunk(request: StoreChunkRequest): String
}
