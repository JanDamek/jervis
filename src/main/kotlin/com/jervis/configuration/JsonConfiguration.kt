package com.jervis.configuration

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * JsonConfiguration class.
 * <p>
 * This class is a part of the application's core functionality.
 * It was created to provide features such as...
 * </p>
 *
 * @author damekjan
 * @version 1.0
 * @since 28.09.2025
 */
@Configuration
class JsonConfiguration {
    @Bean
    fun providerJson(): Json =
        Json {
            ignoreUnknownKeys = true // ignoruje pole, která nejsou v datové třídě
            prettyPrint = true // formátuje výstup s odsazením
            isLenient = true // povolí méně striktní JSON (např. netradiční formát čísel)
            encodeDefaults = true // zapisuje i defaultní hodnoty
            explicitNulls = false // null hodnoty se vynechají
        }
}
