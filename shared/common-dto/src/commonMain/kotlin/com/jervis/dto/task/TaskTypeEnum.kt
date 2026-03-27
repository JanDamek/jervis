package com.jervis.dto.task

/**
 * Task processing pipeline types.
 *
 * @property sourceKey Wire-format identifier sent to KB service and stored in graph metadata.
 *   Must match Python [SourceType] enum values in `service-knowledgebase/app/api/models.py`.
 */
enum class TaskTypeEnum(val sourceKey: String) {
    EMAIL_PROCESSING("email"),
    BUGTRACKER_PROCESSING("jira"),
    LINK_PROCESSING("link"),
    WIKI_PROCESSING("confluence"),
    GIT_PROCESSING("git"),
    MEETING_PROCESSING("meeting"),
    CHAT_PROCESSING("teams"),
    SLACK_PROCESSING("slack"),
    DISCORD_PROCESSING("discord"),
    USER_INPUT_PROCESSING("chat"),
    USER_TASK("user_task"),
    SCHEDULED_TASK("scheduled"),
    IDLE_REVIEW("idle_review"),
    ;
}
