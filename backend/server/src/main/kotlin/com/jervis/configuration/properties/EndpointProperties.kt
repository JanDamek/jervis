package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * External service endpoints and API keys.
 *
 * Binds the `endpoints` prefix from both application.yml (microservices)
 * and models-config.yaml (LLM providers, Ollama instances).
 *
 * Source files:
 * - application.yml → microservice URLs (tika, joern, knowledgebase, orchestrator, etc.)
 * - models-config.yaml → LLM provider URLs + API keys (ollama, anthropic, openai, google)
 * - K8s: k8s/configmap.yaml overrides for production
 *
 * Used by [KtorClientFactory] to create per-provider HTTP clients.
 */
@ConfigurationProperties(prefix = "endpoints")
data class EndpointProperties(
    val openai: Api,                 // OpenAI API
    val anthropic: Api,              // Anthropic Claude API
    val google: Api,                 // Google Gemini API
    val ollama: OllamaConfig,        // Local Ollama instances (GPU + CPU)
    val lmStudio: Host,              // LM Studio (desktop dev)
    val searxng: Host,               // SearXNG web search
    val tika: Host,                  // Apache Tika document extraction (WebSocket)
    val joern: Host,                 // Joern code analysis (WebSocket)
    val whisper: Host,               // Whisper speech-to-text (WebSocket)
    val knowledgebase: Host,         // Python KB microservice (HTTP)
    val orchestrator: Host = Host("http://localhost:8090"),  // Python orchestrator (HTTP)
    val providers: Map<String, String> = emptyMap(),  // Git provider WebSocket URLs (github, gitlab, atlassian)
) {
    /** Cloud LLM provider with API key authentication. */
    data class Api(
        val apiKey: String,
        val baseUrl: String,
    )

    /** Simple baseUrl-only endpoint (microservices, local LLM). */
    data class Host(
        val baseUrl: String,
    )

    /**
     * Ollama instance endpoints.
     *
     * - primary: GPU instance (:11434) – interactive queries, 30B model on P40
     * - qualifier: CPU instance (:11435) – ingest LLM (7B/14B models)
     * - embedding: CPU instance (:11435) – vector embeddings (shares port with qualifier)
     *
     * See docs/structures.md § "Ollama Instance Architecture".
     */
    data class OllamaConfig(
        val primary: Host,           // GPU – Qwen 30B on P40
        val qualifier: Host,         // CPU – ingest qualification + summary
        val embedding: Host,         // CPU – vector embeddings (same instance as qualifier)
    )
}
