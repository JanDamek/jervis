package com.jervis.tts

import com.jervis.dto.tts.TtsRuleDto
import com.jervis.dto.tts.TtsRuleHitDto
import com.jervis.dto.tts.TtsRulePreviewDto
import com.jervis.dto.tts.TtsRulePreviewRequestDto
import com.jervis.dto.tts.TtsRuleScopeDto
import com.jervis.dto.tts.TtsRuleScopeTypeDto
import com.jervis.dto.tts.TtsRuleTypeDto
import com.jervis.dto.tts.TtsRulesSnapshotDto
import com.jervis.infrastructure.error.ErrorLogService
import com.jervis.rpc.BaseRpcImpl
import com.jervis.service.tts.ITtsRuleService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * kRPC implementation — the Settings UI subscribes to [subscribeAll] for
 * a live snapshot (rule #9 push-only) and calls the unary CRUD methods
 * for edits. Mutations trigger a fresh snapshot emission in [service].
 */
@Component
class TtsRuleRpcImpl(
    private val service: TtsRuleService,
    errorLogService: ErrorLogService,
) : BaseRpcImpl(errorLogService), ITtsRuleService {

    override fun subscribeAll(): Flow<TtsRulesSnapshotDto> =
        service.subscribeAll()
            .onStart { service.pushSnapshot() }
            .map { snap -> TtsRulesSnapshotDto(snap.rules.map { it.toDto() }) }

    override suspend fun add(rule: TtsRuleDto): TtsRuleDto =
        executeWithErrorHandling("TtsRule.add") {
            service.add(rule.toDomain(idOverride = ObjectId())).toDto()
        }

    override suspend fun update(rule: TtsRuleDto): TtsRuleDto =
        executeWithErrorHandling("TtsRule.update") {
            require(rule.id.isNotBlank()) { "id required for update" }
            service.update(rule.toDomain(idOverride = ObjectId(rule.id))).toDto()
        }

    override suspend fun delete(id: String) {
        executeWithErrorHandling("TtsRule.delete") {
            service.delete(ObjectId(id))
        }
    }

    override suspend fun preview(request: TtsRulePreviewRequestDto): TtsRulePreviewDto =
        executeWithErrorHandling("TtsRule.preview") {
            val preview = service.preview(
                input = request.text,
                language = request.language,
                clientId = request.clientId?.toObjectIdOrNull(),
                projectId = request.projectId?.toObjectIdOrNull(),
            )
            TtsRulePreviewDto(
                output = preview.output,
                hits = preview.hits.map {
                    TtsRuleHitDto(
                        ruleId = it.ruleId.toHexString(),
                        type = it.type.toDto(),
                        charsRemoved = it.charsRemoved,
                    )
                },
            )
        }
}

private fun String.toObjectIdOrNull(): ObjectId? =
    takeIf { it.isNotBlank() && ObjectId.isValid(it) }?.let { ObjectId(it) }

internal fun TtsRuleDocument.toDto(): TtsRuleDto = TtsRuleDto(
    id = id.toHexString(),
    type = type.toDto(),
    language = language,
    scope = scope.toDto(),
    acronym = acronym,
    pronunciation = pronunciation,
    aliases = aliases.orEmpty(),
    pattern = pattern,
    description = description,
    stripWrappingParens = stripWrappingParens,
    replacement = replacement,
)

internal fun TtsRuleDto.toDomain(idOverride: ObjectId): TtsRuleDocument = TtsRuleDocument(
    id = idOverride,
    type = type.toDomain(),
    language = language.ifBlank { "any" },
    scope = scope.toDomain(),
    acronym = acronym?.takeIf { it.isNotBlank() },
    pronunciation = pronunciation?.takeIf { it.isNotBlank() },
    aliases = aliases.takeIf { it.isNotEmpty() },
    pattern = pattern?.takeIf { it.isNotBlank() },
    description = description?.takeIf { it.isNotBlank() },
    stripWrappingParens = if (type == TtsRuleTypeDto.STRIP) stripWrappingParens else null,
    replacement = if (type == TtsRuleTypeDto.REPLACE) replacement else null,
)

internal fun TtsRuleType.toDto(): TtsRuleTypeDto = when (this) {
    TtsRuleType.ACRONYM -> TtsRuleTypeDto.ACRONYM
    TtsRuleType.STRIP -> TtsRuleTypeDto.STRIP
    TtsRuleType.REPLACE -> TtsRuleTypeDto.REPLACE
}

internal fun TtsRuleTypeDto.toDomain(): TtsRuleType = when (this) {
    TtsRuleTypeDto.ACRONYM -> TtsRuleType.ACRONYM
    TtsRuleTypeDto.STRIP -> TtsRuleType.STRIP
    TtsRuleTypeDto.REPLACE -> TtsRuleType.REPLACE
}

internal fun TtsRuleScope.toDto(): TtsRuleScopeDto = TtsRuleScopeDto(
    type = type.toDto(),
    clientId = clientId?.toHexString(),
    projectId = projectId?.toHexString(),
)

internal fun TtsRuleScopeDto.toDomain(): TtsRuleScope = TtsRuleScope(
    type = type.toDomain(),
    clientId = clientId?.takeIf { it.isNotBlank() && ObjectId.isValid(it) }?.let { ObjectId(it) },
    projectId = projectId?.takeIf { it.isNotBlank() && ObjectId.isValid(it) }?.let { ObjectId(it) },
)

internal fun TtsRuleScopeType.toDto(): TtsRuleScopeTypeDto = when (this) {
    TtsRuleScopeType.GLOBAL -> TtsRuleScopeTypeDto.GLOBAL
    TtsRuleScopeType.CLIENT -> TtsRuleScopeTypeDto.CLIENT
    TtsRuleScopeType.PROJECT -> TtsRuleScopeTypeDto.PROJECT
}

internal fun TtsRuleScopeTypeDto.toDomain(): TtsRuleScopeType = when (this) {
    TtsRuleScopeTypeDto.GLOBAL -> TtsRuleScopeType.GLOBAL
    TtsRuleScopeTypeDto.CLIENT -> TtsRuleScopeType.CLIENT
    TtsRuleScopeTypeDto.PROJECT -> TtsRuleScopeType.PROJECT
}
