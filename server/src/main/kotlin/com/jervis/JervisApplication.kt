package com.jervis

import com.jervis.configuration.YamlPropertySourceFactory
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.configuration.properties.AudioMonitoringProperties
import com.jervis.configuration.properties.DataRootProperties
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.LinkIndexingProperties
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.QdrantProperties
import com.jervis.configuration.properties.RetryProperties
import com.jervis.configuration.properties.TextChunkingProperties
import com.jervis.configuration.properties.WebClientProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.PropertySource
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

@SpringBootApplication
@EnableMongoRepositories(basePackages = ["com.jervis.repository.mongo"])
@EnableConfigurationProperties(
    PromptsConfiguration::class,
    DataRootProperties::class,
    TextChunkingProperties::class,
    LinkIndexingProperties::class,
    EndpointProperties::class,
    AudioMonitoringProperties::class,
    WebClientProperties::class,
    ModelsProperties::class,
    QdrantProperties::class,
    RetryProperties::class,
    com.jervis.configuration.properties.BackgroundProperties::class,
)
@PropertySource(
    value = ["classpath:prompts-tools.yaml", "classpath:prompts-services.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@ComponentScan(
    basePackages = [
        "com.jervis.service",
        "com.jervis.repository",
        "com.jervis.configuration",
        "com.jervis.controller",
        "com.jervis.util",
    ],
)
class JervisApplication

fun main(args: Array<String>) {
    runApplication<JervisApplication>(*args)
}
