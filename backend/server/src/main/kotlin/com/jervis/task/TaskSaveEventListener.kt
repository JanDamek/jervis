package com.jervis.task

import mu.KotlinLogging
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent
import org.springframework.stereotype.Component

/**
 * Central invalidation hook for push-only UI streams (guideline #9).
 *
 * Every write to the `tasks` collection fires [AfterSaveEvent] → we push fresh
 * snapshots to the sidebar + per-task stream. One listener replaces dozens of
 * scattered `emitTaskListChanged` / `sidebarStreamService.invalidate` calls
 * across the codebase.
 *
 * The listener is non-blocking — [SidebarStreamService.invalidate] and
 * [TaskStreamService.invalidate] both dispatch to their own coroutine scope,
 * so this hook returns immediately and never slows the write path.
 */
@Component
class TaskSaveEventListener(
    private val sidebarStreamService: SidebarStreamService,
    private val taskStreamService: TaskStreamService,
) : AbstractMongoEventListener<TaskDocument>() {
    private val logger = KotlinLogging.logger {}

    override fun onAfterSave(event: AfterSaveEvent<TaskDocument>) {
        val task = event.source
        val clientId = try { task.clientId.toString() } catch (_: Exception) { null }
        val taskId = try { task.id.toString() } catch (_: Exception) { null }
        sidebarStreamService.invalidate(clientId)
        if (taskId != null) taskStreamService.invalidate(taskId)
    }

    override fun onAfterDelete(event: AfterDeleteEvent<TaskDocument>) {
        // After delete we can only invalidate the sidebar — taskId is already
        // stripped from the document. Per-task flow subscribers will see the
        // absence when they re-subscribe; the stream just stops emitting.
        sidebarStreamService.invalidate(null)
    }
}
