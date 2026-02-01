package com.jervis.dto

enum class TaskTypeEnum {
    EMAIL_PROCESSING,
    JIRA_PROCESSING,
    LINK_PROCESSING,
    CONFLUENCE_PROCESSING,
    GIT_PROCESSING,

    // Direct input from a user
    USER_INPUT_PROCESSING,

    USER_TASK,

    SCHEDULED_TASK,
}
