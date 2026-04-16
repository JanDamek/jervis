package com.jervis.dto.task

import kotlinx.serialization.Serializable

/**
 * Server-pushed sidebar snapshot. Emitted on every task write in the
 * scope (`clientId` filter + active-vs-done toggle). UI collects the
 * stream and renders without additional RPC calls.
 *
 * Categorization (K vyrizeni / JERVIS pracuje / ...) stays in the UI
 * because it depends on `task.state` which is already in the DTO.
 */
@Serializable
data class SidebarSnapshot(
    val tasks: List<PendingTaskDto>,
    /** Count of DONE tasks in the same scope — used for history badge if the UI wants it. */
    val doneCount: Long = 0L,
)

/**
 * Snapshot for a single task drill-in view (breadcrumb + brief + related).
 * Pushed on task writes, progress callbacks, and related-task changes.
 */
@Serializable
data class TaskSnapshot(
    val task: PendingTaskDto,
    val relatedTasks: List<PendingTaskDto> = emptyList(),
)
