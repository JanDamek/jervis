package com.jervis.configuration

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * JSON configuration for the application.
 * Uses Kotlinx Serialization for all JSON serialization/deserialization in REST endpoints.
 */
@Configuration
class JsonConfiguration {
    @Bean
    @Primary
    @OptIn(ExperimentalSerializationApi::class)
    fun json(): Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
}
