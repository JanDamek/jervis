package com.jervis.rpc.internal

import com.jervis.timetracking.TimeEntryDocument
import com.jervis.timetracking.TimeSource
import com.jervis.timetracking.TimeTrackingService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Internal REST API for time tracking — used by Python orchestrator.
 *
 * POST /internal/time/log       — log time entry
 * GET  /internal/time/summary   — time summary for period
 * GET  /internal/time/capacity  — capacity snapshot
 */
fun Routing.installInternalTimeTrackingApi(
    timeTrackingService: TimeTrackingService,
) {
    post("/internal/time/log") {
        try {
            val body = call.receive<InternalTimeLogRequest>()
            val entry = TimeEntryDocument(
                clientId = body.clientId,
                projectId = body.projectId,
                date = body.date?.let { LocalDate.parse(it) } ?: LocalDate.now(),
                hours = body.hours,
                description = body.description ?: "",
                source = body.source?.let { TimeSource.valueOf(it) } ?: TimeSource.MANUAL,
                billable = body.billable ?: true,
            )
            val saved = timeTrackingService.logTime(entry)
            call.respondText(
                """{"status":"ok","id":"${saved.id!!.toHexString()}","hours":${saved.hours},"date":"${saved.date}"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_TIME_LOG_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/time/summary") {
        try {
            val clientId = call.request.queryParameters["client_id"]
            val fromDate = call.request.queryParameters["from"]?.let { LocalDate.parse(it) }
                ?: LocalDate.now().with(TemporalAdjusters.firstDayOfMonth())
            val toDate = call.request.queryParameters["to"]?.let { LocalDate.parse(it) }
                ?: LocalDate.now()

            val summary = timeTrackingService.getTimeSummary("jan", fromDate, toDate, clientId)
            val byClientJson = summary.byClient.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
            call.respondText(
                """{"totalHours":${summary.totalHours},"billableHours":${summary.billableHours},"byClient":{$byClientJson},"entryCount":${summary.entries.size}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_TIME_SUMMARY_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/time/capacity") {
        try {
            val snapshot = timeTrackingService.getCapacitySnapshot()
            val committedJson = snapshot.committed.values.joinToString(",") { c ->
                """{"clientId":"${c.clientId}","counterparty":"${c.counterparty}","hoursPerWeek":${c.hoursPerWeek}}"""
            }
            val actualJson = snapshot.actualThisWeek.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
            call.respondText(
                """{"totalHoursPerWeek":${snapshot.totalHoursPerWeek},"committed":[$committedJson],"actualThisWeek":{$actualJson},"availableHours":${snapshot.availableHours}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "INTERNAL_TIME_CAPACITY_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class InternalTimeLogRequest(
    val clientId: String,
    val projectId: String? = null,
    val date: String? = null,
    val hours: Double,
    val description: String? = null,
    val source: String? = null,
    val billable: Boolean? = null,
)
