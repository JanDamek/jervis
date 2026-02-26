package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.filtering.FilterAction
import com.jervis.dto.filtering.FilterConditionType
import com.jervis.dto.filtering.FilterSourceType
import com.jervis.dto.filtering.FilteringRuleRequest
import com.jervis.service.filtering.FilteringRulesService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * EPIC 10-S2: Internal REST endpoints for filtering rules, called by Python orchestrator.
 *
 * POST   /internal/filter-rules          → create rule
 * GET    /internal/filter-rules           → list rules
 * DELETE /internal/filter-rules/{ruleId}  → remove rule
 */
fun Routing.installInternalFilterRulesApi(
    filteringRulesService: FilteringRulesService,
) {
    post("/internal/filter-rules") {
        try {
            val body = json.decodeFromString<CreateFilterRuleBody>(call.receive<String>())
            val request = FilteringRuleRequest(
                sourceType = FilterSourceType.valueOf(body.sourceType),
                conditionType = FilterConditionType.valueOf(body.conditionType),
                conditionValue = body.conditionValue,
                action = FilterAction.valueOf(body.action),
                description = body.description,
                clientId = body.clientId,
                projectId = body.projectId,
            )
            val rule = filteringRulesService.createRule(request)
            call.respondText(json.encodeToString(rule), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=filter-rules (POST)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/filter-rules") {
        try {
            val clientId = call.parameters["clientId"]?.let { ClientId.fromString(it) }
            val projectId = call.parameters["projectId"]?.let { ProjectId.fromString(it) }
            val rules = filteringRulesService.listRules(clientId, projectId)
            call.respondText(json.encodeToString(rules), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=filter-rules (GET)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    delete("/internal/filter-rules/{ruleId}") {
        try {
            val ruleId = call.parameters["ruleId"] ?: ""
            val removed = filteringRulesService.removeRule(ruleId)
            if (removed) {
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            } else {
                call.respondText(
                    """{"error":"Rule not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=filter-rules (DELETE)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
private data class CreateFilterRuleBody(
    val sourceType: String,
    val conditionType: String,
    val conditionValue: String,
    val action: String = "IGNORE",
    val description: String? = null,
    val clientId: String? = null,
    val projectId: String? = null,
)
