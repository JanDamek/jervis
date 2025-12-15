package com.jervis

import com.jervis.configuration.YamlPropertySourceFactory
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.configuration.prompts.ProviderCapabilitiesService
import com.jervis.configuration.properties.ArangoProperties
import com.jervis.configuration.properties.BackgroundProperties
import com.jervis.configuration.properties.CodingToolsProperties
import com.jervis.configuration.properties.DataRootProperties
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.KoogProperties
import com.jervis.configuration.properties.KtorClientProperties
import com.jervis.configuration.properties.LinkIndexingProperties
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.PollingProperties
import com.jervis.configuration.properties.PreloadOllamaProperties
import com.jervis.configuration.properties.RetryProperties
import com.jervis.configuration.properties.SecurityProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.configuration.properties.WebClientProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.PropertySource
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories

@SpringBootApplication
@EnableReactiveMongoRepositories(basePackages = ["com.jervis"])
@EnableConfigurationProperties(
    PromptsConfiguration::class,
    DataRootProperties::class,
    LinkIndexingProperties::class,
    EndpointProperties::class,
    WebClientProperties::class,
    ModelsProperties::class,
    PreloadOllamaProperties::class,
    ProviderCapabilitiesService::class,
    WeaviateProperties::class,
    RetryProperties::class,
    BackgroundProperties::class,
    SecurityProperties::class,
    ArangoProperties::class,
    KtorClientProperties::class,
    KoogProperties::class,
    CodingToolsProperties::class,
    PollingProperties::class,
)
@PropertySource(
    value = [
        "classpath:prompts.yaml", "classpath:models-config.yaml",
        "classpath:pending-tasks-goals.yaml",
    ],
    factory = YamlPropertySourceFactory::class,
)
class JervisApplication

fun main(args: Array<String>) {
    runApplication<JervisApplication>(*args)
}
