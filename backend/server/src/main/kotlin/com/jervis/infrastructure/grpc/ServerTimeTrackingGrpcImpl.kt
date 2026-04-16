package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.CommittedCapacity as CommittedCapacityProto
import com.jervis.contracts.server.GetCapacityRequest
import com.jervis.contracts.server.GetCapacityResponse
import com.jervis.contracts.server.GetSummaryRequest
import com.jervis.contracts.server.GetSummaryResponse
import com.jervis.contracts.server.LogTimeRequest
import com.jervis.contracts.server.LogTimeResponse
import com.jervis.contracts.server.ServerTimeTrackingServiceGrpcKt
import com.jervis.timetracking.TimeEntryDocument
import com.jervis.timetracking.TimeSource
import com.jervis.timetracking.TimeTrackingService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@Component
class ServerTimeTrackingGrpcImpl(
    private val timeTrackingService: TimeTrackingService,
) : ServerTimeTrackingServiceGrpcKt.ServerTimeTrackingServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun logTime(request: LogTimeRequest): LogTimeResponse {
        val entry = TimeEntryDocument(
            clientId = request.clientId,
            projectId = request.projectId.takeIf { it.isNotBlank() },
            date = request.dateIso.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: LocalDate.now(),
            hours = request.hours,
            description = request.description,
            source = request.source.takeIf { it.isNotBlank() }?.let { TimeSource.valueOf(it) } ?: TimeSource.MANUAL,
            billable = if (request.billableSet) request.billable else true,
        )
        val saved = timeTrackingService.logTime(entry)
        return LogTimeResponse.newBuilder()
            .setId(saved.id?.toHexString() ?: "")
            .setHours(saved.hours)
            .setDateIso(saved.date.toString())
            .build()
    }

    override suspend fun getSummary(request: GetSummaryRequest): GetSummaryResponse {
        val fromDate = request.fromIso.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
            ?: LocalDate.now().with(TemporalAdjusters.firstDayOfMonth())
        val toDate = request.toIso.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val summary = timeTrackingService.getTimeSummary(
            userId = "jan",
            fromDate = fromDate,
            toDate = toDate,
            clientId = request.clientId.takeIf { it.isNotBlank() },
        )
        return GetSummaryResponse.newBuilder()
            .setTotalHours(summary.totalHours)
            .setBillableHours(summary.billableHours)
            .putAllByClient(summary.byClient)
            .setEntryCount(summary.entries.size)
            .build()
    }

    override suspend fun getCapacity(request: GetCapacityRequest): GetCapacityResponse {
        val snapshot = timeTrackingService.getCapacitySnapshot()
        val committed = snapshot.committed.values.map { c ->
            CommittedCapacityProto.newBuilder()
                .setClientId(c.clientId)
                .setCounterparty(c.counterparty)
                .setHoursPerWeek(c.hoursPerWeek)
                .build()
        }
        return GetCapacityResponse.newBuilder()
            .setTotalHoursPerWeek(snapshot.totalHoursPerWeek)
            .addAllCommitted(committed)
            .putAllActualThisWeek(snapshot.actualThisWeek)
            .setAvailableHours(snapshot.availableHours)
            .build()
    }
}
