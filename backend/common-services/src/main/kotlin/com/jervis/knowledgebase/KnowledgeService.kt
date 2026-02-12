package com.jervis.knowledgebase

import com.jervis.common.types.ClientId
import com.jervis.knowledgebase.model.EvidencePack
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.knowledgebase.model.CpgIngestRequest
import com.jervis.knowledgebase.model.CpgIngestResult
import com.jervis.knowledgebase.model.GitStructureIngestRequest
import com.jervis.knowledgebase.model.GitStructureIngestResult
import com.jervis.knowledgebase.model.IngestRequest
import com.jervis.knowledgebase.model.IngestResult
import com.jervis.knowledgebase.model.RetrievalRequest
import kotlinx.rpc.annotations.Rpc

/**
 * Knowledge Service - Public API for RAG system.
 *
 * This is the ONLY public interface for all knowledge/RAG operations.
 * Everything else under com.jervis.knowledgebase.* is package-private implementation.
 */
@Rpc
interface KnowledgeService {
    /**
     * Store new knowledge (text/code/ticket/log/meeting).
     * Handles chunking, embedding, canonicalization, and graph linking.
     * Idempotent based on sourceUrn and content hash.
     */
    suspend fun ingest(request: IngestRequest): IngestResult

    /**
     * Full document ingestion with attachments.
     * Processes attachments (vision for images, Tika for documents),
     * generates summary and routing hints for qualification.
     */
    suspend fun ingestFull(request: FullIngestRequest): FullIngestResult

    /**
     * Retrieval for a query. Builds EvidencePack (RAG and Graph expand).
     */
    suspend fun retrieve(request: RetrievalRequest): EvidencePack

    suspend fun traverse(
        clientId: ClientId,
        startKey: String,
        spec: com.jervis.knowledgebase.service.graphdb.model.TraversalSpec,
    ): List<com.jervis.knowledgebase.service.graphdb.model.GraphNode>

    /**
     * Structural ingest of git repository (no LLM).
     * Creates graph nodes for repository, branches, files, and classes.
     * Called from GitContinuousIndexer during initial branch index.
     */
    suspend fun ingestGitStructure(request: GitStructureIngestRequest): GitStructureIngestResult

    /**
     * Run Joern CPG deep analysis and import semantic edges.
     *
     * Dispatches Joern K8s Job to generate CPG, then imports pruned edges
     * (calls, extends, uses_type) into ArangoDB graph.
     * Requires that structural ingest (tree-sitter) has already run.
     */
    suspend fun ingestCpg(request: CpgIngestRequest): CpgIngestResult

    /**
     * Purge all KB data (RAG chunks + graph refs) for a given sourceUrn.
     * Returns true if purge was successful.
     */
    suspend fun purge(sourceUrn: String): Boolean
}
