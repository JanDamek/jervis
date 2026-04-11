package com.jervis.dto.task

/**
 * High-level pipeline category for a Task.
 *
 * Source identification (email vs jira vs whatsapp vs …) is encoded in
 * [com.jervis.common.types.SourceUrn], NOT here. This enum only describes
 * HOW the task is processed:
 *
 *  - [INSTANT]   — interactive chat input from a user, processed in
 *                  FOREGROUND mode, user is waiting for the response.
 *  - [SCHEDULED] — time-triggered work (cron, scheduledAt). Fires on
 *                  schedule, then runs as autonomous SYSTEM-style work.
 *  - [SYSTEM]    — autonomous background pipeline (indexers, qualifier,
 *                  polling-driven work, idle review, …). May escalate to
 *                  state=USER_TASK when it needs the user.
 *
 * The previous 15-value enum (EMAIL_PROCESSING, BUGTRACKER_PROCESSING, …)
 * was collapsed because every value mapped to identical pipeline behavior;
 * the source-specific differences live entirely in [SourceUrn] and the
 * indexer that produced the task.
 */
enum class TaskTypeEnum {
    INSTANT,
    SCHEDULED,
    SYSTEM,
}
