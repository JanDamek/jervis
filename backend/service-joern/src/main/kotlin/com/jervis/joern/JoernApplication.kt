package com.jervis.joern

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class JoernApplication

fun main(args: Array<String>) {
    runApplication<JoernApplication>(*args)
}
