package com.jervis.dto

enum class TaskTypeEnum {
    EMAIL_PROCESSING,
    BUGTRACKER_PROCESSING,
    LINK_PROCESSING,
    WIKI_PROCESSING,
    GIT_PROCESSING,

    // Direct input from a user
    USER_INPUT_PROCESSING,

    USER_TASK,

    SCHEDULED_TASK,
}
