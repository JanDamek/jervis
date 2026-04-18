package com.jervis.o365gateway.grpc

import com.google.protobuf.ByteString
import com.jervis.contracts.interceptors.ServerContextInterceptor
import com.jervis.contracts.o365_gateway.O365BytesResponse
import com.jervis.contracts.o365_gateway.O365GatewayServiceGrpcKt
import com.jervis.contracts.o365_gateway.O365Request
import com.jervis.contracts.o365_gateway.O365Response
import com.jervis.o365gateway.model.CreateEventRequest
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.model.SendMailRequest
import com.jervis.o365gateway.service.BrowserPoolClient
import com.jervis.o365gateway.service.GraphApiService
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC server for the O365 Gateway.
 *
 * Passthrough design: one RPC Request(path, method, query, body) routes to
 * the matching GraphApiService / BrowserPoolClient call. Response bodies
 * ride back as JSON text so orchestrator tools can keep the Graph-native
 * parsing they already have.
 *
 * Routing mirrors the retired Ktor /api/o365/* routes 1:1 — if you add
 * a Graph endpoint, wire it both here (gRPC) and in GraphApiService.
 */
class O365GatewayGrpcServer(
    private val graphApi: GraphApiService,
    private val browserPool: BrowserPoolClient,
    private val port: Int = 5501,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private var server: Server? = null

    fun start() {
        val interceptor = ServerContextInterceptor()
        val servicer = GatewayServicer(graphApi, browserPool, json)
        val builder = NettyServerBuilder.forPort(port)
            .maxInboundMessageSize(64 * 1024 * 1024)
            .addService(ServerInterceptors.intercept(servicer, interceptor))
            .addService(ProtoReflectionServiceV1.newInstance())
        server = builder.build().also { it.start() }
        logger.info { "O365 Gateway gRPC listening on :$port" }
    }

    fun stop() {
        runCatching {
            server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
            server?.shutdownNow()
        }
    }
}

