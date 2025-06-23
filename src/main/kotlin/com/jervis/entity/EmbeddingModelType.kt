package com.jervis.entity

import com.jervis.entity.EmbeddingModelType.INTERNAL

/**
 * Enum representing the type of embedding model.
 */
enum class EmbeddingModelType(
    val value: String,
) {
    /**
     * Internal embedding model (Hugging Face)
     */
    INTERNAL("internal"),

    /**
     * OpenAI embedding model
     */
    OPENAI("openai"),

    /**
     * External embedding model
     */
    LM_STUDIO("lmStudio"),

    OLLAMA("ollama"),
}

fun String.fromString(): EmbeddingModelType = EmbeddingModelType.entries.find { it.value.equals(this, ignoreCase = true) } ?: INTERNAL
