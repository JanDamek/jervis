package com.jervis.knowledgebase.internal

import com.jervis.knowledgebase.internal.entity.GraphEntityRegistryDocument
import com.jervis.knowledgebase.internal.repository.GraphEntityRegistryRepository
import com.jervis.types.ClientId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
internal class GraphRefNormalizer(
    private val graphEntityRegistryRepository: GraphEntityRegistryRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val graphRefCache = ConcurrentHashMap<String, String>()
    private val graphRefLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun normalizeGraphRefs(
        clientId: ClientId,
        input: List<String>,
    ): NormalizedGraphRefs {
        val normalizedInput =
            input
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { normalizeSingleGraphRef(it) }
                .distinct()
                .toList()

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

    fun normalizeSingleGraphRef(ref: String): String {
        val cleaned = ref.replace(Regex("\\s+"), " ").trim()

        val head = cleaned.substringBefore("::")
        val tail = cleaned.removePrefix(head)

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

    fun deriveGraphArea(ref: String): String? {
        val base = ref.substringBefore("::").trim()
        if (base.isBlank()) return null

        val nsRaw = base.substringBefore(":", missingDelimiterValue = base).trim()
        if (nsRaw.isBlank()) return null

        val areaCandidate = nsRaw.substringBefore("_").lowercase()

        return when (areaCandidate) {
            "date", "datetime", "time", "deadline", "due" -> "time"
            "address", "street", "location", "place", "city", "country" -> "location"
            else -> areaCandidate
        }
    }

    fun entityTypeFromNodeKey(nodeKey: String): String {
        val base = nodeKey.substringBefore("::").trim()
        return base
            .substringBefore(":", missingDelimiterValue = base)
            .trim()
            .lowercase()
            .ifBlank { "entity" }
    }

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

            val existing = graphEntityRegistryRepository.findFirstByClientIdAndAliasKey(clientKey, aliasRef)
            if (existing != null) {
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

            val canonicalCandidate = canonicalizeGraphRef(aliasRef)

            val canonicalExisting =
                if (canonicalCandidate != aliasRef) {
                    graphEntityRegistryRepository.findFirstByClientIdAndCanonicalKey(clientKey, canonicalCandidate)
                } else {
                    null
                }

            val canonicalToUse = canonicalExisting?.canonicalKey ?: canonicalCandidate

            ensureCanonicalSelfMapping(clientId, canonicalToUse, now)

            if (aliasRef == canonicalToUse) {
                graphRefCache[cacheKey] = canonicalToUse
                return@withLock canonicalToUse
            }

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
            if (e !is DuplicateKeyException) {
                logger.debug(e) { "Failed to persist canonical self-mapping (non-fatal): $canonicalRef" }
            }
        }
    }

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
}

internal data class NormalizedGraphRefs(
    val refs: List<String>,
    val areas: List<String>,
    val rootRef: String?,
    val primaryArea: String?,
    val aliasToCanonical: Map<String, String>,
)
