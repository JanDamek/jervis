package com.jervis.configuration

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.KotlinSerializationJsonDecoder
import org.springframework.http.codec.json.KotlinSerializationJsonEncoder
import org.springframework.web.reactive.config.WebFluxConfigurer

/**
 * JSON configuration for the application.
 * Uses Kotlinx Serialization for all JSON serialization/deserialization in REST endpoints.
 */
@Configuration
class JsonConfiguration : WebFluxConfigurer {
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

    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        val json = json()
        configurer.defaultCodecs().kotlinSerializationJsonEncoder(KotlinSerializationJsonEncoder(json))
        configurer.defaultCodecs().kotlinSerializationJsonDecoder(KotlinSerializationJsonDecoder(json))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Bean
    fun providerJson(): Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
}
