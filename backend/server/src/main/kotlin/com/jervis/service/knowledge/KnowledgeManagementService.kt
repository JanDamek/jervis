package com.jervis.service.knowledge

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.KnowledgeSeverity
import com.jervis.domain.rag.KnowledgeType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.SearchResult
import com.jervis.repository.vector.VectorQuery
import com.jervis.repository.vector.WeaviateFilters
import com.jervis.repository.vector.WeaviateVectorRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.RagIndexingService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Knowledge Management Service for Jervis Knowledge Engine.
 *
 * Provides high-level operations for storing, searching, updating, and deleting knowledge fragments (RULES and MEMORIES).
 * Built on top of the existing RAG infrastructure with specialized knowledge handling.
 */
@Service
class KnowledgeManagementService(
    private val ragIndexingService: RagIndexingService,
    private val vectorRepository: WeaviateVectorRepository,
    private val embeddingGateway: EmbeddingGateway,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val MIN_KNOWLEDGE_TEXT_LENGTH = 10
        private const val MAX_KNOWLEDGE_TEXT_LENGTH = 10000
    }

    /**
     * Store a new knowledge fragment (RULE or MEMORY).
     * Automatically generates a unique knowledgeId (UUID).
     *
     * @return Result with stored KnowledgeFragment or error
     */
    suspend fun storeKnowledge(
        text: String,
        type: KnowledgeType,
        severity: KnowledgeSeverity?,
        tags: List<String>,
        clientId: ObjectId,
        projectId: ObjectId? = null,
        correlationId: String? = null,
    ): Result<KnowledgeFragment> =
        runCatching {
            // Validate
            require(text.length >= MIN_KNOWLEDGE_TEXT_LENGTH) {
                "Knowledge text too short (min $MIN_KNOWLEDGE_TEXT_LENGTH chars)"
            }
            require(text.length <= MAX_KNOWLEDGE_TEXT_LENGTH) {
                "Knowledge text too long (max $MAX_KNOWLEDGE_TEXT_LENGTH chars)"
            }
            require(type == KnowledgeType.MEMORY || severity != null) {
                "Severity is required for RULE type"
            }

            logger.info {
                "Storing knowledge: type=$type, severity=$severity, tags=$tags, " +
                    "clientId=$clientId, projectId=$projectId"
            }

            // Generate unique ID
            val knowledgeId = UUID.randomUUID().toString()

            // Determine source type
            val sourceType =
                when (type) {
                    KnowledgeType.RULE -> RagSourceType.RULE
                    KnowledgeType.MEMORY -> RagSourceType.MEMORY
                }

            // Create RagDocument
            val ragDocument =
                RagDocument(
                    text = text,
                    clientId = clientId,
                    projectId = projectId,
                    ragSourceType = sourceType,
                    knowledgeType = type,
                    knowledgeSeverity = severity,
                    knowledgeTags = tags,
                    knowledgeId = knowledgeId,
                    correlationId = correlationId,
                )

            // Index using RagIndexingService
            val indexed =
                ragIndexingService
                    .indexDocument(ragDocument, ModelTypeEnum.EMBEDDING_TEXT)
                    .getOrThrow()

            logger.info {
                "Knowledge stored successfully: knowledgeId=$knowledgeId, " +
                    "vectorStoreId=${indexed.vectorStoreId}"
            }

            KnowledgeFragment(
                knowledgeId = knowledgeId,
                text = text,
                type = type,
                severity = severity,
                tags = tags,
                clientId = clientId,
                projectId = projectId,
                vectorStoreId = indexed.vectorStoreId,
            )
        }

    /**
     * Search for knowledge fragments.
     * Uses vector search with optional type/tag/severity filtering.
     *
     * @return List of matching knowledge fragments sorted by relevance
     */
    suspend fun searchKnowledge(
        query: String,
        type: KnowledgeType? = null,
        tags: List<String>? = null,
        severity: KnowledgeSeverity? = null,
        clientId: ObjectId,
        projectId: ObjectId? = null,
        limit: Int = 20,
    ): Result<List<KnowledgeFragment>> =
        runCatching {
            logger.info {
                "Searching knowledge: query='$query', type=$type, tags=$tags, " +
                    "severity=$severity, clientId=$clientId, projectId=$projectId"
            }

            // Build filters
            val filters =
                WeaviateFilters(
                    clientId = clientId.toHexString(),
                    projectId = projectId?.toHexString(),
                    knowledgeType = type,
                    knowledgeSeverity = severity,
                    knowledgeTags = tags,
                )

            // Get embedding
            val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, query)

            // Build query
            val vectorQuery =
                VectorQuery(
                    embedding = embedding,
                    filters = filters,
                    limit = limit,
                    minScore = 0.0f, // No minimum score for knowledge search
                )

            // Search
            val results =
                vectorRepository.searchAll(
                    collectionType = ModelTypeEnum.EMBEDDING_TEXT,
                    query = vectorQuery,
                )

            logger.info { "Knowledge search found ${results.size} results" }

            // Convert to KnowledgeFragment
            results.mapNotNull { it.toKnowledgeFragment() }
        }

    /**
     * Delete a knowledge fragment by its unique knowledgeId.
     *
     * @return Result<Boolean> - true if deleted, false if not found
     */
    suspend fun deleteKnowledge(
        knowledgeId: String,
        clientId: ObjectId,
    ): Result<Boolean> =
        runCatching {
            logger.info { "Deleting knowledge: knowledgeId=$knowledgeId, clientId=$clientId" }

            val deleted =
                vectorRepository
                    .deleteByKnowledgeId(
                        collectionType = ModelTypeEnum.EMBEDDING_TEXT,
                        knowledgeId = knowledgeId,
                        clientId = clientId.toHexString(),
                    ).getOrThrow()

            logger.info {
                "Knowledge deletion ${if (deleted) "successful" else "not found"}: knowledgeId=$knowledgeId"
            }

            deleted
        }

    /**
     * Update a knowledge fragment.
     * Implemented as delete + create (simpler than updating embeddings).
     *
     * @return Result with new KnowledgeFragment or error
     */
    suspend fun updateKnowledge(
        knowledgeId: String,
        newText: String,
        newTags: List<String>? = null,
        newSeverity: KnowledgeSeverity? = null,
        clientId: ObjectId,
        projectId: ObjectId? = null,
        correlationId: String? = null,
    ): Result<KnowledgeFragment> =
        runCatching {
            logger.info {
                "Updating knowledge: knowledgeId=$knowledgeId, " +
                    "newTextLength=${newText.length}, newTags=$newTags"
            }

            // First, search for existing to get its type
            val existing =
                searchKnowledge(
                    query = "",
                    clientId = clientId,
                    projectId = projectId,
                    limit = 1,
                ).getOrThrow()
                    .firstOrNull { it.knowledgeId == knowledgeId }
                    ?: throw IllegalArgumentException("Knowledge not found: $knowledgeId")

            // Delete old
            val deleted = deleteKnowledge(knowledgeId, clientId).getOrThrow()
            require(deleted) { "Failed to delete old knowledge: $knowledgeId" }

            // Store new with same type
            storeKnowledge(
                text = newText,
                type = existing.type,
                severity = newSeverity ?: existing.severity,
                tags = newTags ?: existing.tags,
                clientId = clientId,
                projectId = projectId,
                correlationId = correlationId,
            ).getOrThrow()
        }

    /**
     * Get all rules for a given client/project.
     * Useful for loading all active governance rules into Planner.
     */
    suspend fun getAllRules(
        clientId: ObjectId,
        projectId: ObjectId? = null,
        severity: KnowledgeSeverity? = null,
    ): Result<List<KnowledgeFragment>> =
        searchKnowledge(
            query = "", // Empty query to get all
            type = KnowledgeType.RULE,
            severity = severity,
            clientId = clientId,
            projectId = projectId,
            limit = 100, // High limit for rules
        )

    /**
     * Get all memories for a given client/project.
     */
    suspend fun getAllMemories(
        clientId: ObjectId,
        projectId: ObjectId? = null,
        limit: Int = 50,
    ): Result<List<KnowledgeFragment>> =
        searchKnowledge(
            query = "", // Empty query to get all
            type = KnowledgeType.MEMORY,
            clientId = clientId,
            projectId = projectId,
            limit = limit,
        )
}

