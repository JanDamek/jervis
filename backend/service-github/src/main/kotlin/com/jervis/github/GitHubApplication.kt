package com.jervis.github

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IProviderService
import com.jervis.common.client.IRepositoryClient
import com.jervis.common.ratelimit.DomainRateLimiter
import com.jervis.common.ratelimit.ProviderRateLimits
import com.jervis.github.service.GitHubApiClient
import com.jervis.github.service.GitHubBugTrackerService
import com.jervis.github.service.GitHubProviderService
import com.jervis.github.service.GitHubRepositoryService
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
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8085
    val host = System.getenv("HOST") ?: "0.0.0.0"
    logger.info { "Starting GitHub RPC Server on $host:$port" }

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

    val rateLimiter = DomainRateLimiter(ProviderRateLimits.GITHUB)
    val githubApiClient = GitHubApiClient(httpClient, rateLimiter)
    val repositoryService = GitHubRepositoryService(githubApiClient)
    val bugTrackerService = GitHubBugTrackerService(githubApiClient)
    val providerService = GitHubProviderService(repositoryService, bugTrackerService)

    embeddedServer(Netty, port = port, host = host) {
        install(WebSockets)

        routing {
            get("/") {
                call.respondText("""{"status":"UP"}""", io.ktor.http.ContentType.Application.Json)
            }

            get("/oauth2/scopes") {
                call.respondText(
                    """{"scopes":"repo user admin:org admin:public_key admin:repo_hook gist notifications workflow"}""",
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
            }
        }
    }.start(wait = false)

    logger.info { "GitHub RPC Server started successfully on $host:$port with kRPC endpoint at /rpc" }

    Thread.currentThread().join()
}
