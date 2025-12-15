package com.jervis.domain.model

/**
 * Enum representing model types mapped directly from properties keys.
 * Must match keys under `models:` in application properties.
 */
enum class ModelTypeEnum {
    PLANNER,
    JOERN,
    GENERIC_TEXT_MODEL,
    HEAVY_TEXT_MODEL,
    CODER,
    GENERIC_CODE_MODEL,
    HEAVY_CODE_MODEL,
    QUALIFIER,
    QUALIFIER_EMAIL_EVENTS,
    QUALIFIER_PENDING_TASKS,
    EMBEDDING,
}
