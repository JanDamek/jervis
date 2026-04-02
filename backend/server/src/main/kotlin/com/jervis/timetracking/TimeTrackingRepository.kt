package com.jervis.timetracking

import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDate

interface TimeTrackingRepository : CoroutineCrudRepository<TimeEntryDocument, ObjectId> {
    suspend fun findByUserIdAndDateBetween(userId: String, start: LocalDate, end: LocalDate): List<TimeEntryDocument>
    suspend fun findByClientIdAndDateBetween(clientId: String, start: LocalDate, end: LocalDate): List<TimeEntryDocument>
    suspend fun findByUserIdAndDate(userId: String, date: LocalDate): List<TimeEntryDocument>
    suspend fun findByClientId(clientId: String): List<TimeEntryDocument>
}
