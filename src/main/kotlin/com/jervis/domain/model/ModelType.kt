package com.jervis.domain.model

/**
 * Enum representing model types mapped directly from properties keys.
 * Must match keys under `models:` in application properties.
 */
enum class ModelType {
    EMBEDDING_TEXT,
    EMBEDDING_CODE,
    TRANSLATION,
    RAG,
    INTERNAL,
    SPEECH,
    CHAT_INTERNAL,
    CHAT_EXTERNAL,
}
