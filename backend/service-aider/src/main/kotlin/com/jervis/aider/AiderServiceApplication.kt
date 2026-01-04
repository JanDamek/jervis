package com.jervis.aider

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AiderServiceApplication

fun main(args: Array<String>) {
    runApplication<AiderServiceApplication>(*args)
}
