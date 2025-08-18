package com.jervis.service.vectordb

import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.domain.symbol.Symbol
import com.jervis.domain.symbol.SymbolKind
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.mcp.McpAction
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.JsonWithInt
import io.qdrant.client.grpc.Points
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.exp

/**
 * Unified VectorStorageService that consolidates previous VectorDbService and DualVectorDbService.
 * - Delegates RAG operations to VectorStorageRepository.
 * - Implements advanced multi-collection operations (originally in DualVectorDbService) directly.
 */
@Service
class VectorStorageService(
    private val vectorStorageRepository: VectorStorageRepository,
    @Value("\${qdrant.host:localhost}") private val qdrantHost: String,
    @Value("\${qdrant.port:6334}") private val qdrantPort: Int,
) {
    // ===== Logger and Qdrant setup (from DualVectorDbService) =====
    private val logger = KotlinLogging.logger {}
    private var qdrantClient: QdrantClient? = null

    companion object {
        const val SEMANTIC_TEXT_COLLECTION = "semantic_text"
        const val SEMANTIC_CODE_COLLECTION = "semantic_code"
        const val VECTOR_SIZE = 768
    }

    @PostConstruct
    fun initialize() {
        try {
            qdrantClient = QdrantClient(
                QdrantGrpcClient
                    .newBuilder(qdrantHost, qdrantPort, false, true)
                    .withTimeout(Duration.ofSeconds(15))
                    .build()
            )

            createCollectionIfNotExists(SEMANTIC_TEXT_COLLECTION)
            createCollectionIfNotExists(SEMANTIC_CODE_COLLECTION)

            // TODO: Create payload indexes on Qdrant when client API is available.
            // Required fields per spec: clientId, projectId, inspirationOnly, qualifiedName, language, timestamp, isDefaultBranch.
            // Current client lib doesn't expose payload index creation; ensure server-side config or manage via admin scripts.
            logger.info { "VectorStorageService (merged) initialized successfully; ensure Qdrant payload indexes exist for clientId, projectId, inspirationOnly, qualifiedName, language, timestamp, isDefaultBranch" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize VectorStorageService: ${e.message}" }
        }
    }

    private fun createCollectionIfNotExists(collectionName: String) {
        try {
            val client = qdrantClient ?: throw RuntimeException("Qdrant client not initialized")

            // Pre-check existing collections to avoid Qdrant client error logs
            val collections = client.listCollectionsAsync().get().toList()
            if (collections.contains(collectionName)) {
                logger.info { "Collection $collectionName already exists, skipping creation" }
                return
            }

            val vectorParams = when (collectionName) {
                SEMANTIC_TEXT_COLLECTION -> Collections.VectorParams
                    .newBuilder()
                    .setSize(VECTOR_SIZE.toLong())
                    .setDistance(Collections.Distance.Cosine)
                    .build()

                SEMANTIC_CODE_COLLECTION -> Collections.VectorParams
                    .newBuilder()
                    .setSize(VECTOR_SIZE.toLong())
                    .setDistance(Collections.Distance.Cosine)
                    .build()

                else -> throw IllegalArgumentException("Unknown collection: $collectionName")
            }

            client.createCollectionAsync(collectionName, vectorParams).get()
            logger.info { "Created Qdrant collection: $collectionName" }
        } catch (e: Exception) {
            // Log but avoid noisy ALREADY_EXISTS since we pre-checked
            if (e is StatusRuntimeException && e.status.code == Status.Code.ALREADY_EXISTS) {
                logger.info { "Collection $collectionName already exists, skipping creation" }
            } else if (e.cause is StatusRuntimeException && (e.cause as StatusRuntimeException).status.code == Status.Code.ALREADY_EXISTS) {
                logger.info { "Collection $collectionName already exists, skipping creation" }
            } else {
                logger.error(e) { "Failed to ensure collection $collectionName: ${e.message}" }
            }
        }
    }

    // ===== Repository-backed simple operations (from VectorDbService) =====
    fun storeDocument(ragDocument: RagDocument, embedding: List<Float>): String =
        vectorStorageRepository.storeDocument(ragDocument, embedding)

    suspend fun storeDocumentSuspend(ragDocument: RagDocument, embedding: List<Float>) =
        vectorStorageRepository.storeDocumentSuspend(ragDocument, embedding)

    fun storeMcpAction(
        action: McpAction,
        result: String,
        query: String,
        embedding: List<Float>,
        projectId: ObjectId,
    ): String = vectorStorageRepository.storeMcpAction(action, result, query, embedding, projectId)

    suspend fun searchSimilar(
        query: List<Float>,
        limit: Int = 5,
        filter: Map<String, Any>? = null,
    ): List<RagDocument> = vectorStorageRepository.searchSimilar(query, limit, filter)

    suspend fun verifyDataStorage(projectId: ObjectId): Boolean =
        vectorStorageRepository.verifyDataStorage(projectId)

    // ===== Advanced operations (from DualVectorDbService) =====
    suspend fun storeAdvancedDocument(
        document: AdvancedRagDocument,
        embedding: FloatArray
    ): String {
        val client = qdrantClient ?: throw RuntimeException("Qdrant client not initialized")
        val collectionName = determineCollection(document)

        val pointId = UUID.randomUUID().toString()

        // Convert embedding to Qdrant vector
        val vectorsBuilder = Points.Vectors.newBuilder()
        vectorsBuilder.vector = Points.Vector
            .newBuilder()
            .addAllData(embedding.toList())
            .build()
        val vectors = vectorsBuilder.build()

        // Create point struct
        val point = Points.PointStruct
            .newBuilder()
            .setId(
                Points.PointId
                    .newBuilder()
                    .setUuid(pointId)
                    .build()
            )
            .setVectors(vectors)
            .putAllPayload(buildPayload(document))
            .build()

        // Upsert the point
        client.upsertAsync(collectionName, listOf(point)).get()

        logger.debug { "Stored document in $collectionName: ${document.summary.take(50)}..." }
        return pointId
    }

    suspend fun searchByQualifiedName(
        qualifiedName: String,
        projectId: ObjectId,
        limit: Int = 5
    ): List<ScoredDocument> {
        // Minimal stub to keep compilation; proper implementation can be added when needed.
        logger.warn { "searchByQualifiedName is not fully implemented for current Qdrant client version; returning empty list" }
        return emptyList()
    }

    suspend fun searchMultiCollection(
        query: String,
        queryEmbeddings: Map<String, FloatArray>,
        filters: Map<String, Any> = emptyMap(),
        limit: Int = 10
    ): List<ScoredDocument> = coroutineScope {

        val isSymbolicQuery = query.contains(Regex("[A-Z][a-zA-Z]*\\.[a-zA-Z]+"))

        // Fan-out strategy
        val searchTasks = if (isSymbolicQuery) {
            listOf(
                async {
                    queryEmbeddings[SEMANTIC_CODE_COLLECTION]?.let {
                        searchInCollection(SEMANTIC_CODE_COLLECTION, it, filters, limit)
                    } ?: emptyList()
                },
                async {
                    queryEmbeddings[SEMANTIC_TEXT_COLLECTION]?.let {
                        searchInCollection(SEMANTIC_TEXT_COLLECTION, it, filters, limit / 3)
                    } ?: emptyList()
                }
            )
        } else {
            listOf(
                async {
                    queryEmbeddings[SEMANTIC_TEXT_COLLECTION]?.let {
                        searchInCollection(SEMANTIC_TEXT_COLLECTION, it, filters, limit / 2)
                    } ?: emptyList()
                },
                async {
                    queryEmbeddings[SEMANTIC_CODE_COLLECTION]?.let {
                        searchInCollection(SEMANTIC_CODE_COLLECTION, it, filters, limit / 2)
                    } ?: emptyList()
                }
            )
        }

        val allResults = searchTasks.awaitAll()

        // Group results by collection
        val groupedResults = mapOf(
            SEMANTIC_TEXT_COLLECTION to allResults.flatten().filter { it.collection == SEMANTIC_TEXT_COLLECTION },
            SEMANTIC_CODE_COLLECTION to allResults.flatten().filter { it.collection == SEMANTIC_CODE_COLLECTION }
        )

        val mergedResults = rrfMergePerCollection(groupedResults)
        val boostedResults = applyBoostAndDecay(mergedResults, filters)

        return@coroutineScope deduplicateResults(boostedResults).take(limit)
    }

    private suspend fun searchInCollection(
        collectionName: String,
        queryEmbedding: FloatArray,
        filters: Map<String, Any>,
        limit: Int
    ): List<ScoredDocument> {
        val client = qdrantClient ?: throw RuntimeException("Qdrant client not initialized")

        val qdrantFilter = buildFilter(filters)

        val searchBuilder = Points.SearchPoints.newBuilder()
            .setCollectionName(collectionName)
            .setLimit(limit.toLong())

        // Add vector data
        for (i in queryEmbedding.indices) {
            searchBuilder.addVector(queryEmbedding[i])
        }

        // Include payload
        val withPayloadSelector = Points.WithPayloadSelector.newBuilder().setEnable(true).build()
        searchBuilder.withPayload = withPayloadSelector

        // Apply filter if present
        if (qdrantFilter != null) {
            searchBuilder.filter = qdrantFilter
        }

        val searchResponse = client.searchAsync(searchBuilder.build()).get().toList()

        return searchResponse.map { scoredPoint ->
            val payload = scoredPoint.payloadMap.toMap()
            val converted = payload.mapValues { (_, v) -> convertFromQdrantValue(v) }
            ScoredDocument(
                id = scoredPoint.id.uuid,
                score = scoredPoint.score,
                payload = converted,
                collection = collectionName
            )
        }
    }

    private fun buildFilter(filters: Map<String, Any>): Points.Filter? {
        if (filters.isEmpty()) return null

        val filterBuilder = Points.Filter.newBuilder()

        filters.forEach { (key, value) ->
            val fieldCondition = when (value) {
                is String -> Points.FieldCondition
                    .newBuilder()
                    .setKey(key)
                    .setMatch(Points.Match.newBuilder().setKeyword(value))
                    .build()
                is Boolean -> Points.FieldCondition
                    .newBuilder()
                    .setKey(key)
                    .setMatch(Points.Match.newBuilder().setKeyword(value.toString()))
                    .build()
                is Int -> Points.FieldCondition
                    .newBuilder()
                    .setKey(key)
                    .setRange(
                        Points.Range
                            .newBuilder()
                            .setGt(0.0)
                            .setLt(value.toDouble() + 0.1)
                            .build()
                    )
                    .build()
                else -> Points.FieldCondition
                    .newBuilder()
                    .setKey(key)
                    .setMatch(Points.Match.newBuilder().setKeyword(value.toString()))
                    .build()
            }

            filterBuilder.addMust(
                Points.Condition
                    .newBuilder()
                    .setField(fieldCondition)
                    .build()
            )
        }

        return filterBuilder.build()
    }

    private fun rrfMergePerCollection(
        groups: Map<String, List<ScoredDocument>>,
        k: Int = 60
    ): List<ScoredDocument> {
        val accumulator = mutableMapOf<String, Pair<ScoredDocument, Double>>()

        for ((_, docs) in groups) {
            docs.sortedByDescending { it.score }
                .forEachIndexed { rank, doc ->
                    val rrfScore = 1.0 / (k + rank + 1)
                    val (existingDoc, existingScore) = accumulator[doc.id] ?: (doc to 0.0)
                    accumulator[doc.id] = existingDoc to (existingScore + rrfScore)
                }
        }

        return accumulator.values
            .map { (doc, score) -> doc.copy(score = score.toFloat()) }
            .sortedByDescending { it.score }
    }

    private fun applyBoostAndDecay(
        results: List<ScoredDocument>,
        filters: Map<String, Any>
    ): List<ScoredDocument> {
        val currentTime = System.currentTimeMillis()

        return results.map { doc ->
            var boostedScore = doc.score

            // Default branch boost
            val isDefaultBranch = doc.payload["isDefaultBranch"] as? Boolean ?: false
            if (isDefaultBranch) {
                boostedScore *= 1.2f
            }

            // Recency decay
            val timestamp = doc.payload["timestamp"] as? Long ?: currentTime
            val ageInDays = (currentTime - timestamp) / (1000 * 60 * 60 * 24)
            val recencyFactor = exp(-ageInDays / 365.0).toFloat()
            boostedScore *= (0.8f + 0.2f * recencyFactor)

            doc.copy(score = boostedScore)
        }
    }

    private fun deduplicateResults(results: List<ScoredDocument>): List<ScoredDocument> {
        return results.distinctBy { it.id }
    }

    private fun convertFromQdrantValue(value: JsonWithInt.Value): Any {
        return when {
            value.hasStringValue() -> value.stringValue
            value.hasIntegerValue() -> value.integerValue
            value.hasDoubleValue() -> value.doubleValue
            value.hasBoolValue() -> value.boolValue
            else -> value.toString()
        }
    }

    // ===== Helpers specific to advanced document handling =====
    private fun determineCollection(document: AdvancedRagDocument): String {
        return when {
            document.documentType.name.startsWith("CODE") -> SEMANTIC_CODE_COLLECTION
            document.documentType in listOf(RagDocumentType.CLASS_SUMMARY, RagDocumentType.DEPENDENCY) -> SEMANTIC_CODE_COLLECTION
            document.symbol.kind in listOf(SymbolKind.CLASS, SymbolKind.METHOD, SymbolKind.INTERFACE) -> SEMANTIC_CODE_COLLECTION
            else -> SEMANTIC_TEXT_COLLECTION
        }
    }

    private fun buildPayload(document: AdvancedRagDocument): Map<String, JsonWithInt.Value> {
        return mapOf(
            // ACL and base metadata
            "clientId" to createValue(document.clientId?.toString() ?: ""),
            "projectId" to createValue(document.projectId.toString()),
            "documentType" to createValue(document.documentType.name),
            "ragSourceType" to createValue(document.ragSourceType.name),
            "source" to createValue(document.ragSourceType.name),
            "isDefaultBranch" to createValue(document.isDefaultBranch),
            "inspirationOnly" to createValue(document.inspirationOnly),
            "timestamp" to createValue(document.timestamp.toEpochMilli()),

            // Symbol metadata
            "symbolKind" to createValue(document.symbol.kind.name),
            "symbolName" to createValue(document.symbol.name),
            "qualifiedName" to createValue(document.symbol.qualifiedName),
            "canonicalSymbolId" to createValue("${document.projectId}:${document.symbol.qualifiedName}"),
            "language" to createValue(document.language),
            "module" to createValue(document.module),
            "path" to createValue(document.path),

            // Relations
            "parentSymbol" to createValue(document.symbol.parent ?: ""),
            "extends" to createValue(document.relations.extends.joinToString(",")),
            "implements" to createValue(document.relations.implements.joinToString(",")),
            "calls" to createValue(document.relations.calls.joinToString(",")),

            // Content
            "summary" to createValue(document.summary),
            "codeExcerpt" to createValue(document.codeExcerpt),
            "doc" to createValue(document.doc ?: "")
        )
    }

    private fun createValue(value: Any): JsonWithInt.Value {
        return when (value) {
            is String -> JsonWithInt.Value.newBuilder().setStringValue(value).build()
            is Int -> JsonWithInt.Value.newBuilder().setIntegerValue(value.toLong()).build()
            is Long -> JsonWithInt.Value.newBuilder().setIntegerValue(value).build()
            is Boolean -> JsonWithInt.Value.newBuilder().setBoolValue(value).build()
            else -> JsonWithInt.Value.newBuilder().setStringValue(value.toString()).build()
        }
    }
}

// Data classes for advanced vector service

data class ScoredDocument(
    val id: String,
    val score: Float,
    val payload: Map<String, Any>,
    val collection: String
)


data class AdvancedRagDocument(
    val projectId: ObjectId,
    val clientId: ObjectId? = null,
    val documentType: RagDocumentType,
    val ragSourceType: RagSourceType,
    val timestamp: Instant,
    val symbol: Symbol,
    val language: String,
    val module: String,
    val path: String,
    val relations: SymbolRelations,
    val summary: String,
    val codeExcerpt: String,
    val doc: String?,
    val inspirationOnly: Boolean = false,
    val isDefaultBranch: Boolean = true,
)


data class SymbolRelations(
    val extends: List<String> = emptyList(),
    val implements: List<String> = emptyList(),
    val calls: List<String> = emptyList()
)
