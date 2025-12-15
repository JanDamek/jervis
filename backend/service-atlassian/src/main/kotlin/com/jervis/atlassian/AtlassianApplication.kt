package com.jervis.atlassian

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AtlassianApplication

fun main(args: Array<String>) {
    runApplication<AtlassianApplication>(*args)
}
