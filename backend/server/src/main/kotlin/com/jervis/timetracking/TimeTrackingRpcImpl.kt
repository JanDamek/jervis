package com.jervis.timetracking

import com.jervis.dto.timetracking.CapacitySnapshotDto
import com.jervis.dto.timetracking.CommittedCapacityDto
import com.jervis.dto.timetracking.TimeEntryCreateDto
import com.jervis.dto.timetracking.TimeEntryDto
import com.jervis.dto.timetracking.TimeSourceDto
import com.jervis.dto.timetracking.TimeSummaryDto
import com.jervis.rpc.BaseRpcImpl
import com.jervis.service.timetracking.ITimeTrackingService
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.coroutines.CoroutineContext

class TimeTrackingRpcImpl(
    private val service: TimeTrackingService,
    override val coroutineContext: CoroutineContext,
) : ITimeTrackingService, BaseRpcImpl() {

    override suspend fun logTime(request: TimeEntryCreateDto): TimeEntryDto = rpc {
        val entry = TimeEntryDocument(
            clientId = request.clientId,
            projectId = request.projectId,
            date = request.date?.let { LocalDate.parse(it) } ?: LocalDate.now(),
            hours = request.hours,
            description = request.description,
            source = TimeSource.valueOf(request.source.name),
            billable = request.billable,
        )
        service.logTime(entry).toDto()
    }

    override suspend fun getTimeSummary(clientId: String?, fromDate: String?, toDate: String?): TimeSummaryDto = rpc {
        val from = fromDate?.let { LocalDate.parse(it) }
            ?: LocalDate.now().with(TemporalAdjusters.firstDayOfMonth())
        val to = toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val summary = service.getTimeSummary("jan", from, to, clientId)
        TimeSummaryDto(
            totalHours = summary.totalHours,
            billableHours = summary.billableHours,
            byClient = summary.byClient,
            entries = summary.entries.map { it.toDto() },
        )
    }

    override suspend fun getCapacity(): CapacitySnapshotDto = rpc {
        val snapshot = service.getCapacitySnapshot()
        CapacitySnapshotDto(
            totalHoursPerWeek = snapshot.totalHoursPerWeek,
            committed = snapshot.committed.values.map { CommittedCapacityDto(it.clientId, it.counterparty, it.hoursPerWeek) },
            actualThisWeek = snapshot.actualThisWeek,
            availableHours = snapshot.availableHours,
        )
    }

    override suspend fun getTodayEntries(): List<TimeEntryDto> = rpc {
        service.getEntriesForDate("jan", LocalDate.now()).map { it.toDto() }
    }

    override suspend fun deleteTimeEntry(id: String): Boolean = rpc {
        service.deleteEntry(id)
    }

    private fun TimeEntryDocument.toDto() = TimeEntryDto(
        id = id!!.toHexString(),
        userId = userId,
        clientId = clientId,
        projectId = projectId,
        date = date.toString(),
        hours = hours,
        description = description,
        source = TimeSourceDto.valueOf(source.name),
        billable = billable,
    )
}
