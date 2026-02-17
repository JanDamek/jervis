package com.jervis

import com.jervis.configuration.properties.ArangoProperties
import com.jervis.configuration.properties.BackgroundProperties
import com.jervis.configuration.properties.CodingToolsProperties
import com.jervis.configuration.properties.DataRootProperties
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.QualifierProperties
import com.jervis.configuration.properties.LinkIndexingProperties
import com.jervis.configuration.properties.PollingProperties
import com.jervis.configuration.properties.SecurityProperties
import com.jervis.configuration.properties.WeaviateProperties
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
)
class JervisApplication

fun main(args: Array<String>) {
    runApplication<JervisApplication>(*args)
}
