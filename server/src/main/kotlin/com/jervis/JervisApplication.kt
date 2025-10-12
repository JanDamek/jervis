package com.jervis

import com.jervis.configuration.YamlPropertySourceFactory
import com.jervis.configuration.prompts.PromptsConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.PropertySource
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

@SpringBootApplication
@EnableMongoRepositories(basePackages = ["com.jervis.repository.mongo"])
@EnableConfigurationProperties(PromptsConfiguration::class)
@PropertySource(
    value = ["classpath:prompts-tools.yaml", "classpath:prompts-services.yaml"],
    factory = YamlPropertySourceFactory::class,
)
@ComponentScan(
    basePackages = [
        "com.jervis.service",
        "com.jervis.repository", "com.jervis.configuration", "com.jervis.controller",
        "com.jervis.util",
    ],
)
class JervisApplication

fun main(args: Array<String>) {
    runApplication<JervisApplication>(*args)
}
