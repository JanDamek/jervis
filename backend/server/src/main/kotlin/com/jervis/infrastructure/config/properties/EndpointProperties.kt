package com.jervis.infrastructure.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * External microservice endpoints (binds the `endpoints` prefix from
 * application.yml; K8s overrides in k8s/configmap.yaml).
 *
 * Only entries whose owners still resolve them via `endpoints.X` survive
 * here. Anything migrated to gRPC channels (KB / orchestrator / correction
 * / O365 gateway) reads its address from `grpc.<service>.{host,port}` —
 * see [com.jervis.infrastructure.grpc.GrpcChannels] — so the matching
 * endpoint stanzas were dropped in V6f.
 */
@ConfigurationProperties(prefix = "endpoints")
data class EndpointProperties(
    /** Python document-extraction service (HTML, PDF, DOCX, XLSX, images). External REST. */
    val documentExtraction: Host = Host("http://localhost:8080"),
    /** Git provider WebSocket URLs (github, gitlab, atlassian) used by ProviderRegistry kRPC. */
    val providers: Map<String, String> = emptyMap(),
) {
    /** Simple baseUrl-only endpoint. */
    data class Host(
        val baseUrl: String,
    )
}
