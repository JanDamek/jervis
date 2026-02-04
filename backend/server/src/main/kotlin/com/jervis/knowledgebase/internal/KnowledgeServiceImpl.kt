package com.jervis.knowledgebase.internal

import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.IngestResult
import com.jervis.orchestrator.model.EvidenceItem
import com.jervis.knowledgebase.HybridSearchRequest
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.SearchResult
import com.jervis.knowledgebase.IngestRequest
import com.jervis.knowledgebase.RetrievalRequest
import com.jervis.knowledgebase.FactSearchResult
import com.jervis.knowledgebase.internal.entity.GraphEntityRegistryDocument
import com.jervis.knowledgebase.internal.graphdb.GraphDBService
import com.jervis.knowledgebase.internal.graphdb.model.GraphEdge
import com.jervis.knowledgebase.internal.graphdb.model.GraphNode
import com.jervis.knowledgebase.internal.graphdb.model.TraversalSpec
import com.jervis.knowledgebase.internal.model.RagMetadata
import com.jervis.knowledgebase.internal.repository.GraphEntityRegistryRepository
import com.jervis.knowledgebase.internal.repository.VectorDocument
import com.jervis.knowledgebase.internal.repository.VectorFilters
import com.jervis.knowledgebase.internal.repository.VectorQuery
import com.jervis.knowledgebase.internal.repository.WeaviateVectorStore
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.types.ClientId
import com.jervis.types.SourceUrn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.security.MessageDigest
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

    private val graphRefCache = ConcurrentHashMap<String, String>()
    private val graphRefLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun ingest(request: IngestRequest): IngestResult {
        logger.info { "INGEST: clientId=${request.clientId}, sourceUrn=${request.sourceUrn}, kind=${request.kind}" }

        val contentHash = sha256(request.content)
        // Dedupe check: clientId + sourceUrn + contentHash
        // For simplicity, we'll use Weaviate's ID as clientId+sourceUrn+chunkIndex if we did chunking,
        // but since we want to be idempotent for the whole ingest:
        // Let's assume for now we just want to avoid re-ingesting EXACTLY the same content for the same source.

        // 1. Chunking
        val chunks = simpleChunking(request.content)
        logger.info { "Ingest: split into ${chunks.size} chunks" }

        val ingestedNodeKeys = mutableSetOf<String>()
        var chunkCount = 0

        weaviateProvisioner.ensureClientCollections(request.clientId)
        val className = perClientClassName(request.clientId)

        chunks.forEachIndexed { index, chunkContent ->
            val chunkHash = sha256(chunkContent)
            // Composite ID for idempotence
            val chunkId = UUID.nameUUIDFromBytes("${request.clientId}|${request.sourceUrn}|${index}|$chunkHash".toByteArray()).toString()

            // Check if exists
            // (We could do a retrieval by ID, but Weaviate's store is often upsert-by-ID)

            // 2. Extraction & Canonicalization (Placeholder for more advanced logic)
            // For now, we extract some basic terms as nodes
            val extractedNodes = extractNodes(chunkContent)
            val normalizedGraph = normalizeGraphRefs(request.clientId, extractedNodes)

            // 3. Store in RAG
            val embedding = embeddingGateway.callEmbedding(chunkContent)
            val metadata = mutableMapOf<String, Any>(
                "knowledgeId" to chunkId,
                "content" to chunkContent,
                "contentHash" to chunkHash,
                "sourceUrn" to request.sourceUrn.value,
                "clientId" to request.clientId.toString(),
                "kind" to request.kind,
                "chunkIndex" to index,
                "totalChunks" to chunks.size,
                "observedAt" to request.observedAt.toString(),
                "scope" to if (request.projectId != null) "project" else "client"
            )
            request.projectId?.let { metadata["projectId"] = it.toString() }
            if (normalizedGraph.refs.isNotEmpty()) {
                metadata["graphRefs"] = normalizedGraph.refs
                metadata["graphAreas"] = normalizedGraph.areas
            }

            val vectorDoc = VectorDocument(
                id = chunkId,
                content = chunkContent,
                embedding = embedding,
                metadata = metadata
            )
            weaviateVectorStore.store(vectorDoc, className).getOrThrow()
            chunkCount++

            // 4. Store in Graph
            runCatching {
                persistGraph(
                    clientId = request.clientId,
                    ragChunkId = chunkId,
                    nodes = normalizedGraph.refs,
                    relationships = emptyList() // No edges extracted yet in simple ingest
                )
            }
            ingestedNodeKeys.addAll(normalizedGraph.refs)
        }

        return IngestResult(
            success = true,
            summary = "Ingested $chunkCount chunks from ${request.sourceUrn}",
            ingestedNodes = ingestedNodeKeys.toList()
        )
    }

    override suspend fun retrieve(request: RetrievalRequest): EvidencePack {
        logger.info { "RETRIEVE: query='${request.query}', clientId=${request.clientId}, asOf=${request.asOf}" }

        // 1. RAG-first search
        val embedding = embeddingGateway.callEmbedding(request.query)
        val query = VectorQuery(
            embedding = embedding,
            limit = request.maxResults,
            minScore = request.minConfidence.toFloat(),
            filters = VectorFilters(
                clientId = request.clientId,
                projectId = request.projectId
            )
        )

        val className = perClientClassName(request.clientId)
        val ragResults = weaviateVectorStore.search(query, className).getOrThrow()

        // Filter by observedAt if asOf is provided
        val filteredRagResults = if (request.asOf != null) {
            ragResults.filter { res ->
                val observedAtStr = res.metadata["observedAt"]?.toString()
                if (observedAtStr != null) {
                    runCatching { Instant.parse(observedAtStr) }.getOrNull()?.isBefore(request.asOf) ?: true
                } else true
            }
        } else {
            ragResults
        }

        // 2. Seed nodes from chunks
        val seedNodeKeys = filteredRagResults.flatMap {
            val refs = (it.metadata["graphRefs"] as? List<*>)?.mapNotNull { r -> r?.toString() } ?: emptyList()
            refs
        }.distinct()

        // 3. Graph expand
        val expandedNodes = mutableListOf<GraphNode>()
        for (seed in seedNodeKeys.take(10)) {
            graphDBService.traverse(request.clientId, seed, TraversalSpec(maxDepth = 2)).toList(expandedNodes)
        }

        // 4. Complement evidence (fetch chunks for expanded nodes)
        // For simplicity, we use the chunks already linked in GraphNode
        val allEvidenceChunkIds = (filteredRagResults.map { it.id } + expandedNodes.flatMap { it.ragChunks }).distinct()

        // 5. Build EvidencePack
        val evidenceItems = filteredRagResults.map {
            EvidenceItem(
                source = "RAG",
                content = it.content,
                confidence = it.score,
                metadata = it.metadata.mapValues { (_, v) -> v.toString() }
            )
        }

        return EvidencePack(
            items = evidenceItems,
            summary = "Found ${filteredRagResults.size} RAG results and ${expandedNodes.size} related graph nodes."
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun simpleChunking(content: String): List<String> {
        // Very basic chunking by paragraphs or size
        if (content.length < 1000) return listOf(content)
        return content.split("\n\n").filter { it.isNotBlank() }
    }

    private fun extractNodes(content: String): List<String> {
        // Simple regex-based extraction of "Type:ID" or similar patterns
        val pattern = Regex("([a-zA-Z0-9]+:[a-zA-Z0-9_-]+)")
        return pattern.findAll(content).map { it.value }.toList()
    }

    private suspend fun perClientClassName(clientId: ClientId): String = WeaviateClassNameUtil.classFor(clientId)

    private data class NormalizedGraphRefs(
        val refs: List<String>,
        val areas: List<String>,
        val rootRef: String?,
        val primaryArea: String?,
        val aliasToCanonical: Map<String, String>,
    )

    private data class GraphPayload(
        val allNodes: List<String>,
        val rawTriples: List<Triple<String, String, String>>,
    )

    private fun buildGraphPayload(
        mainNodeKey: String,
        relationships: List<String>,
    ): GraphPayload {
        val mainRaw = mainNodeKey.trim()
        val mainId = extractTrailingId(mainRaw)

        val mainNorm = normalizeSingleGraphRef(mainRaw)

        val rawTriples =
            relationships
                .mapNotNull { parseRelationshipPipe(it) }
                .map { (from, edge, to) ->
                    Triple(
                        normalizeSingleGraphRef(expandShortNodeKey(from, mainRaw, mainId)),
                        normalizeEdgeType(edge),
                        normalizeSingleGraphRef(expandShortNodeKey(to, mainRaw, mainId)),
                    )
                }

        val allNodes =
            buildSet {
                add(mainNorm)
                rawTriples.forEach { (from, _, to) ->
                    add(from)
                    add(to)
                }
            }.toList()

        return GraphPayload(
            allNodes = allNodes,
            rawTriples = rawTriples,
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
        val aliasToCanonical = LinkedHashMap<String, String>()
        for (ref in normalizedInput) {
            aliasToCanonical[ref] = resolveCanonicalGraphRef(clientId, ref)
        }

        val refs = aliasToCanonical.values.distinct().sorted()

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
            aliasToCanonical = aliasToCanonical.toMap(),
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
 * Internal fragment representation - only for formatting.
 * File-private so both the service and helpers can access it.
 */
private data class InternalFragment(
    val sourceUrn: String,
    val content: String,
    val graphPrimaryArea: String? = null,
    val graphAreas: List<String> = emptyList(),
    val graphRefs: List<String> = emptyList(),
)

/**
 * Convert Weaviate results into fragments.
 * NOTE: `weaviateVectorStore.search(...)` currently returns `List<VectorSearchResult>`.
 * We keep this adapter tolerant by accepting `List<Any>` (List is covariant), and extracting a
 * `VectorDocument` via reflection when needed.
 */
private fun toFragments(results: List<Any>): List<InternalFragment> =
    results.mapNotNull { item ->
        val doc =
            when (item) {
                is VectorDocument -> item
                else -> item.extractVectorDocumentOrNull()
            }

        val content = doc?.content ?: item.readStringProperty("content") ?: return@mapNotNull null
        val metadata = doc?.metadata ?: item.readMapProperty("metadata")

        InternalFragment(
            sourceUrn = metadata["sourceUrn"]?.toString() ?: "",
            content = content,
            graphPrimaryArea = metadata["graphPrimaryArea"]?.toString(),
            graphAreas = (metadata["graphAreas"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            graphRefs = (metadata["graphRefs"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
        )
    }

/** Try to unwrap `VectorSearchResult`-like wrappers to `VectorDocument` via common accessor names. */
private fun Any.extractVectorDocumentOrNull(): VectorDocument? {
    readObjectProperty("document")?.let { if (it is VectorDocument) return it }
    readObjectProperty("item")?.let { if (it is VectorDocument) return it }
    readObjectProperty("result")?.let { if (it is VectorDocument) return it }
    readObjectProperty("vectorDocument")?.let { if (it is VectorDocument) return it }
    return null
}

private fun Any.readObjectProperty(name: String): Any? {
    val cls = this.javaClass

    val getterName = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    runCatching {
        val m = cls.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        if (m != null) return m.invoke(this)
    }

    runCatching {
        val f = cls.declaredFields.firstOrNull { it.name == name }
        if (f != null) {
            f.isAccessible = true
            return f.get(this)
        }
    }

    return null
}

private fun Any.readStringProperty(name: String): String? = readObjectProperty(name) as? String

@Suppress("UNCHECKED_CAST")
private fun Any.readMapProperty(name: String): Map<String, Any> =
    (readObjectProperty(name) as? Map<*, *>)
        ?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as Any) } }
        ?.toMap()
        ?: emptyMap()
