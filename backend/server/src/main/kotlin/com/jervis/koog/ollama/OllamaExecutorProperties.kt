package com.jervis.koog.ollama

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("jervis.koog.ollama")
data class OllamaExecutorProperties(
    var executors: List<OllamaExecutorConfig> = emptyList(),
)

data class OllamaExecutorConfig(
    var name: String = "",
    var host: String = "",
    var defaultModel: String = "",
)
