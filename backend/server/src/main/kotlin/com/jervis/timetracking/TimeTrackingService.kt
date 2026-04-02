package com.jervis.timetracking

import com.jervis.finance.ContractRepository
import com.jervis.finance.ContractStatus
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class TimeSummary(
    val totalHours: Double,
    val billableHours: Double,
    val byClient: Map<String, Double>,
    val bySource: Map<TimeSource, Double>,
    val entries: List<TimeEntryDocument>,
)

data class CapacitySnapshot(
    val totalHoursPerWeek: Double = 40.0,
    val committed: Map<String, CommittedCapacity>,
    val actualThisWeek: Map<String, Double>,
    val availableHours: Double,
)

data class CommittedCapacity(
    val clientId: String,
    val counterparty: String,
    val hoursPerWeek: Double,
)

@Service
class TimeTrackingService(
    private val timeRepo: TimeTrackingRepository,
    private val contractRepo: ContractRepository,
) {
    suspend fun logTime(entry: TimeEntryDocument): TimeEntryDocument {
        return timeRepo.save(entry)
    }

    suspend fun getEntriesForDate(userId: String, date: LocalDate): List<TimeEntryDocument> {
        return timeRepo.findByUserIdAndDate(userId, date)
    }

    suspend fun getTimeSummary(
        userId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
        clientId: String? = null,
    ): TimeSummary {
        val entries = if (clientId != null) {
            timeRepo.findByClientIdAndDateBetween(clientId, fromDate, toDate)
        } else {
            timeRepo.findByUserIdAndDateBetween(userId, fromDate, toDate)
        }

        return TimeSummary(
            totalHours = entries.sumOf { it.hours },
            billableHours = entries.filter { it.billable }.sumOf { it.hours },
            byClient = entries.groupBy { it.clientId }.mapValues { (_, v) -> v.sumOf { it.hours } },
            bySource = entries.groupBy { it.source }.mapValues { (_, v) -> v.sumOf { it.hours } },
            entries = entries,
        )
    }

    suspend fun getCapacitySnapshot(userId: String = "jan"): CapacitySnapshot {
        // Get active contracts to determine committed hours
        val contracts = contractRepo.findByStatus(ContractStatus.ACTIVE).toList()
        val committed = contracts.map { c ->
            val hoursPerWeek = when (c.rateUnit) {
                com.jervis.finance.RateUnit.HOUR -> 0.0 // hourly contracts don't commit specific hours
                com.jervis.finance.RateUnit.DAY -> 8.0 * 5 // daily rate = assume 5 days/week * 8h
                com.jervis.finance.RateUnit.MONTH -> 40.0 // full-time monthly = 40h/week
            }
            CommittedCapacity(
                clientId = c.clientId,
                counterparty = c.counterparty,
                hoursPerWeek = hoursPerWeek,
            )
        }

        // Get this week's actual hours
        val now = LocalDate.now()
        val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val thisWeekEntries = timeRepo.findByUserIdAndDateBetween(userId, weekStart, weekEnd)
        val actualThisWeek = thisWeekEntries.groupBy { it.clientId }.mapValues { (_, v) -> v.sumOf { it.hours } }

        val totalCommitted = committed.sumOf { it.hoursPerWeek }

        return CapacitySnapshot(
            totalHoursPerWeek = 40.0,
            committed = committed.associateBy { it.clientId },
            actualThisWeek = actualThisWeek,
            availableHours = 40.0 - totalCommitted,
        )
    }

    suspend fun deleteEntry(id: String): Boolean {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        timeRepo.deleteById(objectId)
        return true
    }
}
