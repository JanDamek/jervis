package com.jervis

import com.jervis.infrastructure.config.properties.ArangoProperties
import com.jervis.infrastructure.config.properties.BackgroundProperties
import com.jervis.infrastructure.config.properties.CodingToolsProperties
import com.jervis.infrastructure.config.properties.DataRootProperties
import com.jervis.infrastructure.config.properties.EndpointProperties
import com.jervis.infrastructure.config.properties.QualifierProperties
import com.jervis.infrastructure.config.properties.LinkIndexingProperties
import com.jervis.infrastructure.config.properties.PollingProperties
import com.jervis.infrastructure.config.properties.SecurityProperties
import com.jervis.infrastructure.config.properties.WeaviateProperties
import com.jervis.infrastructure.config.properties.WhisperProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories

@SpringBootApplication
@EnableReactiveMongoRepositories(basePackages = ["com.jervis"])
@EnableConfigurationProperties(
    DataRootProperties::class,
    LinkIndexingProperties::class,
    EndpointProperties::class,
    WeaviateProperties::class,
    BackgroundProperties::class,
    SecurityProperties::class,
    ArangoProperties::class,
    QualifierProperties::class,
    CodingToolsProperties::class,
    PollingProperties::class,
    WhisperProperties::class,
)
class JervisApplication

fun main(args: Array<String>) {
    runApplication<JervisApplication>(*args)
}
