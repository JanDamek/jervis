package com.jervis.configuration

import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "")
class ModelsProperties {
    lateinit var models: Map<ModelType, List<ModelDetail>>

    data class ModelDetail(
        var provider: ModelProvider? = null,
        var model: String = "",
        var maxTokens: Int? = null,
        var maxRequests: Int? = null,
        var concurrency: Int? = null,
        var quick: Boolean = false,
        var dimension: Int? = null,
    )
}
