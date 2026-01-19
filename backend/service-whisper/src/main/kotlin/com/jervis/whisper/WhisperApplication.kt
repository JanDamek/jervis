package com.jervis.whisper

import com.jervis.common.client.IWhisperClient
import com.jervis.whisper.domain.WhisperService
import com.jervis.whisper.service.SimpleWhisperService
import com.jervis.whisper.service.WhisperServiceImpl
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
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8083
    logger.info { "Starting Whisper RPC Server on port $port" }

    val whisperService: WhisperService = SimpleWhisperService()
    val whisperClient: IWhisperClient = WhisperServiceImpl(whisperService)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(WebSockets)
        install(ContentNegotiation) {
            json()
        }

        routing {
            get("/health") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            get("/actuator/health") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            rpc("/rpc") {
                registerService<IWhisperClient> { whisperClient }
            }
        }
    }.start(wait = true)
}
