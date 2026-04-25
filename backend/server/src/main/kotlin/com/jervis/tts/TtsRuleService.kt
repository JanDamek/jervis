package com.jervis.tts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private val logger = KotlinLogging.logger {}

/**
 * Business logic for TTS normalization rules.
 *
 * - CRUD + scope-aware lookup used by XTTS normalizer (per request)
 * - Regex validation for STRIP / REPLACE rules
 * - Push-only snapshot stream for the Settings UI (guideline #9)
 */
@Service
class TtsRuleService(
    private val repo: TtsRuleRepository,
) {
    private val snapshotFlow = MutableSharedFlow<TtsRulesSnapshot>(replay = 1, extraBufferCapacity = 8)

    /** Every state change re-renders the full snapshot to subscribers. */
    fun subscribeAll(): SharedFlow<TtsRulesSnapshot> = snapshotFlow.asSharedFlow()

    suspend fun pushSnapshot() {
        val rules = repo.findAll().toList()
        snapshotFlow.tryEmit(TtsRulesSnapshot(rules))
    }

    /**
     * All rules applicable to a request, sorted by precedence
     * (PROJECT > CLIENT > GLOBAL). XTTS applies them in this order so
     * specific rules win over generic ones.
     */
    suspend fun rulesForScope(
        language: String,
        clientId: ObjectId?,
        projectId: ObjectId?,
    ): List<TtsRuleDocument> {
        val raw = repo.findForScope(language, clientId, projectId).toList()
        return raw.sortedByDescending {
            when (it.scope.type) {
                TtsRuleScopeType.PROJECT -> 3
                TtsRuleScopeType.CLIENT -> 2
                TtsRuleScopeType.GLOBAL -> 1
            }
        }
    }

    suspend fun add(rule: TtsRuleDocument): TtsRuleDocument {
        validateRule(rule)
        val saved = repo.save(rule)
        pushSnapshot()
        return saved
    }

    suspend fun update(rule: TtsRuleDocument): TtsRuleDocument {
        validateRule(rule)
        val saved = repo.save(rule)
        pushSnapshot()
        return saved
    }

    suspend fun delete(id: ObjectId) {
        repo.deleteById(id)
        pushSnapshot()
    }

    suspend fun getById(id: ObjectId): TtsRuleDocument? = repo.getById(id)

    suspend fun listAll(): List<TtsRuleDocument> = repo.findAll().toList()

    /**
     * Dry-run normalization preview. Applies all scope-matching rules to
     * [input] and returns the result plus a per-rule hit trace so the UI
     * can show exactly what changed.
     */
    suspend fun preview(
        input: String,
        language: String,
        clientId: ObjectId?,
        projectId: ObjectId?,
    ): TtsRulePreview {
        val rules = rulesForScope(language, clientId, projectId)
        val hits = mutableListOf<TtsRuleHit>()
        var current = input
        for (rule in rules) {
            val before = current
            current = applyRule(current, rule)
            if (before != current) {
                hits.add(TtsRuleHit(rule.id, rule.type, before.length - current.length))
            }
        }
        return TtsRulePreview(output = current, hits = hits)
    }

    private fun applyRule(text: String, rule: TtsRuleDocument): String = when (rule.type) {
        TtsRuleType.ACRONYM -> {
            val acronym = rule.acronym ?: return text
            val pronunciation = rule.pronunciation ?: return text
            val variants = buildList {
                add(acronym)
                addAll(rule.aliases.orEmpty())
            }.distinct()
            var out = text
            for (v in variants) {
                out = Pattern.compile("\\b" + Pattern.quote(v) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(out)
                    .replaceAll(pronunciation)
            }
            out
        }
        TtsRuleType.STRIP -> {
            val pattern = rule.pattern ?: return text
            val compiled = Pattern.compile(pattern)
            if (rule.stripWrappingParens == true) {
                // Remove `(<match>)`, `( <match> )`, trailing junk and excess spaces.
                val wrapped = Pattern.compile("\\s*\\(\\s*$pattern\\s*\\)\\s*")
                wrapped.matcher(text).replaceAll(" ")
                    .let { compiled.matcher(it).replaceAll("") }
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } else {
                compiled.matcher(text).replaceAll("").replace(Regex("\\s+"), " ").trim()
            }
        }
        TtsRuleType.REPLACE -> {
            val pattern = rule.pattern ?: return text
            val replacement = rule.replacement ?: return text
            Pattern.compile(pattern).matcher(text).replaceAll(replacement)
        }
    }

    private fun validateRule(rule: TtsRuleDocument) {
        when (rule.type) {
            TtsRuleType.ACRONYM -> {
                require(!rule.acronym.isNullOrBlank()) { "acronym required" }
                require(!rule.pronunciation.isNullOrBlank()) { "pronunciation required" }
            }
            TtsRuleType.STRIP -> {
                require(!rule.pattern.isNullOrBlank()) { "pattern required" }
                require(!rule.description.isNullOrBlank()) { "description required" }
                validateRegex(rule.pattern)
            }
            TtsRuleType.REPLACE -> {
                require(!rule.pattern.isNullOrBlank()) { "pattern required" }
                require(rule.replacement != null) { "replacement required" }
                require(!rule.description.isNullOrBlank()) { "description required" }
                validateRegex(rule.pattern)
            }
        }
        if (rule.scope.type == TtsRuleScopeType.CLIENT) {
            require(rule.scope.clientId != null) { "clientId required for CLIENT scope" }
        }
        if (rule.scope.type == TtsRuleScopeType.PROJECT) {
            require(rule.scope.projectId != null) { "projectId required for PROJECT scope" }
        }
    }

    private fun validateRegex(pattern: String) {
        try {
            Pattern.compile(pattern)
        } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException("invalid regex: ${e.description}", e)
        }
    }
}

data class TtsRulesSnapshot(val rules: List<TtsRuleDocument>)

data class TtsRulePreview(val output: String, val hits: List<TtsRuleHit>)

data class TtsRuleHit(val ruleId: ObjectId, val type: TtsRuleType, val charsRemoved: Int)
