package com.jervis.domain.model

/**
 * Enum representing model types mapped directly from properties keys.
 * Must match keys under `models:` in application properties.
 */
enum class ModelType {
    EMBEDDING_TEXT,
    EMBEDDING_CODE,
    QUESTION_INTERPRETER,
    CODER,
    PLANNER,
    JOERN,
    GENERIC_TEXT_MODEL,
    HEAVY_TEXT_MODEL,
    GENERIC_CODE_MODEL,
    HEAVY_CODE_MODEL,
    QUICK,
    QUALIFIER,
}
