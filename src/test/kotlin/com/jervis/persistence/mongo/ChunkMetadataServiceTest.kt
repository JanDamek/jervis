package com.jervis.persistence.mongo

import com.jervis.rag.Document
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ChunkMetadataServiceTest {

    @Mock
    private lateinit var repository: RagChunkMetadataRepository

    private lateinit var service: ChunkMetadataService

    @BeforeEach
    fun setup() {
        service = ChunkMetadataService(repository)
    }

    @Test
    fun saveChunkMetadataShouldCreateAndSaveDocument() {
        runBlocking {
            // Arrange
            val chunkId = "test-chunk-id"
            val embeddingId = "test-embedding-id"
            val document = Document(
                pageContent = "Test content",
                metadata = mapOf(
                    "project" to "123",
                    "file_path" to "/test/path.kt",
                    "chunk_start" to 10,
                    "type" to "code",
                    "language" to "kotlin"
                )
            )
            
            val savedDocument = RagChunkMetadataDocument(
                chunkId = chunkId,
                projectId = "123",
                filePath = "/test/path.kt",
                positionInFile = 10,
                contentSummary = "Test content",
                fullContent = "Test content",
                embeddingId = embeddingId,
                documentType = "code",
                language = "kotlin",
                metadata = document.metadata
            )
            
            `when`(repository.save(any())).thenReturn(savedDocument)
            
            // Act
            val result = service.saveChunkMetadata(chunkId, document, embeddingId)
            
            // Assert
            assertNotNull(result)
            assertEquals(chunkId, result.chunkId)
            assertEquals("123", result.projectId)
            assertEquals("/test/path.kt", result.filePath)
            assertEquals(10, result.positionInFile)
            assertEquals("Test content", result.fullContent)
            assertEquals(embeddingId, result.embeddingId)
            assertEquals("code", result.documentType)
            assertEquals("kotlin", result.language)
            
            verify(repository).save(any())
        }
    }
    
    @Test
    fun updateChunkMetadataShouldUpdateExistingDocument() {
        runBlocking {
            // Arrange
            val chunkId = "test-chunk-id"
            val document = Document(
                pageContent = "Updated content",
                metadata = mapOf(
                    "project" to "123",
                    "file_path" to "/test/path.kt",
                    "type" to "code",
                    "language" to "kotlin"
                )
            )
            
            val existingDocument = RagChunkMetadataDocument(
                chunkId = chunkId,
                projectId = "123",
                filePath = "/test/path.kt",
                positionInFile = 10,
                contentSummary = "Test content",
                fullContent = "Test content",
                embeddingId = "test-embedding-id",
                documentType = "code",
                language = "kotlin",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                metadata = mapOf()
            )
            
            val updatedDocument = existingDocument.copy(
                fullContent = "Updated content",
                contentSummary = "Updated content",
                metadata = document.metadata
            )
            
            `when`(repository.findByChunkId(chunkId)).thenReturn(flowOf(existingDocument))
            `when`(repository.save(any())).thenReturn(updatedDocument)
            
            // Act
            val result = service.updateChunkMetadata(chunkId, document)
            
            // Assert
            assertNotNull(result)
            assertEquals(chunkId, result!!.chunkId)
            assertEquals("Updated content", result.fullContent)
            assertEquals("Updated content", result.contentSummary)
            
            verify(repository).findByChunkId(chunkId)
            verify(repository).save(any())
        }
    }
    
    @Test
    fun updateChunkMetadataShouldReturnNullWhenChunkNotFound() {
        runBlocking {
            // Arrange
            val chunkId = "non-existent-chunk-id"
            val document = Document(
                pageContent = "Updated content",
                metadata = mapOf()
            )
            
            `when`(repository.findByChunkId(chunkId)).thenReturn(flowOf())
            
            // Act
            val result = service.updateChunkMetadata(chunkId, document)
            
            // Assert
            assertNull(result)
            
            verify(repository).findByChunkId(chunkId)
        }
    }
    
    @Test
    fun getChunkDetailShouldReturnChunkWhenFound() {
        runBlocking {
            // Arrange
            val chunkId = "test-chunk-id"
            val document = RagChunkMetadataDocument(
                chunkId = chunkId,
                projectId = "123",
                filePath = "/test/path.kt",
                positionInFile = 10,
                contentSummary = "Test content",
                fullContent = "Test content",
                embeddingId = "test-embedding-id",
                documentType = "code",
                language = "kotlin",
                metadata = mapOf()
            )
            
            `when`(repository.findByChunkId(chunkId)).thenReturn(flowOf(document))
            
            // Act
            val result = service.getChunkDetail(chunkId)
            
            // Assert
            assertNotNull(result)
            assertEquals(chunkId, result!!.chunkId)
            
            verify(repository).findByChunkId(chunkId)
        }
    }
    
    @Test
    fun getChunkDetailShouldReturnNullWhenChunkNotFound() {
        runBlocking {
            // Arrange
            val chunkId = "non-existent-chunk-id"
            
            `when`(repository.findByChunkId(chunkId)).thenReturn(flowOf())
            
            // Act
            val result = service.getChunkDetail(chunkId)
            
            // Assert
            assertNull(result)
            
            verify(repository).findByChunkId(chunkId)
        }
    }
    
    @Test
    fun getProjectChunksShouldReturnChunksForProject() {
        runBlocking {
            // Arrange
            val projectId = "123"
            val document = RagChunkMetadataDocument(
                chunkId = "test-chunk-id",
                projectId = projectId,
                filePath = "/test/path.kt",
                positionInFile = 10,
                contentSummary = "Test content",
                fullContent = "Test content",
                embeddingId = "test-embedding-id",
                documentType = "code",
                language = "kotlin",
                metadata = mapOf()
            )
            
            `when`(repository.findByProjectId(projectId)).thenReturn(flowOf(document))
            
            // Act
            val result = service.getProjectChunks(projectId)
            
            // Assert
            assertNotNull(result)
            
            verify(repository).findByProjectId(projectId)
        }
    }
}