/**
 * Knowledge Fragment DTO for external API.
 */
data class KnowledgeFragment(
    val knowledgeId: String,
    val text: String,
    val type: KnowledgeType,
    val severity: KnowledgeSeverity?,
    val tags: List<String>,
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val vectorStoreId: String,
)

/**
 * Convert Weaviate SearchResult to KnowledgeFragment.
 * Returns null if not a knowledge fragment (missing knowledgeId).
 */
private fun SearchResult.toKnowledgeFragment(): KnowledgeFragment? {
    val knowledgeId = metadata["knowledgeId"] as? String ?: return null
    val typeStr = metadata["knowledgeType"] as? String ?: return null
    val severityStr = metadata["knowledgeSeverity"] as? String
    val tagsStr = metadata["knowledgeTags"] as? List<*>
    val clientIdStr = metadata["clientId"] as? String ?: return null
    val projectIdStr = metadata["projectId"] as? String

    return KnowledgeFragment(
        knowledgeId = knowledgeId,
        text = text,
        type = KnowledgeType.valueOf(typeStr),
        severity = severityStr?.let { KnowledgeSeverity.valueOf(it) },
        tags = tagsStr?.mapNotNull { it as? String } ?: emptyList(),
        clientId = ObjectId(clientIdStr),
        projectId = projectIdStr?.let { ObjectId(it) },
        vectorStoreId = id,
    )
}
