package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * External microservice endpoints.
 *
 * Binds the `endpoints` prefix from application.yml.
 * K8s overrides in k8s/configmap.yaml.
 *
 * Used by [RpcClientsConfig] to create per-service RPC/HTTP clients.
 */
@ConfigurationProperties(prefix = "endpoints")
data class EndpointProperties(
    val searxng: Host,               // SearXNG web search
    val tika: Host,                  // Apache Tika document extraction (WebSocket)
    val knowledgebase: Host,         // Python KB microservice (HTTP) - read operations
    val knowledgebaseWrite: Host? = null,  // Python KB write endpoint (defaults to knowledgebase if not set)
    val orchestrator: Host = Host("http://localhost:8090"),  // Python orchestrator (HTTP)
    val correction: Host = Host("http://localhost:8000"),  // Python correction service (HTTP)
    val providers: Map<String, String> = emptyMap(),  // Git provider WebSocket URLs (github, gitlab, atlassian)
) {
    /** Simple baseUrl-only endpoint. */
    data class Host(
        val baseUrl: String,
    )
}
