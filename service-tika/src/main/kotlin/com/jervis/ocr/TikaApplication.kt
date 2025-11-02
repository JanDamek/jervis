package com.jervis.ocr

import com.jervis.ocr.configuration.TikaProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(TikaProperties::class)
class TikaApplication

fun main(args: Array<String>) {
    runApplication<TikaApplication>(*args)
}
