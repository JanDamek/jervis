package com.jervis.joern

import com.jervis.common.client.IJoernClient
import com.jervis.joern.domain.JoernRunner
import com.jervis.joern.service.CliJoernRunner
import com.jervis.joern.service.JoernServiceImpl
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
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8082
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting Joern RPC Server on $host:$port" }

    val joernRunner: JoernRunner = CliJoernRunner()
    val joernService: IJoernClient = JoernServiceImpl(joernRunner)

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

                registerService<IJoernClient> { joernService }
            }
        }
    }.start(wait = false)

    logger.info { "Joern RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    // Keep the application running
    Thread.currentThread().join()
}
