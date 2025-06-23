package com.jervis.entity

/**
 * Enum representing the type of embedding model.
 */
enum class LlmModelType(
    val value: String,
) {
    /**
     * OpenAI embedding model
     */
    OPENAI("openai"),

    ANTHROPIC("anthropic"),

    /**
     * External embedding model
     */
    LM_STUDIO("lmStudio"),

    OLLAMA("ollama"),
}
