package com.jervis.persistence.mongo

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository interface for accessing RAG chunk metadata in MongoDB.
 * Uses Kotlin Coroutines for reactive operations.
 */
@Repository
interface RagChunkMetadataRepository : CoroutineCrudRepository<RagChunkMetadataDocument, String> {
    
    /**
     * Find a chunk by its ID.
     *
     * @param chunkId The ID of the chunk
     * @return A Flow containing the chunk if found
     */
    fun findByChunkId(chunkId: String): Flow<RagChunkMetadataDocument>
    
    /**
     * Find all chunks for a specific project.
     *
     * @param projectId The ID of the project
     * @return A Flow of chunks belonging to the project
     */
    fun findByProjectId(projectId: String): Flow<RagChunkMetadataDocument>
    
    /**
     * Find all chunks for a specific file in a project.
     *
     * @param projectId The ID of the project
     * @param filePath The path of the file
     * @return A Flow of chunks from the specified file
     */
    fun findByProjectIdAndFilePath(projectId: String, filePath: String): Flow<RagChunkMetadataDocument>
    
    /**
     * Find all active chunks for a specific project.
     *
     * @param projectId The ID of the project
     * @param status The status of the chunks to find (default: "active")
     * @return A Flow of active chunks belonging to the project
     */
    fun findByProjectIdAndStatus(projectId: String, status: String = "active"): Flow<RagChunkMetadataDocument>
    
    /**
     * Find chunks by embedding ID.
     *
     * @param embeddingId The ID of the embedding in the vector database
     * @return A Flow of chunks with the specified embedding ID
     */
    fun findByEmbeddingId(embeddingId: String): Flow<RagChunkMetadataDocument>
}