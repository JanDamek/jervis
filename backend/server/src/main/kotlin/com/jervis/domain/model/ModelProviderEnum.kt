package com.jervis.domain.model

/**
 * LLM provider identifiers. Each maps to a provider entry in models-config.yaml
 * which defines endpoint URL and concurrency limits.
 *
 * Ollama instances (see docs/structures.md § "Ollama Instance Architecture"):
 * - [OLLAMA] → GPU instance (:11434) – Qwen 30B on P40, interactive queries
 * - [OLLAMA_QUALIFIER] → CPU instance (:11435) – qwen2.5 7B/14B, background ingest
 * - [OLLAMA_EMBEDDING] → CPU instance (:11435) – qwen3-embedding:8b, vector embeddings
 *
 * Used by: OllamaClient (routing), ModelConcurrencyManager (semaphores),
 *          ModelCandidateSelector (model selection), KtorClientFactory (HTTP clients).
 */
enum class ModelProviderEnum {
    /** GPU Ollama – primary interactive model (Qwen 30B on P40, port 11434). */
    OLLAMA,
    /** CPU Ollama – ingest qualification, summary, relevance check (port 11435). */
    OLLAMA_QUALIFIER,
    /** CPU Ollama – vector embeddings, shares instance with QUALIFIER (port 11435). */
    OLLAMA_EMBEDDING,
    /** LM Studio – local desktop development. */
    LM_STUDIO,
    /** OpenAI API. */
    OPENAI,
    /** Anthropic Claude API. */
    ANTHROPIC,
    /** Google Gemini API. */
    GOOGLE,
}