private class GatewayServicer(
    private val graphApi: GraphApiService,
    private val browserPool: BrowserPoolClient,
    private val json: Json,
) : O365GatewayServiceGrpcKt.O365GatewayServiceCoroutineImplBase() {

    override suspend fun request(request: O365Request): O365Response {
        val path = request.path.trim().trim('/')
        val method = request.method.ifBlank { "GET" }.uppercase()
        val query = request.queryMap
        val bodyJson = request.bodyJson

        return runCatching {
            val parts = path.split('/')
            val element: JsonElement = dispatch(method, parts, query, bodyJson)
            O365Response.newBuilder()
                .setStatusCode(200)
                .setBodyJson(json.encodeToString(JsonElement.serializer(), element))
                .build()
        }.getOrElse { e ->
            logger.warn(e) { "O365_GRPC_REQ_FAIL | method=$method path=$path" }
            val status = if (e is NoSuchElementException) 404 else 502
            O365Response.newBuilder()
                .setStatusCode(status)
                .setBodyJson("""{"error":"${e.message?.replace("\"", "'")?.take(300)}"}""")
                .build()
        }
    }

    override suspend fun requestBytes(request: O365Request): O365BytesResponse {
        val path = request.path.trim().trim('/')
        val parts = path.split('/')
        return runCatching {
            when {
                // online-meetings/{clientId}/{meetingId}/transcripts/{transcriptId}/content
                parts.size == 5 && parts[0] == "online-meetings" && parts[3] == "transcripts" && parts[4] == "content" -> {
                    val vtt = graphApi.downloadTranscriptVtt(parts[1], parts[2], parts[3])
                    O365BytesResponse.newBuilder()
                        .setStatusCode(200)
                        .setBody(ByteString.copyFrom(vtt))
                        .setContentType("text/vtt")
                        .build()
                }
                else -> {
                    O365BytesResponse.newBuilder()
                        .setStatusCode(404)
                        .setContentType("text/plain")
                        .setBody(ByteString.copyFromUtf8("No binary route for $path"))
                        .build()
                }
            }
        }.getOrElse { e ->
            logger.warn(e) { "O365_GRPC_REQ_BYTES_FAIL | path=$path" }
            O365BytesResponse.newBuilder()
                .setStatusCode(502)
                .setContentType("text/plain")
                .setBody(ByteString.copyFromUtf8(e.message.orEmpty()))
                .build()
        }
    }

    private suspend fun dispatch(
        method: String,
        parts: List<String>,
        query: Map<String, String>,
        bodyJson: String,
    ): JsonElement {
        val top = query["top"]?.toIntOrNull()
        return when {
            // chats/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "chats" ->
                encode(graphApi.listChats(parts[1], (top ?: 20).coerceIn(1, 50)))

            // chats/{clientId}/{chatId}/messages
            method == "GET" && parts.size == 4 && parts[0] == "chats" && parts[3] == "messages" ->
                encode(graphApi.readChat(parts[1], parts[2], top ?: 20))
            method == "POST" && parts.size == 4 && parts[0] == "chats" && parts[3] == "messages" -> {
                val body = json.decodeFromString<GraphMessageBody>(bodyJson.ifBlank { "{}" })
                encode(graphApi.sendChatMessage(parts[1], parts[2], body.content ?: "", body.contentType ?: "text"))
            }

            // teams/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "teams" ->
                encode(graphApi.listTeams(parts[1]))
            // teams/{clientId}/{teamId}/channels
            method == "GET" && parts.size == 4 && parts[0] == "teams" && parts[3] == "channels" ->
                encode(graphApi.listChannels(parts[1], parts[2]))
            // teams/{clientId}/{teamId}/channels/{channelId}/messages
            method == "GET" && parts.size == 6 && parts[0] == "teams" && parts[3] == "channels" && parts[5] == "messages" ->
                encode(graphApi.readChannel(parts[1], parts[2], parts[4], top ?: 20))
            method == "POST" && parts.size == 6 && parts[0] == "teams" && parts[3] == "channels" && parts[5] == "messages" -> {
                val body = json.decodeFromString<GraphMessageBody>(bodyJson.ifBlank { "{}" })
                encode(graphApi.sendChannelMessage(parts[1], parts[2], parts[4], body.content ?: ""))
            }

            // mail/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "mail" ->
                encode(graphApi.listMail(parts[1], top ?: 20, query["folder"] ?: "inbox", query["filter"]))
            // mail/{clientId}/{messageId}
            method == "GET" && parts.size == 3 && parts[0] == "mail" ->
                encode(graphApi.readMail(parts[1], parts[2]))
            // mail/{clientId}/send
            method == "POST" && parts.size == 3 && parts[0] == "mail" && parts[2] == "send" -> {
                val body = json.decodeFromString<SendMailRequest>(bodyJson.ifBlank { "{}" })
                graphApi.sendMail(parts[1], body)
                json.encodeToJsonElement(mapOf("result" to "Mail sent successfully"))
            }

            // calendar/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "calendar" ->
                encode(
                    graphApi.listEvents(
                        parts[1], top ?: 20, query["startDateTime"], query["endDateTime"],
                    ),
                )
            method == "POST" && parts.size == 2 && parts[0] == "calendar" -> {
                val body = json.decodeFromString<CreateEventRequest>(bodyJson.ifBlank { "{}" })
                encode(graphApi.createEvent(parts[1], body))
            }

            // online-meetings/{clientId}/by-join-url
            method == "GET" && parts.size == 3 && parts[0] == "online-meetings" && parts[2] == "by-join-url" -> {
                val joinUrl = query["joinUrl"] ?: throw IllegalArgumentException("Missing joinUrl query param")
                val meeting = graphApi.getOnlineMeetingByJoinUrl(parts[1], joinUrl)
                    ?: throw NoSuchElementException("No meeting matches joinUrl")
                encode(meeting)
            }
            // online-meetings/{clientId}/{meetingId}
            method == "GET" && parts.size == 3 && parts[0] == "online-meetings" ->
                encode(graphApi.getOnlineMeeting(parts[1], parts[2]))
            // online-meetings/{clientId}/{meetingId}/recordings
            method == "GET" && parts.size == 4 && parts[0] == "online-meetings" && parts[3] == "recordings" ->
                encode(graphApi.listMeetingRecordings(parts[1], parts[2]))
            // online-meetings/{clientId}/{meetingId}/transcripts
            method == "GET" && parts.size == 4 && parts[0] == "online-meetings" && parts[3] == "transcripts" ->
                encode(graphApi.listMeetingTranscripts(parts[1], parts[2]))

            // drive/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "drive" ->
                encode(graphApi.listDriveItems(parts[1], query["path"] ?: "root", top ?: 50))
            // drive/{clientId}/item/{itemId}
            method == "GET" && parts.size == 4 && parts[0] == "drive" && parts[2] == "item" ->
                encode(graphApi.getDriveItem(parts[1], parts[3]))
            // drive/{clientId}/search
            method == "GET" && parts.size == 3 && parts[0] == "drive" && parts[2] == "search" -> {
                val q = query["q"] ?: throw IllegalArgumentException("Missing q query param")
                encode(graphApi.searchDrive(parts[1], q, top ?: 25))
            }

            // session/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "session" -> {
                val status = browserPool.getSessionStatus(parts[1])
                    ?: throw NoSuchElementException("No session for client '${parts[1]}'")
                encode(status)
            }

            else -> throw IllegalArgumentException("Unsupported route: $method /${parts.joinToString("/")}")
        }
    }

    private inline fun <reified T> encode(value: T): JsonElement =
        json.encodeToJsonElement(serializer(), value)
}
