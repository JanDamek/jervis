package com.jervis.timetracking

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.LocalDate

enum class TimeSource {
    MANUAL,
    MEETING,
    TASK,
    CALENDAR,
}

@Document("time_entries")
@CompoundIndex(def = "{'userId': 1, 'date': -1}")
@CompoundIndex(def = "{'clientId': 1, 'date': -1}")
data class TimeEntryDocument(
    @Id val id: ObjectId? = null,
    val userId: String = "jan",
    val clientId: String,
    val projectId: String? = null,
    val date: LocalDate,
    val hours: Double,
    val description: String = "",
    val source: TimeSource = TimeSource.MANUAL,
    val billable: Boolean = true,
    val createdAt: Instant = Instant.now(),
)
