package com.jervis.tts

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * TTS normalization rule persisted in MongoDB.
 *
 * One collection holds three rule types — acronym expansion, pattern strip,
 * pattern replace — distinguished by [type]. A flat document keeps Mongo
 * queries simple; service layer maps to a typed sealed class before
 * exposing it over gRPC / kRPC.
 *
 * Scope precedence on lookup: `project > client > global`. The normalizer
 * concatenates matching rules in that order so more specific rules win.
 */
@Document(collection = "ttsRules")
@CompoundIndexes(
    CompoundIndex(name = "scope_lang", def = "{'scope.type': 1, 'scope.clientId': 1, 'scope.projectId': 1, 'language': 1}"),
)
data class TtsRuleDocument(
    @Id val id: ObjectId = ObjectId(),
    val type: TtsRuleType,
    /** "cs", "en", or "any" — filter applied by the normalizer per request. */
    val language: String,
    val scope: TtsRuleScope,
    // --- type = ACRONYM ---
    val acronym: String? = null,
    val pronunciation: String? = null,
    val aliases: List<String>? = null,
    // --- type = STRIP / REPLACE ---
    val pattern: String? = null,
    val description: String? = null,
    val stripWrappingParens: Boolean? = null,
    val replacement: String? = null,
)

enum class TtsRuleType { ACRONYM, STRIP, REPLACE }

/**
 * Scope embedded in [TtsRuleDocument]. `clientId` and `projectId` are
 * populated only for the matching [TtsRuleScopeType].
 */
data class TtsRuleScope(
    val type: TtsRuleScopeType,
    val clientId: ObjectId? = null,
    val projectId: ObjectId? = null,
)

enum class TtsRuleScopeType { GLOBAL, CLIENT, PROJECT }
