package com.jervis.controller

import com.jervis.persistence.mongo.ChunkMetadataService
import com.jervis.persistence.mongo.RagChunkMetadataDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * REST controller for retrieving RAG chunk details.
 */
@RestController
@RequestMapping("/api/chunks")
class ChunkController(private val chunkMetadataService: ChunkMetadataService) {
    
    /**
     * Get chunk detail by ID.
     *
     * @param chunkId The ID of the chunk
     * @return The chunk metadata document
     */
    @GetMapping("/{chunkId}")
    suspend fun getChunkDetail(@PathVariable chunkId: String): RagChunkMetadataDocument {
        return chunkMetadataService.getChunkDetail(chunkId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk not found")
    }
    
    /**
     * Get all chunks for a project.
     *
     * @param projectId The ID of the project
     * @return A list of chunk metadata documents
     */
    @GetMapping("/project/{projectId}")
    fun getProjectChunks(@PathVariable projectId: String): Flow<RagChunkMetadataDocument> {
        return chunkMetadataService.getProjectChunks(projectId)
    }
    
    /**
     * Get all chunks for a file in a project.
     *
     * @param projectId The ID of the project
     * @param filePath The path of the file
     * @return A list of chunk metadata documents
     */
    @GetMapping("/project/{projectId}/file")
    fun getFileChunks(
        @PathVariable projectId: String,
        @RequestParam filePath: String
    ): Flow<RagChunkMetadataDocument> {
        return chunkMetadataService.getFileChunks(projectId, filePath)
    }
    
    /**
     * Get a summary of chunks for a project.
     * Returns only basic information about each chunk.
     *
     * @param projectId The ID of the project
     * @return A list of chunk summaries
     */
    @GetMapping("/project/{projectId}/summary")
    fun getProjectChunksSummary(@PathVariable projectId: String): Flow<ChunkSummary> {
        return chunkMetadataService.getProjectChunks(projectId)
            .map { chunk ->
                ChunkSummary(
                    chunkId = chunk.chunkId,
                    projectId = chunk.projectId,
                    filePath = chunk.filePath,
                    contentSummary = chunk.contentSummary,
                    documentType = chunk.documentType,
                    language = chunk.language,
                    status = chunk.status,
                    createdAt = chunk.createdAt
                )
            }
    }
    
    /**
     * Data class for chunk summary response.
     */
    data class ChunkSummary(
        val chunkId: String,
        val projectId: String,
        val filePath: String,
        val contentSummary: String,
        val documentType: String,
        val language: String?,
        val status: String,
        val createdAt: java.time.Instant
    )
}