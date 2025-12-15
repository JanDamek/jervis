package com.jervis.aider

import com.jervis.aider.configuration.AiderProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AiderProperties::class)
class AiderServiceApplication

fun main(args: Array<String>) {
    runApplication<AiderServiceApplication>(*args)
}
