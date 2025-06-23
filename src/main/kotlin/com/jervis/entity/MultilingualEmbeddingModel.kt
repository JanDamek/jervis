package com.jervis.entity

/**
 * Enum representing the multilingual embedding model.
 */
enum class MultilingualEmbeddingModel(val value: String) {
    /**
     * E5 Multilingual Large model
     */
    E5_LARGE("E5_MULTILINGUAL_LARGE"),

    /**
     * Multilingual MiniLM model
     */
    MINI("MULTILINGUAL_MINILM");

    companion object {
        /**
         * Get the enum value from a string.
         * @param value The string value to convert
         * @return The corresponding enum value, or E5_LARGE if not found
         */
        fun fromString(value: String): MultilingualEmbeddingModel {
            return values().find { it.value.equals(value, ignoreCase = true) } ?: E5_LARGE
        }
    }
}