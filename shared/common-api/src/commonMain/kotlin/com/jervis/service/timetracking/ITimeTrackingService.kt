package com.jervis.service.timetracking

import com.jervis.dto.timetracking.CapacitySnapshotDto
import com.jervis.dto.timetracking.TimeEntryCreateDto
import com.jervis.dto.timetracking.TimeEntryDto
import com.jervis.dto.timetracking.TimeSummaryDto
import kotlinx.rpc.annotations.Rpc

/**
 * Time tracking and capacity management service.
 */
@Rpc
interface ITimeTrackingService {

    suspend fun logTime(request: TimeEntryCreateDto): TimeEntryDto

    suspend fun getTimeSummary(clientId: String? = null, fromDate: String? = null, toDate: String? = null): TimeSummaryDto

    suspend fun getCapacity(): CapacitySnapshotDto

    suspend fun getTodayEntries(): List<TimeEntryDto>

    suspend fun deleteTimeEntry(id: String): Boolean
}
