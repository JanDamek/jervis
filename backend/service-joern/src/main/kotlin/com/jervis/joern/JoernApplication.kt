package com.jervis.joern

import com.jervis.common.client.IJoernClient
import com.jervis.joern.domain.JoernRunner
import com.jervis.joern.service.CliJoernRunner
import com.jervis.joern.service.JoernServiceImpl
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8082
    logger.info { "Starting Joern RPC Server on port $port" }

    val joernRunner: JoernRunner = CliJoernRunner()
    val joernService: IJoernClient = JoernServiceImpl(joernRunner)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
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
            get("/health") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            get("/actuator/health") {
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
    }.start(wait = true)
}
