package com.jervis.module.llm

/**
 * Enum representing the type of language model provider.
 */
enum class ProviderType {
    /**
     * Provider for simple, quick tasks.
     */
    SIMPLE,

    /**
     * Provider for more complex tasks, especially for content containing programming code.
     */
    COMPLEX,

    /**
     * Provider for finalizing tasks, used for final refinement of content.
     */
    FINALIZATION,
}
