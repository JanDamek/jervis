package com.jervis.o365gateway

import com.jervis.o365gateway.config.O365GatewayConfig
import com.jervis.o365gateway.model.CreateEventRequest
import com.jervis.o365gateway.model.GraphMailBody
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.model.GraphRecipient
import com.jervis.o365gateway.model.GraphEmailAddressDetail
import com.jervis.o365gateway.model.SendMailMessage
import com.jervis.o365gateway.model.SendMailRequest
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

                // -- Mail (Outlook) --
                get("/mail/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 20
                    val folder = call.request.queryParameters["folder"] ?: "inbox"
                    val filter = call.request.queryParameters["filter"]
                    try {
                        val messages = graphApi.listMail(clientId, top, folder, filter)
                        call.respond(messages)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                get("/mail/{clientId}/{messageId}") {
                    val clientId = call.parameters["clientId"]!!
                    val messageId = call.parameters["messageId"]!!
                    try {
                        val message = graphApi.readMail(clientId, messageId)
                        call.respond(message)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                post("/mail/{clientId}/send") {
                    val clientId = call.parameters["clientId"]!!
                    val body = call.receive<SendMailRequest>()
                    try {
                        graphApi.sendMail(clientId, body)
                        call.respond(McpToolResponse(result = "Mail sent successfully"))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                // -- Calendar --
                get("/calendar/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 20
                    val startDateTime = call.request.queryParameters["startDateTime"]
                    val endDateTime = call.request.queryParameters["endDateTime"]
                    try {
                        val events = graphApi.listEvents(clientId, top, startDateTime, endDateTime)
                        call.respond(events)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                post("/calendar/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    val body = call.receive<CreateEventRequest>()
                    try {
                        val event = graphApi.createEvent(clientId, body)
                        call.respond(event)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                // -- OneDrive --
                get("/drive/{clientId}") {
                    val clientId = call.parameters["clientId"]!!
                    val path = call.request.queryParameters["path"] ?: "root"
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 50
                    try {
                        val items = graphApi.listDriveItems(clientId, path, top)
                        call.respond(items)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                get("/drive/{clientId}/item/{itemId}") {
                    val clientId = call.parameters["clientId"]!!
                    val itemId = call.parameters["itemId"]!!
                    try {
                        val item = graphApi.getDriveItem(clientId, itemId)
                        call.respond(item)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, McpToolResponse(error = e.message))
                    }
                }

                get("/drive/{clientId}/search") {
                    val clientId = call.parameters["clientId"]!!
                    val query = call.request.queryParameters["q"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, McpToolResponse(error = "Missing 'q' parameter"))
                    val top = call.request.queryParameters["top"]?.toIntOrNull() ?: 25
                    try {
                        val results = graphApi.searchDrive(clientId, query, top)
                        call.respond(results)
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
