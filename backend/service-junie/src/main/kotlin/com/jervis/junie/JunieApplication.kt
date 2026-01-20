package com.jervis.junie

import com.jervis.common.client.ICodingClient
import com.jervis.junie.service.JunieServiceImpl
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3300
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting Junie RPC Server on $host:$port" }

    val junieService: ICodingClient = JunieServiceImpl()

    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                    prettyPrint = true
                },
            )
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

                registerService<ICodingClient> { junieService }
            }
        }
    }.start(wait = false)

    logger.info { "Junie RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
