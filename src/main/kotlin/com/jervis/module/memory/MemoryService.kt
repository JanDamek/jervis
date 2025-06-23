package com.jervis.module.memory

import com.jervis.entity.Project
import com.jervis.module.indexer.ChunkingService
import com.jervis.module.indexer.EmbeddingService
import com.jervis.module.vectordb.VectorDbService
import com.jervis.rag.Document
import com.jervis.rag.RagMetadata
import com.jervis.service.ProjectService
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * Service for managing memory items.
 * This service provides methods for creating, retrieving, updating, and deleting memory items,
 * as well as methods for processing meeting transcripts and indexing notes and history.
 * 
 * This service uses only the vector database (Qdrant) for storage and retrieval of memory items.
 */
@Service
class MemoryService(
    private val projectService: ProjectService,
    private val objectMapper: ObjectMapper,
    private val vectorDbService: VectorDbService,
    private val embeddingService: EmbeddingService,
    private val chunkingService: ChunkingService
) {
    private val logger = KotlinLogging.logger {}

    // Maximum size of a chunk in characters
    private val maxChunkSize = 300

    /**
     * Get all memory items for a specific project.
     *
     * @param project The project to get memory items for
     * @return A list of documents for the project
     */
    fun getMemoryItemsByProject(project: Project): List<Document> {
        val filter = MemoryDocument.createProjectFilter(project)
        return vectorDbService.searchSimilar(emptyList(), 100, filter)
    }

    /**
     * Get all memory items for a specific project and type.
     *
     * @param project The project to get memory items for
     * @param type The type of memory items to get
     * @return A list of documents for the project and type
     */
    fun getMemoryItemsByType(project: Project, type: MemoryItemType): List<Document> {
        val filter = MemoryDocument.createTypeFilter(project, type)
        return vectorDbService.searchSimilar(emptyList(), 100, filter)
    }

    /**
     * Get recent memory items for a specific project.
     *
     * @param project The project to get memory items for
     * @param limit The maximum number of items to return
     * @return A list of recent documents for the project
     */
    fun getRecentMemoryItems(project: Project, limit: Int = 10): List<Document> {
        val filter = MemoryDocument.createProjectFilter(project)
        val documents = vectorDbService.searchSimilar(emptyList(), 100, filter)

        // Sort by timestamp (descending) and take the most recent ones
        return documents
            .sortedByDescending { it.metadata["timestamp"] as? LocalDateTime ?: LocalDateTime.MIN }
            .take(limit)
    }

    /**
     * Search for memory items by content using traditional text search.
     * This method searches for exact matches in the content.
     *
     * @param project The project to search in
     * @param searchTerm The term to search for
     * @return A list of documents matching the search term
     */
    fun searchMemoryItemsByText(project: Project, searchTerm: String): List<Document> {
        // For text search, we'll use semantic search with the search term as the query
        // This is a simplification since Qdrant doesn't have built-in text search
        return searchMemoryItems(project, searchTerm)
    }

    /**
     * Search for memory items by content using semantic search.
     * This method searches for semantically similar content rather than exact matches.
     *
     * @param project The project to search in
     * @param query The query to search for
     * @param limit The maximum number of results to return
     * @return A list of documents semantically similar to the query
     */
    fun searchMemoryItems(project: Project, query: String, limit: Int = 10): List<Document> {
        try {
            // Generate embedding for the query using the query-specific method
            val queryEmbedding = embeddingService.generateQueryEmbedding(query)

            // Create filter for the project and memory source
            val filter = MemoryDocument.createProjectFilter(project)

            // Search for similar documents in the vector database
            return vectorDbService.searchSimilar(queryEmbedding, limit, filter)
        } catch (e: Exception) {
            logger.error(e) { "Semantic search failed: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Search for memory items by content using semantic search and group results by memory_id.
     * This method searches for semantically similar content and groups the results by memory_id,
     * returning the most recent items first.
     *
     * @param project The project to search in
     * @param query The query to search for
     * @param limit The maximum number of memory items to return
     * @return A list of documents semantically similar to the query, grouped by memory_id
     */
    fun searchMemoryItemsGrouped(project: Project, query: String, limit: Int = 10): List<Document> {
        try {
            // Generate embedding for the query using the query-specific method
            val queryEmbedding = embeddingService.generateQueryEmbedding(query)

            // Create filter for the project and memory source
            val filter = MemoryDocument.createProjectFilter(project)

            // Search for similar documents in the vector database (get more results to account for grouping)
            val results = vectorDbService.searchSimilar(queryEmbedding, limit * 3, filter)

            // Group results by memory_id and take the first chunk from each group
            return results
                .groupBy { it.metadata["memory_id"] as? String ?: "" }
                .map { (_, chunks) -> chunks.minByOrNull { it.metadata["chunk_index"] as? Int ?: 0 } ?: chunks.first() }
                // Sort by timestamp (descending)
                .sortedByDescending { it.metadata["timestamp"] as? LocalDateTime ?: LocalDateTime.MIN }
                .take(limit)
        } catch (e: Exception) {
            logger.error(e) { "Grouped semantic search failed: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Get all chunks for a specific memory item.
     *
     * @param memoryId The memory ID
     * @return A list of all chunks for the memory item, ordered by chunk index
     */
    fun getMemoryItemChunks(memoryId: String): List<Document> {
        try {
            // Create filter for the memory ID
            val filter = mapOf("memory_id" to memoryId)

            // Get all chunks for the memory item
            val chunks = vectorDbService.searchSimilar(emptyList(), 100, filter)

            // Sort by chunk index
            return chunks.sortedBy { it.metadata["chunk_index"] as? Int ?: 0 }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get memory item chunks: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Get the original content for a memory item.
     *
     * @param document The document (chunk) to get the original content for
     * @return The original content, or the document's content if original content is not available
     */
    fun getOriginalContent(document: Document): String {
        // Check if the document has original_content in its metadata
        val originalContent = document.metadata["original_content"] as? String
        if (originalContent != null) {
            return originalContent
        }

        // If not, check if it's part of a chunked memory item
        val memoryId = document.metadata["memory_id"] as? String
        if (memoryId != null) {
            // Get all chunks for the memory item
            val chunks = getMemoryItemChunks(memoryId)

            // If there's only one chunk, return its content
            if (chunks.size <= 1) {
                return document.pageContent
            }

            // Check if any chunk has the original content
            val originalFromChunks = chunks.firstOrNull { it.metadata["original_content"] != null }
                ?.metadata?.get("original_content") as? String

            if (originalFromChunks != null) {
                return originalFromChunks
            }

            // If not, reconstruct from all chunks
            return chunks
                .sortedBy { it.metadata["chunk_index"] as? Int ?: 0 }
                .joinToString(" ") { it.pageContent }
        }

        // If all else fails, return the document's content
        return document.pageContent
    }

    /**
     * Create a new memory item.
     *
     * @param project The project to create the memory item for
     * @param title The title of the memory item
     * @param content The content of the memory item
     * @param type The type of memory item
     * @param importance The importance of the memory item (1-10)
     * @param metadata Additional metadata for the memory item
     * @return The created memory document
     */
    @Transactional
    fun createMemoryItem(
        project: Project,
        title: String,
        content: String,
        type: MemoryItemType,
        importance: Int = 5,
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): Document {
        logger.info { "Creating memory item: $title for project: ${project.name}" }

        try {
            // Create a Document for the memory item
            val document = MemoryDocument.create(
                project = project,
                title = title,
                content = content,
                type = type,
                importance = importance,
                metadata = metadata,
                createdBy = createdBy
            )

            // Generate embedding for the content
            val embedding = embeddingService.generateEmbedding(content)

            // Store in vector database
            val vectorId = vectorDbService.storeDocument(document, embedding)
            logger.info { "Stored memory item in vector database with ID: $vectorId" }

            return document
        } catch (e: Exception) {
            logger.error(e) { "Failed to store memory item in vector database: ${e.message}" }
            throw e
        }
    }

    /**
     * Create a memory item with chunking by sentences.
     * This method splits the content into chunks of maximum size [maxChunkSize] characters,
     * with each chunk representing a sentence or group of sentences.
     * The original content is stored in the metadata of each chunk.
     *
     * @param project The project to create the memory item for
     * @param title The title of the memory item
     * @param content The content of the memory item
     * @param type The type of memory item
     * @param importance The importance of the memory item (1-10)
     * @param metadata Additional metadata for the memory item
     * @param createdBy The user who created the memory item
     * @return A list of created memory documents (chunks)
     */
    @Transactional
    fun createChunkedMemoryItem(
        project: Project,
        title: String,
        content: String,
        type: MemoryItemType,
        importance: Int = 5,
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): List<Document> {
        logger.info { "Creating chunked memory item: $title for project: ${project.name}" }

        try {
            // Split content into sentences
            val sentences = splitIntoSentences(content)

            // Group sentences into chunks of maximum size maxChunkSize
            val chunks = createChunksFromSentences(sentences)

            // Create a unique ID for this memory item (to group all chunks)
            val memoryId = UUID.randomUUID().toString()

            // Create documents for each chunk
            val documents = chunks.mapIndexed { index, chunkContent ->
                // Create metadata for the chunk
                val chunkMetadata = RagMetadata(
                    type = when (type) {
                        MemoryItemType.MEETING_TRANSCRIPT -> com.jervis.rag.DocumentType.MEETING
                        MemoryItemType.NOTE -> com.jervis.rag.DocumentType.NOTE
                        MemoryItemType.DECISION, 
                        MemoryItemType.PLAN,
                        MemoryItemType.HISTORY -> com.jervis.rag.DocumentType.TEXT
                    },
                    project = project.id!!.toInt(),
                    source = "memory",
                    tags = listOf(type.name.lowercase()),
                    timestamp = LocalDateTime.now(),
                    chunkIndex = index,
                    createdBy = createdBy,
                    extra = mapOf(
                        "title" to title,
                        "importance" to importance,
                        "memory_type" to type.name,
                        "memory_id" to memoryId,
                        "updated_at" to LocalDateTime.now(),
                        "original_content" to content,
                        "total_chunks" to chunks.size
                    ) + (metadata ?: emptyMap())
                )

                // Create document for the chunk
                val document = Document(chunkContent, chunkMetadata)

                // Generate embedding for the chunk
                val embedding = embeddingService.generateEmbedding(chunkContent)

                // Store in vector database
                val vectorId = vectorDbService.storeDocument(document, embedding)
                logger.info { "Stored memory chunk ${index + 1}/${chunks.size} in vector database with ID: $vectorId" }

                document
            }

            // Generate a summary of the content if it's long enough
            if (content.length > maxChunkSize * 2) {
                createSummary(project, title, content, type, importance, memoryId, createdBy)
            }

            return documents
        } catch (e: Exception) {
            logger.error(e) { "Failed to store chunked memory item in vector database: ${e.message}" }
            throw e
        }
    }

    /**
     * Split text into sentences.
     * This method uses a simple regex to split text into sentences.
     *
     * @param text The text to split
     * @return A list of sentences
     */
    private fun splitIntoSentences(text: String): List<String> {
        // Split by sentence-ending punctuation followed by whitespace or end of string
        return text.split(Regex("(?<=[.!?])\\s+|(?<=[.!?])$"))
            .filter { it.isNotBlank() }
            .map { it.trim() }
    }

    /**
     * Create chunks from sentences, ensuring each chunk is no larger than maxChunkSize.
     *
     * @param sentences The sentences to chunk
     * @return A list of chunks, where each chunk is a string containing one or more sentences
     */
    private fun createChunksFromSentences(sentences: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (sentence in sentences) {
            // If adding this sentence would exceed the max chunk size and the current chunk is not empty,
            // add the current chunk to the list and start a new one
            if (currentChunk.isNotEmpty() && currentChunk.length + sentence.length > maxChunkSize) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            }

            // If the sentence itself is longer than maxChunkSize, split it further
            if (sentence.length > maxChunkSize) {
                // If there's content in the current chunk, add it first
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.clear()
                }

                // Split the long sentence into smaller parts
                var start = 0
                while (start < sentence.length) {
                    val end = minOf(start + maxChunkSize, sentence.length)
                    // Try to find a word boundary to split at
                    var splitPoint = end
                    if (end < sentence.length) {
                        val lastSpace = sentence.substring(start, end).lastIndexOf(' ')
                        if (lastSpace > 0) {
                            splitPoint = start + lastSpace
                        }
                    }
                    chunks.add(sentence.substring(start, splitPoint).trim())
                    start = splitPoint + 1
                }
            } else {
                // Add the sentence to the current chunk
                currentChunk.append(sentence).append(" ")
            }
        }

        // Add the last chunk if not empty
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks
    }

    /**
     * Create a summary of the content using LLM.
     * This method creates a special memory item that contains a summary of the original content.
     *
     * @param project The project
     * @param title The title
     * @param content The content to summarize
     * @param type The type of memory item
     * @param importance The importance
     * @param memoryId The memory ID to link the summary to the original chunks
     * @param createdBy The user who created the memory item
     * @return The created summary document
     */
    private fun createSummary(
        project: Project,
        title: String,
        content: String,
        type: MemoryItemType,
        importance: Int,
        memoryId: String,
        createdBy: String?
    ): Document {
        logger.info { "Creating summary for memory item: $title" }

        // In a real implementation, we would use an LLM to generate a summary
        // For now, we'll just use the first few sentences as a simple summary
        val sentences = splitIntoSentences(content)
        val summary = sentences.take(3).joinToString(" ")

        // Create metadata for the summary
        val summaryMetadata = mapOf(
            "is_summary" to true,
            "original_memory_id" to memoryId
        )

        // Create the summary document
        return createMemoryItem(
            project = project,
            title = "$title (Summary)",
            content = summary,
            type = type,
            importance = importance,
            metadata = summaryMetadata,
            createdBy = createdBy
        )
    }

    /**
     * Process a meeting transcript and create a memory item.
     *
     * @param project The project to create the memory item for
     * @param title The title of the meeting
     * @param transcript The meeting transcript
     * @param importance The importance of the meeting (1-10)
     * @param metadata Additional metadata for the meeting
     * @return The created memory document
     */
    @Transactional
    fun processMeetingTranscript(
        project: Project,
        title: String,
        transcript: String,
        importance: Int = 5,
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): List<Document> {
        logger.info { "Processing meeting transcript: $title for project: ${project.name}" }

        // Use the chunked memory item creation to split the transcript into manageable pieces
        return createChunkedMemoryItem(
            project = project,
            title = title,
            content = transcript,
            type = MemoryItemType.MEETING_TRANSCRIPT,
            importance = importance,
            metadata = metadata,
            createdBy = createdBy
        )
    }

    /**
     * Create a note memory item.
     *
     * @param project The project to create the note for
     * @param title The title of the note
     * @param content The content of the note
     * @param importance The importance of the note (1-10)
     * @param metadata Additional metadata for the note
     * @return The created memory document
     */
    @Transactional
    fun createNote(
        project: Project,
        title: String,
        content: String,
        importance: Int = 5,
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): List<Document> {
        logger.info { "Creating note: $title for project: ${project.name}" }

        return createChunkedMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.NOTE,
            importance = importance,
            metadata = metadata,
            createdBy = createdBy
        )
    }

    /**
     * Record a project decision.
     *
     * @param project The project to record the decision for
     * @param title The title of the decision
     * @param content The content of the decision
     * @param importance The importance of the decision (1-10)
     * @param metadata Additional metadata for the decision
     * @return The created memory document
     */
    @Transactional
    fun recordDecision(
        project: Project,
        title: String,
        content: String,
        importance: Int = 7, // Decisions are typically more important
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): List<Document> {
        logger.info { "Recording decision: $title for project: ${project.name}" }

        return createChunkedMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.DECISION,
            importance = importance,
            metadata = metadata,
            createdBy = createdBy
        )
    }

    /**
     * Record a project plan.
     *
     * @param project The project to record the plan for
     * @param title The title of the plan
     * @param content The content of the plan
     * @param importance The importance of the plan (1-10)
     * @param metadata Additional metadata for the plan
     * @return The created memory document
     */
    @Transactional
    fun recordPlan(
        project: Project,
        title: String,
        content: String,
        importance: Int = 6, // Plans are typically important
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): List<Document> {
        logger.info { "Recording plan: $title for project: ${project.name}" }

        return createChunkedMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.PLAN,
            importance = importance,
            metadata = metadata,
            createdBy = createdBy
        )
    }

    /**
     * Record a historical event.
     *
     * @param project The project to record the event for
     * @param title The title of the event
     * @param content The content of the event
     * @param importance The importance of the event (1-10)
     * @param metadata Additional metadata for the event
     * @return The created memory document
     */
    @Transactional
    fun recordHistory(
        project: Project,
        title: String,
        content: String,
        importance: Int = 4, // Historical events are typically less important than current decisions
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): List<Document> {
        logger.info { "Recording history: $title for project: ${project.name}" }

        return createChunkedMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.HISTORY,
            importance = importance,
            metadata = metadata,
            createdBy = createdBy
        )
    }

    /**
     * Get memory items for the active project.
     *
     * @param type Optional type filter
     * @param limit Maximum number of items to return
     * @return A list of documents for the active project
     * @throws IllegalArgumentException if no active project is found
     */
    suspend fun getActiveProjectMemoryItems(type: MemoryItemType? = null, limit: Int = 10): List<Document> {
        val activeProject = projectService.getActiveProject()
            ?: throw IllegalArgumentException("No active project selected")

        return if (type != null) {
            val filter = MemoryDocument.createTypeFilter(activeProject, type)
            vectorDbService.searchSimilar(emptyList(), limit, filter)
        } else {
            val filter = MemoryDocument.createProjectFilter(activeProject)
            val documents = vectorDbService.searchSimilar(emptyList(), 100, filter)

            // Sort by timestamp (descending) and take the most recent ones
            documents
                .sortedByDescending { it.metadata["timestamp"] as? LocalDateTime ?: LocalDateTime.MIN }
                .take(limit)
        }
    }

    /**
     * Delete a memory item.
     * This method deletes the item from the vector database.
     *
     * @param document The document to delete
     */
    @Transactional
    suspend fun deleteMemoryItem(document: Document) {
        val title = MemoryDocument.getTitle(document)
        val projectId = document.metadata["project"] as? Int
        val projectName = projectId?.let { projectService.getProjectById(it.toLong())?.name } ?: "Unknown"

        logger.info { "Deleting memory item: $title for project: $projectName" }

        try {
            // Create filter to find the exact document
            val memoryId = MemoryDocument.getId(document)
            val filter = mapOf("memory_id" to memoryId)

            // Delete from vector database
            vectorDbService.deleteDocuments(filter)
            logger.info { "Deleted memory item from vector database with ID: $memoryId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete memory item from vector database: ${e.message}" }
            throw e
        }
    }

    /**
     * Delete a memory item by ID.
     * This method deletes the item from the vector database.
     *
     * @param memoryId The ID of the memory item to delete
     */
    @Transactional
    fun deleteMemoryItemById(memoryId: String) {
        logger.info { "Deleting memory item with ID: $memoryId" }

        try {
            // Create filter to find the exact document
            val filter = mapOf("memory_id" to memoryId)

            // Delete from vector database
            vectorDbService.deleteDocuments(filter)
            logger.info { "Deleted memory item from vector database with ID: $memoryId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete memory item from vector database: ${e.message}" }
            throw e
        }
    }
}
