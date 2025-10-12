package com.jervis.configuration

import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "")
class ModelsProperties {
    var models: Map<ModelType, List<ModelDetail>> = emptyMap()

    data class ModelDetail(
        var provider: ModelProvider? = null,
        var model: String = "",
        var contextLength: Int? = null,
        var concurrency: Int? = null,
        var quick: Boolean = false,
        var dimension: Int? = null,
        var numPredict: Int? = null, // Ollama max tokens to predict (output only)
    )
}
