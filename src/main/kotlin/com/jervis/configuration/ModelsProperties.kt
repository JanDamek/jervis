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
        var maxInputTokens: Int? = null,
        var maxRequests: Int? = null,
        // Optional runtime parameters purely from properties
        var concurrency: Int? = null,
        var temperature: Double? = null,
        var topP: Double? = null,
        var timeoutMs: Long? = null,
        var backoffSeconds: Long? = null,
        /** When true, this model is considered a quick (fast) candidate for prompt routing. */
        var quick: Boolean = false,
    )
}
