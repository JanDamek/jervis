package com.jervis.gitlab

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IProviderService
import com.jervis.common.client.IRepositoryClient
import com.jervis.common.client.IWikiClient
import com.jervis.gitlab.service.GitLabApiClient
import com.jervis.gitlab.service.GitLabBugTrackerService
import com.jervis.gitlab.service.GitLabProviderService
import com.jervis.gitlab.service.GitLabRepositoryService
import com.jervis.gitlab.service.GitLabWikiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8086
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting GitLab RPC Server on $host:$port" }

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
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
            }
        }

    val gitlabApiClient = GitLabApiClient(httpClient)
    val repositoryService = GitLabRepositoryService(gitlabApiClient)
    val bugTrackerService = GitLabBugTrackerService(gitlabApiClient)
    val wikiService = GitLabWikiService(gitlabApiClient)
    val providerService = GitLabProviderService(repositoryService, bugTrackerService, wikiService)

    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)

        routing {
            get("/") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            get("/oauth2/scopes") {
                call.respondText(
                    """{"scopes":"api read_user read_api read_repository write_repository read_registry write_registry sudo admin_mode"}""",
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
                registerService<IRepositoryClient> { repositoryService }
                registerService<IBugTrackerClient> { bugTrackerService }
                registerService<IWikiClient> { wikiService }
            }
        }
    }.start(wait = false)

    logger.info { "GitLab RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
