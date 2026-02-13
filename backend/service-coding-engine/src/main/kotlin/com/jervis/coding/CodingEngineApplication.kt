package com.jervis.coding

import com.jervis.coding.configuration.CodingEngineProperties
import com.jervis.coding.service.CodingEngineServiceImpl
import com.jervis.common.client.ICodingClient
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3200
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting Coding Engine (OpenHands) RPC Server on $host:$port" }

    // Load configuration from environment
    val properties =
        CodingEngineProperties(
            sandboxImage = System.getenv("OPENHANDS_SANDBOX_IMAGE") ?: "python:3.12-slim",
            maxIterations = System.getenv("OPENHANDS_MAX_ITERATIONS")?.toIntOrNull() ?: 10,
            dockerHost = System.getenv("DOCKER_HOST") ?: "tcp://localhost:2375",
            ollamaBaseUrl = System.getenv("OLLAMA_BASE_URL")
                ?: throw IllegalStateException("OLLAMA_BASE_URL must be set via ConfigMap"),
        )

    val codingService: ICodingClient = CodingEngineServiceImpl(properties)

    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)

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

                registerService<ICodingClient> { codingService }
            }
        }
    }.start(wait = false)

    logger.info { "Coding Engine (OpenHands) RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
