package com.jervis.tts

import com.jervis.contracts.server.DeleteTtsRuleRequest
import com.jervis.contracts.server.DeleteTtsRuleResponse
import com.jervis.contracts.server.GetForScopeRequest
import com.jervis.contracts.server.ListTtsRulesRequest
import com.jervis.contracts.server.PreviewRequest
import com.jervis.contracts.server.PreviewResponse
import com.jervis.contracts.server.ServerTtsRulesServiceGrpcKt
import com.jervis.contracts.server.TtsRule as TtsRuleProto
import com.jervis.contracts.server.TtsRuleHit as TtsRuleHitProto
import com.jervis.contracts.server.TtsRuleList
import com.jervis.contracts.server.TtsRuleScope as TtsRuleScopeProto
import com.jervis.contracts.server.TtsRuleScopeType as TtsRuleScopeTypeProto
import com.jervis.contracts.server.TtsRuleType as TtsRuleTypeProto
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * gRPC surface for [TtsRuleService].
 *
 * XTTS pod (sidecar to the audio VM, NOT in cluster) calls this service
 * before each SpeakStream to load scope-specific rules. Orchestrator + MCP
 * call the mutation RPCs when the user edits the dictionary via chat.
 */
@Component
class TtsRulesGrpcImpl(
    private val service: TtsRuleService,
) : ServerTtsRulesServiceGrpcKt.ServerTtsRulesServiceCoroutineImplBase() {

    override suspend fun getForScope(request: GetForScopeRequest): TtsRuleList {
        val rules = service.rulesForScope(
            language = request.language.ifBlank { "any" },
            clientId = request.clientId.toObjectIdOrNull(),
            projectId = request.projectId.toObjectIdOrNull(),
        )
        val builder = TtsRuleList.newBuilder()
        rules.forEach { builder.addRules(it.toProto()) }
        return builder.build()
    }

    override suspend fun list(request: ListTtsRulesRequest): TtsRuleList {
        val rules = service.listAll()
        val builder = TtsRuleList.newBuilder()
        rules.forEach { builder.addRules(it.toProto()) }
        return builder.build()
    }

    override suspend fun add(request: TtsRuleProto): TtsRuleProto =
        service.add(request.toDomain(idOverride = ObjectId())).toProto()

    override suspend fun update(request: TtsRuleProto): TtsRuleProto {
        require(request.id.isNotBlank()) { "id required for update" }
        return service.update(request.toDomain(idOverride = ObjectId(request.id))).toProto()
    }

    override suspend fun delete(request: DeleteTtsRuleRequest): DeleteTtsRuleResponse {
        service.delete(ObjectId(request.id))
        return DeleteTtsRuleResponse.newBuilder().setOk(true).build()
    }

    override suspend fun preview(request: PreviewRequest): PreviewResponse {
        val preview = service.preview(
            input = request.text,
            language = request.language.ifBlank { "any" },
            clientId = request.clientId.toObjectIdOrNull(),
            projectId = request.projectId.toObjectIdOrNull(),
        )
        val builder = PreviewResponse.newBuilder().setOutput(preview.output)
        preview.hits.forEach {
            builder.addHits(
                TtsRuleHitProto.newBuilder()
                    .setRuleId(it.ruleId.toHexString())
                    .setType(it.type.toProto())
                    .setCharsRemoved(it.charsRemoved)
                    .build(),
            )
        }
        return builder.build()
    }
}

private fun String.toObjectIdOrNull(): ObjectId? =
    takeIf { it.isNotBlank() && ObjectId.isValid(it) }?.let { ObjectId(it) }

internal fun TtsRuleDocument.toProto(): TtsRuleProto {
    val b = TtsRuleProto.newBuilder()
        .setId(id.toHexString())
        .setType(type.toProto())
        .setLanguage(language)
        .setScope(scope.toProto())
    acronym?.let { b.acronym = it }
    pronunciation?.let { b.pronunciation = it }
    aliases?.let { b.addAllAliases(it) }
    pattern?.let { b.pattern = it }
    description?.let { b.description = it }
    stripWrappingParens?.let { b.stripWrappingParens = it }
    replacement?.let { b.replacement = it }
    return b.build()
}

internal fun TtsRuleProto.toDomain(idOverride: ObjectId): TtsRuleDocument = TtsRuleDocument(
    id = idOverride,
    type = type.toDomain(),
    language = language.ifBlank { "any" },
    scope = scope.toDomain(),
    acronym = acronym.takeIf { it.isNotBlank() },
    pronunciation = pronunciation.takeIf { it.isNotBlank() },
    aliases = if (aliasesList.isEmpty()) null else aliasesList.toList(),
    pattern = pattern.takeIf { it.isNotBlank() },
    description = description.takeIf { it.isNotBlank() },
    stripWrappingParens = if (type == TtsRuleTypeProto.STRIP) stripWrappingParens else null,
    replacement = if (type == TtsRuleTypeProto.REPLACE) replacement else null,
)

internal fun TtsRuleType.toProto(): TtsRuleTypeProto = when (this) {
    TtsRuleType.ACRONYM -> TtsRuleTypeProto.ACRONYM
    TtsRuleType.STRIP -> TtsRuleTypeProto.STRIP
    TtsRuleType.REPLACE -> TtsRuleTypeProto.REPLACE
}

internal fun TtsRuleTypeProto.toDomain(): TtsRuleType = when (this) {
    TtsRuleTypeProto.ACRONYM -> TtsRuleType.ACRONYM
    TtsRuleTypeProto.STRIP -> TtsRuleType.STRIP
    TtsRuleTypeProto.REPLACE -> TtsRuleType.REPLACE
    TtsRuleTypeProto.TTS_RULE_TYPE_UNSPECIFIED,
    TtsRuleTypeProto.UNRECOGNIZED -> error("TtsRuleType must be set")
}

internal fun TtsRuleScope.toProto(): TtsRuleScopeProto {
    val b = TtsRuleScopeProto.newBuilder().setType(type.toProto())
    clientId?.let { b.clientId = it.toHexString() }
    projectId?.let { b.projectId = it.toHexString() }
    return b.build()
}

internal fun TtsRuleScopeProto.toDomain(): TtsRuleScope = TtsRuleScope(
    type = type.toDomain(),
    clientId = clientId.takeIf { it.isNotBlank() && ObjectId.isValid(it) }?.let { ObjectId(it) },
    projectId = projectId.takeIf { it.isNotBlank() && ObjectId.isValid(it) }?.let { ObjectId(it) },
)

internal fun TtsRuleScopeType.toProto(): TtsRuleScopeTypeProto = when (this) {
    TtsRuleScopeType.GLOBAL -> TtsRuleScopeTypeProto.GLOBAL
    TtsRuleScopeType.CLIENT -> TtsRuleScopeTypeProto.CLIENT
    TtsRuleScopeType.PROJECT -> TtsRuleScopeTypeProto.PROJECT
}

internal fun TtsRuleScopeTypeProto.toDomain(): TtsRuleScopeType = when (this) {
    TtsRuleScopeTypeProto.GLOBAL -> TtsRuleScopeType.GLOBAL
    TtsRuleScopeTypeProto.CLIENT -> TtsRuleScopeType.CLIENT
    TtsRuleScopeTypeProto.PROJECT -> TtsRuleScopeType.PROJECT
    TtsRuleScopeTypeProto.TTS_RULE_SCOPE_UNSPECIFIED,
    TtsRuleScopeTypeProto.UNRECOGNIZED -> error("TtsRuleScopeType must be set")
}
