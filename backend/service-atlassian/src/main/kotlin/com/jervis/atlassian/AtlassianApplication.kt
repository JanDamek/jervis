package com.jervis.atlassian

import com.jervis.atlassian.service.AtlassianApiClient
import com.jervis.atlassian.service.AtlassianProviderService
import com.jervis.atlassian.service.AtlassianServiceImpl
import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IProviderService
import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IWikiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
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
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8084
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting Atlassian RPC Server on $host:$port" }

    val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        explicitNulls = false
                        prettyPrint = true
                        coerceInputValues = true
                    },
                )
            }
        }

    val atlassianApiClient = AtlassianApiClient(httpClient)
    val atlassianService = AtlassianServiceImpl(atlassianApiClient)
    val providerService = AtlassianProviderService(atlassianService)

    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)

        routing {
            get("/") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            get("/oauth2/scopes") {
                call.respondText(
                    """{"scopes":"read:jira-user read:jira-work write:jira-work read:confluence-content.all read:confluence-content.summary read:confluence-content.permission read:confluence-props read:confluence-space.summary read:confluence-groups read:confluence-user write:confluence-content write:confluence-space search:confluence readonly:content.attachment:confluence read:space:confluence read:page:confluence read:content:confluence read:attachment:confluence read:content.metadata:confluence offline_access"}""",
                    io.ktor.http.ContentType.Application.Json,
                )
            }

            rpc("/rpc") {
                rpcConfig {
                    serialization {
                        cbor()
                    }
                }

                registerService<IProviderService> { providerService }
                registerService<IAtlassianClient> { atlassianService }
                registerService<IBugTrackerClient> { atlassianService }
                registerService<IWikiClient> { atlassianService }
            }
        }
    }.start(wait = false)

    logger.info { "Atlassian RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
