package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "qdrant")
class QdrantProperties {
    var host: String = "localhost"
    var port: Int = 6334
}
