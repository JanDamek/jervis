package com.jervis.service

// This file has been modified to use Qdrant instead of relational database
import com.jervis.module.memory.MemoryItemType
import com.jervis.module.memory.MemoryDocument
import com.jervis.entity.Project
import com.jervis.module.indexer.EmbeddingService
import com.jervis.module.vectordb.VectorDbService
import com.jervis.rag.Document
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Service for managing memory items.
 * This service provides methods for creating, retrieving, updating, and deleting memory items,
 * as well as methods for processing meeting transcripts and indexing notes and history.
 * 
 * This service uses only the vector database (Qdrant) for storage and retrieval of memory items.
 */
@Service("serviceMemoryService")
class MemoryService(
    private val projectService: ProjectService,
    private val objectMapper: ObjectMapper,
    private val vectorDbService: VectorDbService,
    private val embeddingService: EmbeddingService
) {
    private val logger = KotlinLogging.logger {}

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
     * Search for memory items by content.
     *
     * @param project The project to search in
     * @param searchTerm The term to search for
     * @return A list of documents matching the search term
     */
    fun searchMemoryItems(project: Project, searchTerm: String): List<Document> {
        try {
            // Generate embedding for the query
            val queryEmbedding = embeddingService.generateTextEmbedding(searchTerm)

            // Create filter for the project and memory source
            val filter = MemoryDocument.createProjectFilter(project)

            // Search for similar documents in the vector database
            return vectorDbService.searchSimilar(queryEmbedding, 10, filter)
        } catch (e: Exception) {
            logger.error(e) { "Semantic search failed: ${e.message}" }
            return emptyList()
        }
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
        metadata: Map<String, Any>? = null
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
                metadata = metadata
            )

            // Generate embedding for the content
            val embedding = embeddingService.generateTextEmbedding(content)

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
        metadata: Map<String, Any>? = null
    ): Document {
        logger.info { "Processing meeting transcript: $title for project: ${project.name}" }

        // In a real implementation, we might do additional processing here,
        // such as extracting key points, decisions, or action items

        return createMemoryItem(
            project = project,
            title = title,
            content = transcript,
            type = MemoryItemType.MEETING_TRANSCRIPT,
            importance = importance,
            metadata = metadata
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
        metadata: Map<String, Any>? = null
    ): Document {
        logger.info { "Creating note: $title for project: ${project.name}" }

        return createMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.NOTE,
            importance = importance,
            metadata = metadata
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
        metadata: Map<String, Any>? = null
    ): Document {
        logger.info { "Recording decision: $title for project: ${project.name}" }

        return createMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.DECISION,
            importance = importance,
            metadata = metadata
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
        metadata: Map<String, Any>? = null
    ): Document {
        logger.info { "Recording plan: $title for project: ${project.name}" }

        return createMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.PLAN,
            importance = importance,
            metadata = metadata
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
        metadata: Map<String, Any>? = null
    ): Document {
        logger.info { "Recording history: $title for project: ${project.name}" }

        return createMemoryItem(
            project = project,
            title = title,
            content = content,
            type = MemoryItemType.HISTORY,
            importance = importance,
            metadata = metadata
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
    fun getActiveProjectMemoryItems(type: MemoryItemType? = null, limit: Int = 10): List<Document> {
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
    fun deleteMemoryItem(document: Document) {
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
