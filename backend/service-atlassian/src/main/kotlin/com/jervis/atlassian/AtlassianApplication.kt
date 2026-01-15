package com.jervis.atlassian

import com.jervis.atlassian.api.AtlassianController
import com.jervis.atlassian.service.AtlassianApiClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("SERVER_PORT")?.toInt() ?: 8080
    
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }
    
    val atlassianApiClient = AtlassianApiClient(httpClient)
    val controller = AtlassianController(atlassianApiClient, port)
    
    println("Starting Atlassian Service on port $port...")
    controller.startRpcServer()
}
