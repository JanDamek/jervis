package com.jervis.service.agent

import com.jervis.domain.agent.AgentMemoryDocument
import com.jervis.repository.AgentMemoryRepository
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * AgentMemoryService – dlouhodobá persistentní paměť pro KoogAgent.
 *
 * Poskytuje:
 * - Uložení paměti s audit trail
 * - Vyhledávání podle tématu, data, projektu, entity, kontextu
 * - Nic se nemaže – permanent storage
 *
 * IMPORTANT: Všechny repository jsou CoroutineCrudRepository (non-blocking).
 * Blocking MongoRepository se NEPOUŽÍVÁ.
 */
@Service
class AgentMemoryService(
    private val repository: AgentMemoryRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Uložit novou paměť do long-term storage.
     */
    suspend fun write(memory: AgentMemoryDocument): AgentMemoryDocument {
        val saved = repository.save(memory)
        logger.info {
            "AgentMemory written: clientId=${memory.clientId}, " +
                "actionType=${memory.actionType}, " +
                "entityKey=${memory.entityKey}, " +
                "id=${saved.id.toHexString()}"
        }
        return saved
    }

    /**
     * Přečíst poslední N memories pro daného klienta.
     */
    suspend fun read(clientId: String, limit: Int = 50): List<AgentMemoryDocument> {
        return repository.findByClientIdOrderByTimestampDesc(clientId)
            .take(limit)
            .toList()
    }

    /**
     * Přečíst memories pro projekt.
     */
    suspend fun readByProject(clientId: String, projectId: String, limit: Int = 50): List<AgentMemoryDocument> {
        return repository.findByClientIdAndProjectIdOrderByTimestampDesc(clientId, projectId)
            .take(limit)
            .toList()
    }

    /**
     * Přečíst celou session/workflow podle correlation ID.
     */
    suspend fun readByCorrelation(clientId: String, correlationId: String): List<AgentMemoryDocument> {
        return repository.findByClientIdAndCorrelationIdOrderByTimestamp(clientId, correlationId)
            .toList()
    }

    /**
     * Vyhledat memories podle typu akce.
     */
    suspend fun searchByActionType(clientId: String, actionType: String, limit: Int = 50): List<AgentMemoryDocument> {
        return repository.findByClientIdAndActionTypeOrderByTimestampDesc(clientId, actionType)
            .take(limit)
            .toList()
    }

    /**
     * Vyhledat memories podle entity (např. všechny akce na konkrétním souboru).
     */
    suspend fun searchByEntity(clientId: String, entityType: String, entityKey: String, limit: Int = 50): List<AgentMemoryDocument> {
        return repository.findByClientIdAndEntityTypeAndEntityKeyOrderByTimestampDesc(clientId, entityType, entityKey)
            .take(limit)
            .toList()
    }

    /**
     * Vyhledat memories podle tagů.
     */
    suspend fun searchByTags(clientId: String, tags: List<String>, limit: Int = 50): List<AgentMemoryDocument> {
        return repository.findByClientIdAndTagsInOrderByTimestampDesc(clientId, tags)
            .take(limit)
            .toList()
    }

    /**
     * Full-text search v content/reason (vyžaduje MongoDB text index).
     * Note: Tato funkce vyžaduje custom implementation, protože @Query s Flow není plně podporován.
     */
    suspend fun searchByText(clientId: String, query: String, limit: Int = 50): List<AgentMemoryDocument> {
        // Pro full-text search je potřeba použít MongoTemplate nebo custom implementation
        // Pro nyní vracíme prázdný seznam - implementovat až bude potřeba
        logger.warn { "searchByText not yet implemented - requires MongoTemplate with Flow support" }
        return emptyList()
    }

    /**
     * Vyhledat memories v časovém rozmezí.
     */
    suspend fun searchByTimeRange(
        clientId: String,
        from: Instant,
        to: Instant,
        limit: Int = 50,
    ): List<AgentMemoryDocument> {
        return repository.findByClientIdAndTimestampBetweenOrderByTimestampDesc(clientId, from, to)
            .take(limit)
            .toList()
    }

    /**
     * Vyhledat poslední memories za X dní.
     */
    suspend fun searchLastDays(clientId: String, days: Long, limit: Int = 50): List<AgentMemoryDocument> {
        val from = Instant.now().minus(days, ChronoUnit.DAYS)
        val to = Instant.now()
        return searchByTimeRange(clientId, from, to, limit)
    }

    /**
     * Statistiky memories pro klienta.
     */
    suspend fun getStats(clientId: String): MemoryStats {
        val all = read(clientId, Int.MAX_VALUE)
        val actionTypes = all.groupingBy { it.actionType }.eachCount()
        val entityTypes = all.groupingBy { it.entityType ?: "unknown" }.eachCount()
        val last7Days = searchLastDays(clientId, 7, Int.MAX_VALUE).size
        val last30Days = searchLastDays(clientId, 30, Int.MAX_VALUE).size

        return MemoryStats(
            total = all.size,
            last7Days = last7Days,
            last30Days = last30Days,
            byActionType = actionTypes,
            byEntityType = entityTypes,
            oldestTimestamp = all.minByOrNull { it.timestamp }?.timestamp,
            newestTimestamp = all.maxByOrNull { it.timestamp }?.timestamp,
        )
    }

    data class MemoryStats(
        val total: Int,
        val last7Days: Int,
        val last30Days: Int,
        val byActionType: Map<String, Int>,
        val byEntityType: Map<String, Int>,
        val oldestTimestamp: Instant?,
        val newestTimestamp: Instant?,
    )
}
