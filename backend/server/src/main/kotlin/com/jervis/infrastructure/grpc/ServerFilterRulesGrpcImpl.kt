package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.contracts.server.CreateFilterRuleRequest
import com.jervis.contracts.server.FilterRule
import com.jervis.contracts.server.FilterRuleList
import com.jervis.contracts.server.ListFilterRulesRequest
import com.jervis.contracts.server.RemoveFilterRuleRequest
import com.jervis.contracts.server.RemoveFilterRuleResponse
import com.jervis.contracts.server.ServerFilterRulesServiceGrpcKt
import com.jervis.dto.filtering.FilterAction
import com.jervis.dto.filtering.FilterConditionType
import com.jervis.dto.filtering.FilterSourceType
import com.jervis.dto.filtering.FilteringRule
import com.jervis.dto.filtering.FilteringRuleRequest
import com.jervis.filtering.FilteringRulesService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerFilterRulesGrpcImpl(
    private val filteringRulesService: FilteringRulesService,
) : ServerFilterRulesServiceGrpcKt.ServerFilterRulesServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun create(request: CreateFilterRuleRequest): FilterRule {
        val rule = filteringRulesService.createRule(
            FilteringRuleRequest(
                sourceType = FilterSourceType.valueOf(request.sourceType),
                conditionType = FilterConditionType.valueOf(request.conditionType),
                conditionValue = request.conditionValue,
                action = FilterAction.valueOf(request.action.ifBlank { "IGNORE" }),
                description = request.description.takeIf { it.isNotBlank() },
                clientId = request.clientId.takeIf { it.isNotBlank() },
                projectId = request.projectId.takeIf { it.isNotBlank() },
            ),
        )
        return rule.toProto()
    }

    override suspend fun list(request: ListFilterRulesRequest): FilterRuleList {
        val rules = filteringRulesService.listRules(
            clientId = request.clientId.takeIf { it.isNotBlank() }?.let { ClientId.fromString(it) },
            projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
        )
        return FilterRuleList.newBuilder()
            .addAllRules(rules.map { it.toProto() })
            .build()
    }

    override suspend fun remove(request: RemoveFilterRuleRequest): RemoveFilterRuleResponse {
        val removed = filteringRulesService.removeRule(request.ruleId)
        return RemoveFilterRuleResponse.newBuilder().setRemoved(removed).build()
    }
}

private fun FilteringRule.toProto(): FilterRule =
    FilterRule.newBuilder()
        .setId(id)
        .setScope(scope.name)
        .setSourceType(sourceType.name)
        .setConditionType(conditionType.name)
        .setConditionValue(conditionValue)
        .setAction(action.name)
        .setDescription(description ?: "")
        .setCreatedAt(createdAt)
        .setCreatedBy(createdBy)
        .setEnabled(enabled)
        .build()
