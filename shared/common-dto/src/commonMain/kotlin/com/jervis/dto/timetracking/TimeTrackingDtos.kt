package com.jervis.dto.timetracking

import kotlinx.serialization.Serializable

@Serializable
enum class TimeSourceDto {
    MANUAL,
    MEETING,
    TASK,
    CALENDAR,
}

@Serializable
data class TimeEntryDto(
    val id: String,
    val userId: String = "jan",
    val clientId: String,
    val projectId: String? = null,
    val date: String,
    val hours: Double,
    val description: String = "",
    val source: TimeSourceDto = TimeSourceDto.MANUAL,
    val billable: Boolean = true,
)

@Serializable
data class TimeEntryCreateDto(
    val clientId: String,
    val projectId: String? = null,
    val date: String? = null,
    val hours: Double,
    val description: String = "",
    val source: TimeSourceDto = TimeSourceDto.MANUAL,
    val billable: Boolean = true,
)

@Serializable
data class TimeSummaryDto(
    val totalHours: Double,
    val billableHours: Double,
    val byClient: Map<String, Double>,
    val entries: List<TimeEntryDto>,
)

@Serializable
data class CommittedCapacityDto(
    val clientId: String,
    val counterparty: String,
    val hoursPerWeek: Double,
)

@Serializable
data class CapacitySnapshotDto(
    val totalHoursPerWeek: Double = 40.0,
    val committed: List<CommittedCapacityDto>,
    val actualThisWeek: Map<String, Double>,
    val availableHours: Double,
)
