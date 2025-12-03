package com.jervis.configuration.properties

import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.PropertySource

/**
 * Models configuration properties loaded from models-config.yaml.
 */
@ConfigurationProperties(prefix = "")
class ModelsProperties {
    var models: Map<ModelTypeEnum, List<ModelDetail>> = emptyMap()

    data class ModelDetail(
        var provider: ModelProviderEnum? = null,
        var model: String = "",
        var contextLength: Int? = null,
        var dimension: Int? = null,
        var numPredict: Int? = null,
        var concurrency: Int? = null,
    )
}
