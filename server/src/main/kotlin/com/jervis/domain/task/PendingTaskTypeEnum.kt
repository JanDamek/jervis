package com.jervis.domain.task

enum class PendingTaskTypeEnum {
    EMAIL_PROCESSING,
    AGENT_ANALYSIS,
    COMMIT_ANALYSIS,

    /**
     * Delayed response to user question.
     * Used when agent can't answer immediately (e.g., branch not indexed yet).
     * Task waits for condition to be met, then agent answers original question.
     */
    DELAYED_USER_RESPONSE,
}
