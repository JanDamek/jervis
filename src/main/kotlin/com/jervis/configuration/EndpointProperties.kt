package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds external LLM endpoints and API keys from application.yml (and ENV) under `endpoints` prefix.
 * This replaces ad-hoc settings sources and must be the single source of truth for client configuration.
 */
@Component
@ConfigurationProperties(prefix = "endpoints")
class EndpointProperties {
    var openai: Api = Api()
    var anthropic: Api = Api()
    var ollama: Host = Host()
    var lmStudio: Host = Host()
    var searxng: Host = Host()

    class Api {
        var apiKey: String? = null
        var baseUrl: String? = null
    }

    class Host {
        var baseUrl: String? = null
    }
}
