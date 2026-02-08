package com.jervis.configuration.properties

import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.PropertySource

/**
 * Model definitions loaded from `models:` section in models-config.yaml.
 *
 * Maps [ModelTypeEnum] (e.g., EMBEDDING) to a list of available model configurations.
 * Used by ModelCandidateSelector to find the best model for a given request.
 */
@ConfigurationProperties(prefix = "")
class ModelsProperties {
    var models: Map<ModelTypeEnum, List<ModelDetail>> = emptyMap()

    data class ModelDetail(
        var provider: ModelProviderEnum? = null,  // Which Ollama instance or cloud API
        var model: String = "",                   // Model name (e.g., "qwen3-embedding:8b")
        var contextLength: Int? = null,           // Max context window in tokens
        var dimension: Int? = null,               // Embedding vector dimension (embedding models only)
        var numPredict: Int? = null,              // Max output tokens (num_predict in Ollama)
        var concurrency: Int? = null,             // Per-model semaphore limit (ModelConcurrencyManager)
    )
}
