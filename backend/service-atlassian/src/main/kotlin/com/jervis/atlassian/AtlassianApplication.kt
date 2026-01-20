package com.jervis.atlassian

import com.jervis.atlassian.service.AtlassianApiClient
import com.jervis.atlassian.service.AtlassianServiceImpl
import com.jervis.common.client.IAtlassianClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8084
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting Atlassian RPC Server on $host:$port" }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        prettyPrint = true
        coerceInputValues = true
    }

    val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(json)
        }
    }

    val atlassianApiClient = AtlassianApiClient(httpClient)
    val atlassianService: IAtlassianClient = AtlassianServiceImpl(atlassianApiClient)

    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
            json(json)
        }

        routing {
            get("/") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            rpc("/rpc") {
                rpcConfig {
                    serialization {
                        cbor()
                    }
                }

                registerService<IAtlassianClient> { atlassianService }
            }
        }
    }.start(wait = false)

    logger.info { "Atlassian RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
