package com.jervis.dto

enum class TaskTypeEnum {
    EMAIL_PROCESSING,
    FILE_PROCESSING,
    JIRA_PROCESSING,
    LINK_PROCESSING,
    CONFLUENCE_PROCESSING,
    ANALYSIS_PROCESSING,

    // Direct input from a user
    USER_INPUT_PROCESSING,
    DATA_PROCESSING,

    USER_TASK,

    SCHEDULED_TASK,
}
