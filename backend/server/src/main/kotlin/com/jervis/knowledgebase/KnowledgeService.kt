package com.jervis.knowledgebase

import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.IngestResult

/**
 * Knowledge Service - Public API for RAG system.
 *
 * This is the ONLY public interface for all knowledge/RAG operations.
 * Everything else under com.jervis.knowledgebase.* is package-private implementation.
 */
interface KnowledgeService {
    /**
     * Store new knowledge (text/code/ticket/log/meeting).
     * Handles chunking, embedding, canonicalization, and graph linking.
     * Idempotent based on sourceUrn and content hash.
     */
    suspend fun ingest(request: IngestRequest): IngestResult

    /**
     * Retrieval for a query. Sestavuje EvidencePack (RAG + Graph expand).
     */
    suspend fun retrieve(request: RetrievalRequest): EvidencePack
}
