package com.jervis.rpc

import com.jervis.configuration.properties.KtorClientProperties
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
@OptIn(ExperimentalSerializationApi::class)
class KtorRpcServer(
    private val clientRpcImpl: ClientRpcImpl,
    private val projectRpcImpl: ProjectRpcImpl,
    private val userTaskRpcImpl: UserTaskRpcImpl,
    private val ragSearchRpcImpl: RagSearchRpcImpl,
    private val taskSchedulingRpcImpl: TaskSchedulingRpcImpl,
    private val agentOrchestratorRpcImpl: AgentOrchestratorRpcImpl,
    private val errorLogRpcImpl: ErrorLogRpcImpl,
    private val connectionRpcImpl: ConnectionRpcImpl,
    private val gitConfigurationRpcImpl: GitConfigurationRpcImpl,
    private val clientProjectLinkRpcImpl: ClientProjectLinkRpcImpl,
    private val pendingTaskRpcImpl: PendingTaskRpcImpl,
    private val notificationRpcImpl: NotificationRpcImpl,
    private val properties: KtorClientProperties,
) {
    private val logger = KotlinLogging.logger {}
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @PostConstruct
    fun start() {
        val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 5500
        // Use a separate thread to not block Spring startup
        Thread {
            try {
                server =
                    embeddedServer(Netty, port = port, host = "0.0.0.0") {
                        install(WebSockets) {
                            pingPeriodMillis = 30000
                            timeoutMillis = 60000
                            maxFrameSize = Long.MAX_VALUE
                            masking = false
                        }

                        routing {
                            get("/") {
                                call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                            }
                            // Health check endpoint pro K8s
                            get("/health") {
                                call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                            }
                            get("/actuator/health") {
                                call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                            }

                            rpc("/rpc") {
                                rpcConfig {
                                    serialization {
                                        cbor()
                                    }
                                }

                                registerService<com.jervis.service.IClientService> { clientRpcImpl }
                                registerService<com.jervis.service.IProjectService> { projectRpcImpl }
                                registerService<com.jervis.service.IUserTaskService> { userTaskRpcImpl }
                                registerService<com.jervis.service.IRagSearchService> { ragSearchRpcImpl }
                                registerService<com.jervis.service.ITaskSchedulingService> { taskSchedulingRpcImpl }
                                registerService<com.jervis.service.IAgentOrchestratorService> { agentOrchestratorRpcImpl }
                                registerService<com.jervis.service.IErrorLogService> { errorLogRpcImpl }
                                registerService<com.jervis.service.IConnectionService> { connectionRpcImpl }
                                registerService<com.jervis.service.IGitConfigurationService> { gitConfigurationRpcImpl }
                                registerService<com.jervis.service.IClientProjectLinkService> { clientProjectLinkRpcImpl }
                                registerService<com.jervis.service.IPendingTaskService> { pendingTaskRpcImpl }
                                registerService<com.jervis.service.INotificationService> { notificationRpcImpl }
                            }
                        }
                    }
                server?.start(wait = true)
            } catch (e: Exception) {
                logger.error(e) { "Ktor RPC server failed to start or crashed" }
                System.exit(1)
            }
        }.start()
        logger.info { "Ktor RPC server thread started on port $port at /rpc (CBOR) with /health endpoint" }
    }

    @PreDestroy
    fun stop() {
        server?.stop(1000, 2000)
        logger.info { "Ktor RPC server stopped" }
    }
}
