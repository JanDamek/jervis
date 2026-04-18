package com.jervis.o365gateway

import com.jervis.o365gateway.config.O365GatewayConfig
import com.jervis.o365gateway.grpc.O365GatewayGrpcServer
import com.jervis.o365gateway.service.BrowserPoolClient
import com.jervis.o365gateway.service.GraphApiService
import com.jervis.o365gateway.service.GraphRateLimiter
import com.jervis.o365gateway.service.TokenService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val config = O365GatewayConfig()
    logger.info { "Starting O365 Gateway on ${config.host}:${config.port}" }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
                coerceInputValues = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    val browserPoolClient = BrowserPoolClient(httpClient)
    val tokenService = TokenService(browserPoolClient)
    val rateLimiter = GraphRateLimiter(config.rateLimitPerSecond)
    val graphApi = GraphApiService(httpClient, tokenService, rateLimiter, config.graphApiBaseUrl)

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        explicitNulls = false
    }

    // Ktor server keeps only health/liveness endpoints. All Graph-proxy
    // traffic has moved to O365GatewayService (gRPC on :5501).
    embeddedServer(Netty, port = config.port, host = config.host) {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
            json(json)
        }
        routing {
            get("/") {
                call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
            }
            get("/health") {
                call.respondText("""{"status":"UP","service":"o365-gateway"}""", ContentType.Application.Json)
            }
        }
    }.start(wait = false)

    val grpcPort = System.getenv("O365_GRPC_PORT")?.toIntOrNull() ?: 5501
    val grpcServer = O365GatewayGrpcServer(graphApi, browserPoolClient, grpcPort)
    grpcServer.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info { "Shutting down O365 Gateway gRPC server" }
            grpcServer.stop()
        },
    )

    logger.info { "O365 Gateway started (ktor=${config.host}:${config.port}, grpc=:$grpcPort)" }
    Thread.currentThread().join()
}
