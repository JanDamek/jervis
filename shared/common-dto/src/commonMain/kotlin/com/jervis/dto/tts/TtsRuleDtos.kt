package com.jervis.dto.tts

import kotlinx.serialization.Serializable

/**
 * DTOs for the TTS normalization rules stored in `ttsRules` on the server.
 *
 * Three rule types share one collection; nullable fields are populated per
 * [type]. The UI Settings screen and orchestrator tools serialize these.
 */
@Serializable
enum class TtsRuleTypeDto { ACRONYM, STRIP, REPLACE }

@Serializable
enum class TtsRuleScopeTypeDto { GLOBAL, CLIENT, PROJECT }

@Serializable
data class TtsRuleScopeDto(
    val type: TtsRuleScopeTypeDto,
    val clientId: String? = null,
    val projectId: String? = null,
)

@Serializable
data class TtsRuleDto(
    /** Empty on create, populated by server on add. */
    val id: String = "",
    val type: TtsRuleTypeDto,
    /** "cs", "en", or "any". */
    val language: String = "any",
    val scope: TtsRuleScopeDto,
    // type = ACRONYM
    val acronym: String? = null,
    val pronunciation: String? = null,
    val aliases: List<String> = emptyList(),
    // type = STRIP / REPLACE
    val pattern: String? = null,
    val description: String? = null,
    val stripWrappingParens: Boolean? = null,
    val replacement: String? = null,
)

/** Snapshot pushed to the Settings UI on every rule change. */
@Serializable
data class TtsRulesSnapshotDto(val rules: List<TtsRuleDto>)

/** Per-rule trace produced by Preview — used by Settings UI to show which
 *  rules hit and how many characters they removed. */
@Serializable
data class TtsRuleHitDto(
    val ruleId: String,
    val type: TtsRuleTypeDto,
    val charsRemoved: Int,
)

@Serializable
data class TtsRulePreviewDto(
    val output: String,
    val hits: List<TtsRuleHitDto>,
)

@Serializable
data class TtsRulePreviewRequestDto(
    val text: String,
    val language: String = "any",
    val clientId: String? = null,
    val projectId: String? = null,
)
