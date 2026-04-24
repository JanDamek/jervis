package com.jervis.scheduler

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ScheduledTriggerRepository : CoroutineCrudRepository<ScheduledTriggerDocument, String> {

    /**
     * Triggers whose `nextRunAt` has already passed. The executor sorts by
     * the timestamp ascending so oldest pending fires first — a catch-up
     * sweep after downtime still respects cron ordering.
     */
    fun findByEnabledIsTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
        cutoff: Instant,
    ): Flow<ScheduledTriggerDocument>
}
