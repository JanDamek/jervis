package com.jervis.persistence.mongo

import com.jervis.rag.Document
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing RAG chunk metadata in MongoDB.
 * Provides methods for saving and retrieving chunk metadata.
 */
@Service
class ChunkMetadataService(private val repository: RagChunkMetadataRepository) {
    
    private val logger = KotlinLogging.logger {}
    
    /**
     * Save chunk metadata to MongoDB.
     *
     * @param chunkId The ID of the chunk (same as in vector database)
     * @param document The RAG document
     * @param embeddingId The ID of the embedding in the vector database
     * @return The saved document
     */
    suspend fun saveChunkMetadata(chunkId: String, document: Document, embeddingId: String): RagChunkMetadataDocument {
        logger.debug { "Saving chunk metadata for chunk $chunkId" }
        
        // Extract metadata from the document
        val projectId = document.metadata["project"]?.toString() ?: "unknown"
        val filePath = document.metadata["file_path"]?.toString() ?: ""
        val positionInFile = document.metadata["chunk_start"]?.toString()?.toIntOrNull() ?: 0
        val documentType = document.metadata["type"]?.toString() ?: "unknown"
        val language = document.metadata["language"]?.toString()
        
        // Create a summary of the content (first 100 characters)
        val contentSummary = document.pageContent.take(100).let {
            if (document.pageContent.length > 100) "$it..." else it
        }
        
        // Create the MongoDB document
        val metadataDocument = RagChunkMetadataDocument(
            chunkId = chunkId,
            projectId = projectId,
            filePath = filePath,
            positionInFile = positionInFile,
            contentSummary = contentSummary,
            fullContent = document.pageContent,
            embeddingId = embeddingId,
            documentType = documentType,
            language = language,
            metadata = document.metadata
        )
        
        return repository.save(metadataDocument)
    }
    
    /**
     * Update an existing chunk's metadata.
     *
     * @param chunkId The ID of the chunk to update
     * @param document The updated RAG document
     * @return The updated document
     */
    suspend fun updateChunkMetadata(chunkId: String, document: Document): RagChunkMetadataDocument? {
        logger.debug { "Updating chunk metadata for chunk $chunkId" }
        
        val existingDocument = repository.findByChunkId(chunkId).firstOrNull()
        
        if (existingDocument == null) {
            logger.warn { "Chunk $chunkId not found for update" }
            return null
        }
        
        // Create a summary of the content (first 100 characters)
        val contentSummary = document.pageContent.take(100).let {
            if (document.pageContent.length > 100) "$it..." else it
        }
        
        // Create updated document
        val updatedDocument = existingDocument.copy(
            fullContent = document.pageContent,
            contentSummary = contentSummary,
            updatedAt = Instant.now(),
            metadata = document.metadata
        )
        
        return repository.save(updatedDocument)
    }
    
    /**
     * Get chunk detail by ID.
     *
     * @param chunkId The ID of the chunk
     * @return The chunk metadata document if found
     */
    suspend fun getChunkDetail(chunkId: String): RagChunkMetadataDocument? {
        logger.debug { "Getting chunk detail for chunk $chunkId" }
        return repository.findByChunkId(chunkId).firstOrNull()
    }
    
    /**
     * Get all chunks for a project.
     *
     * @param projectId The ID of the project
     * @return A Flow of chunk metadata documents
     */
    fun getProjectChunks(projectId: String): Flow<RagChunkMetadataDocument> {
        logger.debug { "Getting all chunks for project $projectId" }
        return repository.findByProjectId(projectId)
    }
    
    /**
     * Get all chunks for a file in a project.
     *
     * @param projectId The ID of the project
     * @param filePath The path of the file
     * @return A Flow of chunk metadata documents
     */
    fun getFileChunks(projectId: String, filePath: String): Flow<RagChunkMetadataDocument> {
        logger.debug { "Getting chunks for file $filePath in project $projectId" }
        return repository.findByProjectIdAndFilePath(projectId, filePath)
    }
    
    /**
     * Mark a chunk as obsolete.
     *
     * @param chunkId The ID of the chunk
     * @return The updated document
     */
    suspend fun markChunkAsObsolete(chunkId: String): RagChunkMetadataDocument? {
        logger.debug { "Marking chunk $chunkId as obsolete" }
        
        val existingDocument = repository.findByChunkId(chunkId).firstOrNull()
        
        if (existingDocument == null) {
            logger.warn { "Chunk $chunkId not found for status update" }
            return null
        }
        
        val updatedDocument = existingDocument.copy(
            status = "obsolete",
            updatedAt = Instant.now()
        )
        
        return repository.save(updatedDocument)
    }
}