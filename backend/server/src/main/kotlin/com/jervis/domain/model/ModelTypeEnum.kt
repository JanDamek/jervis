package com.jervis.domain.model

/**
 * Model type categories for routing and selection.
 *
 * Keys must match entries under `models:` in models-config.yaml.
 * Used by OllamaClient to select GPU vs CPU endpoint,
 * and by ModelCandidateSelector to filter model candidates.
 */
enum class ModelTypeEnum {
    /** CPU qualification models – used by SimpleQualifierAgent for ingest. Routes to OLLAMA_QUALIFIER. */
    QUALIFIER,
    /** Embedding models – vector embeddings for RAG. Routes to OLLAMA_EMBEDDING. */
    EMBEDDING,
}
