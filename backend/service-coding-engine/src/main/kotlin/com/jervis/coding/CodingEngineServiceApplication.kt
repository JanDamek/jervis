package com.jervis.coding

import com.jervis.coding.configuration.CodingEngineProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(CodingEngineProperties::class)
class CodingEngineServiceApplication

fun main(args: Array<String>) {
    runApplication<CodingEngineServiceApplication>(*args)
}
