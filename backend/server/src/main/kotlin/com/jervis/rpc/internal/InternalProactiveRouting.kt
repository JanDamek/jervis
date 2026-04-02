package com.jervis.rpc.internal

import com.jervis.proactive.ProactiveScheduler
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Internal REST API for proactive communication triggers.
 *
 * POST /internal/proactive/morning-briefing   — generate morning briefing
 * POST /internal/proactive/overdue-check      — check overdue invoices
 * POST /internal/proactive/weekly-summary     — generate weekly summary
 * POST /internal/proactive/vip-alert          — send VIP email alert
 */
fun Routing.installInternalProactiveApi(
    proactiveScheduler: ProactiveScheduler,
) {
    post("/internal/proactive/morning-briefing") {
        try {
            val body = call.receive<ProactiveClientRequest>()
            val briefing = proactiveScheduler.generateMorningBriefing(body.clientId)
            call.respondText(
                """{"status":"ok","briefing":"${briefing.take(200).replace("\"", "\\\"").replace("\n", "\\n")}"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "MORNING_BRIEFING_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/proactive/overdue-check") {
        try {
            val count = proactiveScheduler.checkOverdueInvoices()
            call.respondText(
                """{"status":"ok","overdueCount":$count}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "OVERDUE_CHECK_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/proactive/weekly-summary") {
        try {
            val body = call.receive<ProactiveClientRequest>()
            val summary = proactiveScheduler.generateWeeklySummary(body.clientId)
            call.respondText(
                """{"status":"ok","summary":"${summary.take(200).replace("\"", "\\\"").replace("\n", "\\n")}"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "WEEKLY_SUMMARY_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/proactive/vip-alert") {
        try {
            val body = call.receive<VipAlertRequest>()
            proactiveScheduler.sendVipEmailAlert(body.clientId, body.senderName, body.subject)
            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "VIP_ALERT_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class ProactiveClientRequest(
    val clientId: String,
)

@Serializable
data class VipAlertRequest(
    val clientId: String,
    val senderName: String,
    val subject: String,
)
