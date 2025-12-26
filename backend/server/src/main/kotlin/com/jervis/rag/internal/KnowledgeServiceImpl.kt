package com.jervis.rag.internal

import com.jervis.rag.HybridSearchRequest
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.rag.SearchResult
import com.jervis.rag.StoreChunkRequest
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.rag.internal.graphdb.model.GraphEdge
import com.jervis.rag.internal.graphdb.model.GraphNode
import com.jervis.rag.internal.model.RagMetadata
import com.jervis.rag.internal.repository.VectorDocument
import com.jervis.rag.internal.repository.VectorFilters
import com.jervis.rag.internal.repository.VectorQuery
import com.jervis.rag.internal.repository.WeaviateVectorStore
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.types.ClientId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of KnowledgeService.
 * Atomic storage: Agent chunks, service embeds.
 * Hybrid search: BM25 + Vector.
 */
@Service
internal class KnowledgeServiceImpl(
    private val weaviateVectorStore: WeaviateVectorStore,
    private val embeddingGateway: EmbeddingGateway,
    private val ragMetadataUpdater: RagMetadataUpdater,
    private val weaviateProvisioner: WeaviatePerClientProvisioner,
    private val graphEntityRegistryRepository: GraphEntityRegistryRepository,
    private val graphDBService: GraphDBService,
) : KnowledgeService {
    private val logger = KotlinLogging.logger {}

    // Normalization cache (per-client) to keep graph refs stable across chunks.
    private val graphRefCache = ConcurrentHashMap<String, String>()
    private val graphRefLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun storeChunk(request: StoreChunkRequest): String {
        logger.info {
            "ATOMIC_STORE_CHUNK: clientId=${request.clientId}, " +
                "size=${request.content.length} "
        }

        if (request.content.isBlank()) {
            throw IllegalArgumentException("Content cannot be blank")
        }

        val graphPayload =
            buildGraphPayload(
                clientId = request.clientId,
                mainNodeKey = request.mainNodeKey,
                relationships = request.relationships,
            )

        val normalizedGraph = normalizeGraphRefs(request.clientId, graphPayload.allNodes)

        val chunkId = UUID.randomUUID().toString()

        // 1. Generate embedding
        val embedding = embeddingGateway.callEmbedding(request.content)

        // 2. Build metadata
        val metadata =
            mutableMapOf<String, Any>(
                "knowledgeId" to chunkId,
                "content" to request.content,
                "sourceUrn" to request.sourceUrn.value,
                "clientId" to request.clientId.toString(),
                "mainNodeKey" to request.mainNodeKey,
            )

        request.projectId?.let { metadata["projectId"] = it.toString() }

        if (normalizedGraph.refs.isNotEmpty()) {
            // Keep the original list shape for backwards compatibility, but ensure it is normalized.
            metadata["graphRefs"] = normalizedGraph.refs

            // Extra, query-friendly hints (helps later retrieval + model reasoning)
            metadata["graphAreas"] = normalizedGraph.areas
            normalizedGraph.rootRef?.let { metadata["graphRootRef"] = it }
            normalizedGraph.primaryArea?.let { metadata["graphPrimaryArea"] = it }
        }

        if (graphPayload.canonicalRelationships.isNotEmpty()) {
            metadata["graphRelationships"] = graphPayload.canonicalRelationships
        }

        // 3. Store in Weaviate (with BM25 indexing on the 'content' field)
        val vectorDoc =
            VectorDocument(
                id = chunkId,
                content = request.content,
                embedding = embedding,
                metadata = metadata,
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId)
        weaviateVectorStore.store(vectorDoc, classNameOverride).getOrThrow()

        // 4. Persist graph (best-effort): nodes + edges
        runCatching {
            persistGraph(
                clientId = request.clientId,
                ragChunkId = chunkId,
                nodes = normalizedGraph.refs,
                relationships = graphPayload.canonicalTriples,
            )
        }.onFailure { e ->
            logger.warn(e) { "Graph persistence failed for chunk $chunkId (non-fatal)" }
        }

        // 5. Cross-link RAG -> Graph (best-effort, legacy hook)
        runCatching {
            val meta =
                RagMetadata(
                    clientId = request.clientId,
                    projectId = request.projectId,
                    sourceUrn = request.sourceUrn,
                    graphRefs = normalizedGraph.refs,
                )
            ragMetadataUpdater.onChunkStored(
                clientId = request.clientId,
                ragChunkId = chunkId,
                meta = meta,
            )
        }.onFailure { e ->
            logger.warn(e) { "Graph cross-link failed for chunk $chunkId (non-fatal)" }
        }

        logger.info { "CHUNK_STORED: chunkId=$chunkId, sourceUrn=${request.sourceUrn}" }
        return chunkId
    }

    override suspend fun searchHybrid(request: HybridSearchRequest): SearchResult {
        logger.info {
            "HYBRID_SEARCH: query='${request.query}', " +
                "alpha=${request.alpha}, " +
                "clientId=${request.clientId}, " +
                "maxResults=${request.maxResults}"
        }

        // For now, delegate to existing vector search
        // TODO: Implement true hybrid search with BM25 + Vector when Weaviate client supports it
        // Weaviate v4 Java client may need GraphQL query builder for hybrid argument

        val embedding = embeddingGateway.callEmbedding(request.query)

        val query =
            VectorQuery(
                embedding = embedding,
                limit = request.maxResults,
                minScore = 0.0f, // Hybrid search uses alpha, not minScore
                filters =
                    VectorFilters(
                        clientId = request.clientId,
                        projectId = request.projectId,
                    ),
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId)
        val results =
            weaviateVectorStore
                .search(query, classNameOverride)
                .getOrThrow()

        logger.info { "HYBRID_SEARCH_COMPLETE: found=${results.size} results" }

        val fragments =
            results.map { result ->
                InternalFragment(
                    sourceUrn = result.metadata["sourceUrn"]?.toString() ?: "",
                    content = result.content,
                    graphPrimaryArea = result.metadata["graphPrimaryArea"]?.toString(),
                    graphAreas =
                        (result.metadata["graphAreas"] as? List<*>)?.mapNotNull { it?.toString() }
                            ?: emptyList(),
                    graphRefs =
                        (result.metadata["graphRefs"] as? List<*>)?.mapNotNull { it?.toString() }
                            ?: emptyList(),
                )
            }

        return SearchResult(formatFragmentsForLlm(fragments))
    }

    override suspend fun search(request: SearchRequest): SearchResult {
        logger.info {
            "Knowledge search: query='${request.query}', " +
                "clientId=${request.clientId}, " +
                "projectId=${request.projectId}"
        }

        // Generate embedding
        val embedding = embeddingGateway.callEmbedding(request.query)

        // Build query
        val query =
            VectorQuery(
                embedding = embedding,
                limit = request.maxResults,
                minScore = request.minScore.toFloat(),
                filters =
                    VectorFilters(
                        clientId = request.clientId,
                        projectId = request.projectId,
                    ),
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId)
        val results =
            weaviateVectorStore
                .search(query, classNameOverride)
                .getOrThrow()

        logger.info { "Search completed: found=${results.size} results" }

        // Map to internal fragments and format as text
        val fragments =
            results.map { result ->
                InternalFragment(
                    sourceUrn = result.metadata["sourceUrn"]?.toString() ?: "",
                    content = result.content,
                    graphPrimaryArea = result.metadata["graphPrimaryArea"]?.toString(),
                    graphAreas =
                        (result.metadata["graphAreas"] as? List<*>)?.mapNotNull { it?.toString() }
                            ?: emptyList(),
                    graphRefs =
                        (result.metadata["graphRefs"] as? List<*>)?.mapNotNull { it?.toString() }
                            ?: emptyList(),
                )
            }

        return SearchResult(formatFragmentsForLlm(fragments))
    }

    /**
     * Format fragments as text for LLM.
     * IMPORTANT: sourceUrn must be visible, so the agent can track sources.
     */
    private fun formatFragmentsForLlm(fragments: List<InternalFragment>): String =
        buildString {
            fragments.forEachIndexed { index, fragment ->
                // Header with sourceUrn
                append("[")
                append(fragment.sourceUrn)
                append("]")
                appendLine()

                // Optional graph hints (keep it compact)
                if (fragment.graphPrimaryArea != null || fragment.graphAreas.isNotEmpty() || fragment.graphRefs.isNotEmpty()) {
                    val areas =
                        buildList {
                            fragment.graphPrimaryArea?.let { add(it) }
                            addAll(fragment.graphAreas)
                        }.distinct().take(6)

                    if (areas.isNotEmpty()) {
                        append("GraphArea: ")
                        appendLine(areas.joinToString(", "))
                    }

                    val refsPreview = fragment.graphRefs.take(6)
                    if (refsPreview.isNotEmpty()) {
                        append("GraphRefs: ")
                        appendLine(refsPreview.joinToString(", "))
                    }

                    appendLine("---")
                }

                // Content
                appendLine(fragment.content)

                // Separator between fragments
                if (index < fragments.size - 1) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
        }

    private suspend fun perClientClassName(clientId: ClientId): String = WeaviateClassNameUtil.classFor(clientId)

    /**
     * Internal fragment representation - only for formatting.
     */
    private data class InternalFragment(
        val sourceUrn: String,
        val content: String,
        val graphPrimaryArea: String? = null,
        val graphAreas: List<String> = emptyList(),
        val graphRefs: List<String> = emptyList(),
    )

    private data class NormalizedGraphRefs(
        val refs: List<String>,
        val areas: List<String>,
        val rootRef: String?,
        val primaryArea: String?,
    )

    private data class GraphPayload(
        val allNodes: List<String>,
        val canonicalTriples: List<Triple<String, String, String>>,
        val canonicalRelationships: List<String>,
    )

    private suspend fun buildGraphPayload(
        clientId: ClientId,
        mainNodeKey: String,
        relationships: List<String>,
    ): GraphPayload {
        val mainRaw = mainNodeKey.trim()
        val mainId = extractTrailingId(mainRaw)

        val parsedTriples =
            relationships
                .mapNotNull { parseRelationshipPipe(it) }
                .map { (from, edge, to) ->
                    Triple(
                        expandShortNodeKey(from, mainRaw, mainId),
                        edge,
                        expandShortNodeKey(to, mainRaw, mainId),
                    )
                }

        val canonicalMain = resolveCanonicalGraphRef(clientId, normalizeSingleGraphRef(mainRaw))

        val canonicalTriples =
            parsedTriples.map { (from, edge, to) ->
                val cFrom = resolveCanonicalGraphRef(clientId, normalizeSingleGraphRef(from))
                val cTo = resolveCanonicalGraphRef(clientId, normalizeSingleGraphRef(to))
                Triple(cFrom, normalizeEdgeType(edge), cTo)
            }

        val allNodes =
            buildSet {
                add(canonicalMain)
                canonicalTriples.forEach { (from, _, to) ->
                    add(from)
                    add(to)
                }
            }.toList()

        val canonicalRelationships =
            canonicalTriples
                .map { (from, edge, to) -> "$from|$edge|$to" }
                .distinct()

        return GraphPayload(
            allNodes = allNodes,
            canonicalTriples = canonicalTriples,
            canonicalRelationships = canonicalRelationships,
        )
    }

    private fun extractTrailingId(nodeKey: String): String? {
        val base = nodeKey.substringBefore("::").trim()
        if (base.isBlank()) return null
        if (!base.contains(":")) return null
        val id = base.substringAfterLast(":").trim()
        return id.ifBlank { null }
    }

    private fun expandShortNodeKey(
        raw: String,
        mainNodeKey: String,
        mainId: String?,
    ): String {
        val s = raw.trim()
        if (s.isBlank()) return s

        if (s.contains(":") || s.contains("::")) return s

        if (mainId != null && s == mainId) return mainNodeKey
        if (mainNodeKey.endsWith(":$s")) return mainNodeKey

        return s
    }

    private fun parseRelationshipPipe(rel: String): Triple<String, String, String>? {
        val s = rel.trim()
        if (s.isBlank()) return null

        // Primary format: from|edge|to
        val parts = s.split('|', limit = 3).map { it.trim() }
        if (parts.size == 3 && parts.all { it.isNotBlank() }) {
            return Triple(parts[0], parts[1], parts[2])
        }

        // Compatibility: from->edge->to
        if (s.contains("->")) {
            val p = s.split("->", limit = 3).map { it.trim() }
            if (p.size == 3 && p.all { it.isNotBlank() }) {
                return Triple(p[0], p[1], p[2])
            }
        }

        // Compatibility: from -[edge]-> to
        val regex = """^(.+?)\s*-\[(.+?)]-?>\s*(.+)$""".toRegex()
        val match = regex.matchEntire(s) ?: return null
        val (from, edge, to) = match.destructured
        val f = from.trim()
        val e = edge.trim()
        val t = to.trim()
        if (f.isBlank() || e.isBlank() || t.isBlank()) return null
        return Triple(f, e, t)
    }

    private fun normalizeEdgeType(edge: String): String =
        edge
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), "_")

    private fun entityTypeFromNodeKey(nodeKey: String): String {
        val base = nodeKey.substringBefore("::").trim()
        return base
            .substringBefore(":", missingDelimiterValue = base)
            .trim()
            .lowercase()
            .ifBlank { "entity" }
    }

    private suspend fun persistGraph(
        clientId: ClientId,
        ragChunkId: String,
        nodes: List<String>,
        relationships: List<Triple<String, String, String>>,
    ) {
        // Upsert nodes (attach chunk id). GraphDBService should merge ragChunks on upsert.
        for (key in nodes) {
            graphDBService.upsertNode(
                clientId = clientId,
                node =
                    GraphNode(
                        key = key,
                        entityType = entityTypeFromNodeKey(key),
                        ragChunks = listOf(ragChunkId),
                    ),
            )
        }

        // Upsert edges
        for ((from, edgeType, to) in relationships) {
            graphDBService.upsertEdge(
                clientId = clientId,
                edge =
                    GraphEdge(
                        edgeType = edgeType,
                        fromKey = from,
                        toKey = to,
                    ),
            )
        }
    }

    /**
     * Normalizes graphRefs coming from the agent.
     * Goal: keep refs stable (case/whitespace), and also derive query-friendly "areas".
     * Canonicalizes and aliases via MongoDB per-client registry.
     *
     * Examples:
     * - "email_email:6942..." -> area "email"
     * - "order:order_530798957" -> area "order"
     * - "email_email:...::v::abcd" -> area "email" (derived from the base part before ::)
     */
    private suspend fun normalizeGraphRefs(
        clientId: ClientId,
        input: List<String>,
    ): NormalizedGraphRefs {
        // 1) Basic formatting normalization (stable, no semantic guessing)
        val normalizedInput =
            input
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { normalizeSingleGraphRef(it) }
                .distinct()
                .toList()

        // 2) Canonicalization + aliasing via MongoDB (per-client)
        val canonicalRefs = mutableListOf<String>()
        for (ref in normalizedInput) {
            canonicalRefs += resolveCanonicalGraphRef(clientId, ref)
        }

        val refs = canonicalRefs.distinct().sorted()

        val areas =
            refs
                .asSequence()
                .mapNotNull { deriveGraphArea(it) }
                .distinct()
                .sorted()
                .toList()

        val rootRef =
            refs.firstOrNull { ref ->
                ref.startsWith("email:") || ref.startsWith("jira:") || ref.startsWith("confluence:") ||
                    ref.startsWith("doc:") || ref.startsWith("log:") ||
                    ref.startsWith("email_") || ref.startsWith("jira_") || ref.startsWith("confluence_") ||
                    ref.startsWith("doc_") || ref.startsWith("log_")
            } ?: refs.firstOrNull()

        val primaryArea = rootRef?.let { deriveGraphArea(it) } ?: areas.firstOrNull()

        return NormalizedGraphRefs(
            refs = refs,
            areas = areas,
            rootRef = rootRef,
            primaryArea = primaryArea,
        )
    }

    /**
     * Resolve canonical ref for a given alias ref.
     *
     * - Uses per-client Mongo registry (aliasKey -> canonicalKey)
     * - Adds new aliases automatically (best-effort, idempotent)
     * - Applies a conservative heuristic canonicalization (e.g., order:order_123 -> order:123)
     */
    private suspend fun resolveCanonicalGraphRef(
        clientId: ClientId,
        aliasRef: String,
    ): String {
        val clientKey = clientId.toString()
        val cacheKey = "$clientKey|$aliasRef"

        graphRefCache[cacheKey]?.let { return it }

        val mutex = graphRefLocks.computeIfAbsent(cacheKey) { Mutex() }
        return mutex.withLock {
            graphRefCache[cacheKey]?.let { return@withLock it }

            val now = Instant.now()

            // 1) Exact alias mapping
            val existing = graphEntityRegistryRepository.findFirstByClientIdAndAliasKey(clientKey, aliasRef)
            if (existing != null) {
                // Best-effort freshness update (only when we missed the cache)
                runCatching {
                    graphEntityRegistryRepository.save(
                        existing.copy(
                            area = existing.area ?: deriveGraphArea(existing.canonicalKey),
                            lastSeenAt = now,
                            seenCount = (existing.seenCount + 1),
                            updatedAt = now,
                        ),
                    )
                }.onFailure { e ->
                    if (e !is DuplicateKeyException) {
                        logger.debug(e) { "Failed to update graph alias stats (non-fatal): $aliasRef" }
                    }
                }

                graphRefCache[cacheKey] = existing.canonicalKey
                return@withLock existing.canonicalKey
            }

            // 2) Conservative canonical form (doesn't lose information, only removes redundant ns prefix)
            val canonicalCandidate = canonicalizeGraphRef(aliasRef)

            // 3) If canonical already exists (as a canonicalKey anywhere), re-use it and add alias
            val canonicalExisting =
                if (canonicalCandidate != aliasRef) {
                    graphEntityRegistryRepository.findFirstByClientIdAndCanonicalKey(clientKey, canonicalCandidate)
                } else {
                    null
                }

            val canonicalToUse = canonicalExisting?.canonicalKey ?: canonicalCandidate

            // Ensure the canonical key is also present as an alias to itself (helps future lookups + suggestions)
            ensureCanonicalSelfMapping(clientId, canonicalToUse, now)

            // If aliasRef is already canonical, DO NOT insert a second identical document.
            // Self-mapping above is sufficient.
            if (aliasRef == canonicalToUse) {
                graphRefCache[cacheKey] = canonicalToUse
                return@withLock canonicalToUse
            }

            // 4) Upsert the alias mapping (best-effort, tolerates races)
            runCatching {
                graphEntityRegistryRepository.save(
                    GraphEntityRegistryDocument(
                        clientId = clientKey,
                        aliasKey = aliasRef,
                        canonicalKey = canonicalToUse,
                        area = deriveGraphArea(canonicalToUse) ?: deriveGraphArea(aliasRef),
                        firstSeenAt = now,
                        lastSeenAt = now,
                        seenCount = 1,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }.onFailure { e ->
                // If we raced with another writer, ignore duplicate; otherwise log as debug.
                if (e !is DuplicateKeyException) {
                    logger.debug(e) { "Failed to persist graph alias mapping (non-fatal): $aliasRef -> $canonicalToUse" }
                }
            }

            graphRefCache[cacheKey] = canonicalToUse
            canonicalToUse
        }
    }

    private suspend fun ensureCanonicalSelfMapping(
        clientId: ClientId,
        canonicalRef: String,
        now: Instant,
    ) {
        val clientKey = clientId.toString()
        val existing = graphEntityRegistryRepository.findFirstByClientIdAndAliasKey(clientKey, canonicalRef)

        if (existing != null) {
            // Best-effort stats refresh (non-fatal)
            runCatching {
                graphEntityRegistryRepository.save(
                    existing.copy(
                        area = existing.area ?: deriveGraphArea(existing.canonicalKey),
                        lastSeenAt = now,
                        seenCount = existing.seenCount + 1,
                        updatedAt = now,
                    ),
                )
            }.onFailure { e ->
                if (e !is DuplicateKeyException) {
                    logger.debug(e) { "Failed to update canonical self-mapping stats (non-fatal): $canonicalRef" }
                }
            }
            return
        }

        runCatching {
            graphEntityRegistryRepository.save(
                GraphEntityRegistryDocument(
                    clientId = clientKey,
                    aliasKey = canonicalRef,
                    canonicalKey = canonicalRef,
                    area = deriveGraphArea(canonicalRef),
                    firstSeenAt = now,
                    lastSeenAt = now,
                    seenCount = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.onFailure { e ->
            // Tolerate races
            if (e !is DuplicateKeyException) {
                logger.debug(e) { "Failed to persist canonical self-mapping (non-fatal): $canonicalRef" }
            }
        }
    }

    /**
     * Conservative canonicalization: keeps structure, only removes redundant namespace prefix in the value part.
     * Examples:
     * - order:order_530798957 -> order:530798957
     * - product:product_lego_42152 -> product:lego_42152
     * - shipping:shipping_alzabox -> shipping:alzabox
     */
    private fun canonicalizeGraphRef(ref: String): String {
        val head = ref.substringBefore("::")
        val tail = ref.removePrefix(head)

        if (!head.contains(":")) return ref

        val ns = head.substringBefore(":").trim().lowercase()
        val value = head.substringAfter(":").trim()
        if (value.isBlank()) return ref

        val redundantPrefix = ns + "_"
        val canonValue =
            if (value.startsWith(redundantPrefix) && value.length > redundantPrefix.length) {
                value.removePrefix(redundantPrefix)
            } else {
                value
            }

        val canonHead = "$ns:$canonValue"
        return canonHead + tail
    }

    private fun normalizeSingleGraphRef(ref: String): String {
        // Keep the overall structure, but normalize namespace casing and internal whitespace.
        // We DO NOT attempt to rename IDs (agent owns semantics); we just stabilize formatting.
        val cleaned = ref.replace(Regex("\\s+"), " ").trim()

        // Normalize the namespace part before ':' (and before any '::' suffix)
        val head = cleaned.substringBefore("::")
        val tail = cleaned.removePrefix(head) // includes "::..." or empty

        val ns = head.substringBefore(":", missingDelimiterValue = head)
        val rest = if (head.contains(":")) head.substringAfter(":") else ""

        val nsNorm = ns.trim().lowercase()

        val headNorm =
            if (rest.isBlank() || !head.contains(":")) {
                nsNorm
            } else {
                "$nsNorm:${rest.trim()}"
            }

        return headNorm + tail
    }

    private fun deriveGraphArea(ref: String): String? {
        val base = ref.substringBefore("::").trim()
        if (base.isBlank()) return null

        val nsRaw = base.substringBefore(":", missingDelimiterValue = base).trim()
        if (nsRaw.isBlank()) return null

        // If namespace is compound (email_email / jira_issue / etc.), area is the first token.
        val areaCandidate = nsRaw.substringBefore("_").lowercase()

        // Coarse bucketing for better retrieval/filters (keeps agent freedom, but gives stable buckets)
        return when (areaCandidate) {
            // Time-like entities
            "date", "datetime", "time", "deadline", "due" -> "time"

            // Location-like entities
            "address", "street", "location", "place", "city", "country" -> "location"

            // Otherwise keep the derived area as-is
            else -> areaCandidate
        }
    }
}

/**
 * Mongo registry for graph entity aliasing.
 * Stores per-client mapping: aliasKey -> canonicalKey.
 */
@Document("graph_entity_registry")
@CompoundIndexes(
    CompoundIndex(name = "client_alias_unique", def = "{'clientId': 1, 'aliasKey': 1}", unique = true),
    CompoundIndex(name = "client_canonical_idx", def = "{'clientId': 1, 'canonicalKey': 1}"),
    CompoundIndex(name = "client_area_idx", def = "{'clientId': 1, 'area': 1}"),
)
internal data class GraphEntityRegistryDocument(
    @Id
    val id: ObjectId? = null,
    @Indexed
    val clientId: String,
    @Indexed
    val aliasKey: String,
    @Indexed
    val canonicalKey: String,
    @Indexed
    val area: String? = null,
    val firstSeenAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
    val seenCount: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

internal interface GraphEntityRegistryRepository : CoroutineCrudRepository<GraphEntityRegistryDocument, ObjectId> {
    suspend fun findFirstByClientIdAndAliasKey(
        clientId: String,
        aliasKey: String,
    ): GraphEntityRegistryDocument?

    suspend fun findFirstByClientIdAndCanonicalKey(
        clientId: String,
        canonicalKey: String,
    ): GraphEntityRegistryDocument?

    fun findAllByClientIdAndArea(
        clientId: String,
        area: String,
    ): Flow<GraphEntityRegistryDocument>
}
