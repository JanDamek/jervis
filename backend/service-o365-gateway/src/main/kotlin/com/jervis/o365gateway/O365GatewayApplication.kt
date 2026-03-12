package com.jervis.o365gateway

import com.jervis.o365gateway.config.O365GatewayConfig
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.service.BrowserPoolClient
import com.jervis.o365gateway.service.GraphApiService
import com.jervis.o365gateway.service.GraphRateLimiter
import com.jervis.o365gateway.service.TokenService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class McpToolRequest(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class McpToolResponse(
    val result: String? = null,
    val error: String? = null,
)

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

    val browserPoolClient = BrowserPoolClient(httpClient, config.browserPoolUrl)
    val tokenService = TokenService(browserPoolClient)
    val rateLimiter = GraphRateLimiter(config.rateLimitPerSecond)
    val graphApi = GraphApiService(httpClient, tokenService, rateLimiter, config.graphApiBaseUrl)

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        explicitNulls = false
    }

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

            // -- Internal REST API for MCP service / orchestrator ----

            route("/api/o365") {

                // Teams chats
                get("/chats/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 20
                    try {
                        val chats = graphApi.listChats(clientId, top.coerceIn(1, 50))
                        call.respond(chats)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                get("/chats/{clientId}/{chatId}/messages") {
                    val clientId = call.parameters["clientId"]!!
                    val chatId = call.parameters["chatId"]!!
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 20
                    try {
                        val messages = graphApi.readChat(clientId, chatId, top)
                        call.respond(messages)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                post("/chats/{clientId}/{chatId}/messages") {
                    val clientId = call.parameters["clientId"]!!
                    val chatId = call.parameters["chatId"]!!
                    val body = call.receive<GraphMessageBody>()
                    try {
                        val msg = graphApi.sendChatMessage(
                            clientId, chatId,
                            body.content ?: "",
                            body.contentType ?: "text",
                        )
                        call.respond(msg)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                // Teams / Channels
                get("/teams/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    try {
                        val teams = graphApi.listTeams(clientId)
                        call.respond(teams)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                get("/teams/{clientId}/{teamId}/channels") {
                    val clientId = call.parameters["clientId"]!!
                    val teamId = call.parameters["teamId"]!!
                    try {
                        val channels = graphApi.listChannels(clientId, teamId)
                        call.respond(channels)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                get("/teams/{clientId}/{teamId}/channels/{channelId}/messages") {
                    val clientId = call.parameters["clientId"]!!
                    val teamId = call.parameters["teamId"]!!
                    val channelId = call.parameters["channelId"]!!
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 20
                    try {
                        val messages = graphApi.readChannel(clientId, teamId, channelId, top)
                        call.respond(messages)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                post("/teams/{clientId}/{teamId}/channels/{channelId}/messages") {
                    val clientId = call.parameters["clientId"]!!
                    val teamId = call.parameters["teamId"]!!
                    val channelId = call.parameters["channelId"]!!
                    val body = call.receive<GraphMessageBody>()
                    try {
                        val msg = graphApi.sendChannelMessage(
                            clientId, teamId, channelId,
                            body.content ?: "",
                        )
                        call.respond(msg)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                // Session status
                get("/session/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    try {
                        val status = browserPoolClient.getSessionStatus(clientId)
                        if (status != null) {
                            call.respond(status)
                        } else {
                            call.respond(HttpStatusCode.NotFound, McpToolResponse(error = "No session for client '$clientId'"))
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }
            }
        }
    }.start(wait = false)

    logger.info { "O365 Gateway started on ${config.host}:${config.port}" }
    Thread.currentThread().join()
}
