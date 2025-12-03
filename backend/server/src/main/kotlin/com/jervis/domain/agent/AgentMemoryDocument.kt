package com.jervis.domain.agent

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * AgentMemoryDocument – dlouhodobá persistentní paměť agenta s audit trail.
 *
 * Obsahuje:
 * - Co agent udělal (action, content)
 * - Proč to udělal (reason, context)
 * - Kdy a v jakém kontextu (timestamp, clientId, projectId, correlationId)
 * - Související entity (entityType, entityKey, relatedTo)
 *
 * Nic se NEMAŽE – všechny záznamy jsou permanent pro dlouhodobý audit.
 */
@Document(collection = "agent_memory")
data class AgentMemoryDocument(
    @Id
    val id: ObjectId = ObjectId(),

    /** Client ID (izolace per-client) */
    @Indexed
    val clientId: String,

    /** Project ID (optional, per-project izolace) */
    @Indexed
    val projectId: String? = null,

    /** Correlation ID (pro sledování celé session/workflow) */
    @Indexed
    val correlationId: String? = null,

    /** Kdy byl záznam vytvořen */
    @Indexed
    val timestamp: Instant = Instant.now(),

    /** Typ akce (např. FILE_EDIT, TASK_CREATE, SHELL_EXEC, MEMORY_WRITE, DECISION, ANALYSIS) */
    @Indexed
    val actionType: String,

    /** Hlavní obsah paměti (co agent udělal nebo si zapamatoval) */
    val content: String,

    /** Důvod proč agent provedl akci (audit trail - "why") */
    val reason: String,

    /** Kontext ve kterém akce vznikla (uživatelský vstup, předchozí kroky, atd.) */
    val context: String? = null,

    /** Výsledek operace (success/error, detaily) */
    val result: String? = null,

    /** Typ entity kterou akce ovlivnila (FILE, CLASS, METHOD, TASK, COMMIT, BRANCH, atd.) */
    val entityType: String? = null,

    /** Klíč entity (např. file path, task ID, commit hash) */
    @Indexed
    val entityKey: String? = null,

    /** Související záznamy (odkazy na jiné memory ID nebo entity) */
    val relatedTo: List<String> = emptyList(),

    /** Tagy pro snadnější vyhledávání */
    @Indexed
    val tags: List<String> = emptyList(),

    /** Embeddings pro sémantické vyhledávání (optional, pro integraci s vector DB) */
    val embedding: List<Double>? = null,

    /** Metadata (volně strukturovaná data) */
    val metadata: Map<String, String> = emptyMap(),
)
