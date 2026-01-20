package com.jervis.whisper

import com.jervis.common.client.IWhisperClient
import com.jervis.whisper.domain.WhisperService
import com.jervis.whisper.service.SimpleWhisperService
import com.jervis.whisper.service.WhisperServiceImpl
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
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8083
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting Whisper RPC Server on $host:$port" }

    val whisperService: WhisperService = SimpleWhisperService()
    val whisperClient: IWhisperClient = WhisperServiceImpl(whisperService)

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

                registerService<IWhisperClient> { whisperClient }
            }
        }
    }.start(wait = false)

    logger.info { "Whisper RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
