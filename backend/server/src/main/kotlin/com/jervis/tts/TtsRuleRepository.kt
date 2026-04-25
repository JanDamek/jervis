package com.jervis.tts

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Reactive repository for [TtsRuleDocument].
 *
 * All scope filtering happens in DB per guideline #1. Lookup queries return
 * unordered Flow; the service layer enforces precedence
 * `PROJECT > CLIENT > GLOBAL` in-memory after the rules land.
 */
@Repository
interface TtsRuleRepository : CoroutineCrudRepository<TtsRuleDocument, ObjectId> {
    suspend fun getById(id: ObjectId): TtsRuleDocument?

    /**
     * All rules that could apply to a request scoped by [clientId] + [projectId]
     * in one round-trip. `language` is filtered to [lang] or "any".
     *
     * The $or clauses cover: global rules (always), client rules for this client
     * (if any), project rules for this project (if any). Scope precedence is
     * resolved in [TtsRuleService.rulesForScope].
     */
    @Query(
        """
        {
          ${'$'}and: [
            { language: { ${'$'}in: [?0, 'any'] } },
            { ${'$'}or: [
                { 'scope.type': 'GLOBAL' },
                { 'scope.type': 'CLIENT', 'scope.clientId': ?1 },
                { 'scope.type': 'PROJECT', 'scope.projectId': ?2 }
            ] }
          ]
        }
        """,
    )
    fun findForScope(
        lang: String,
        clientId: ObjectId?,
        projectId: ObjectId?,
    ): Flow<TtsRuleDocument>

    fun findByScopeType(scopeType: TtsRuleScopeType): Flow<TtsRuleDocument>

    fun findByScopeTypeAndScopeClientId(scopeType: TtsRuleScopeType, clientId: ObjectId): Flow<TtsRuleDocument>

    fun findByScopeTypeAndScopeProjectId(scopeType: TtsRuleScopeType, projectId: ObjectId): Flow<TtsRuleDocument>

    /** Exists check used by the seeder to stay idempotent on startup. */
    suspend fun countByScopeType(scopeType: TtsRuleScopeType): Long

    /**
     * Lookup used by the seeder to upsert defaults by a semantic key.
     * Matching `(type, description, scope.type)` is stable enough to
     * recognise a previously-seeded rule even if the user edited
     * pattern / replacement later (we don't overwrite user edits —
     * the seeder only inserts MISSING rules).
     */
    suspend fun findFirstByTypeAndDescriptionAndScopeType(
        type: TtsRuleType,
        description: String,
        scopeType: TtsRuleScopeType,
    ): TtsRuleDocument?

    /** Same semantic key for ACRONYM rules, which store the
     *  acronym (not `description`) as the identifier. */
    suspend fun findFirstByTypeAndAcronymAndScopeType(
        type: TtsRuleType,
        acronym: String,
        scopeType: TtsRuleScopeType,
    ): TtsRuleDocument?
}
