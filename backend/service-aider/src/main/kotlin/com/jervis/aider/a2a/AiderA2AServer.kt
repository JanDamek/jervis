package com.jervis.aider.a2a

import ai.koog.a2a.model.TransportProtocol
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import com.jervis.aider.service.AiderService
import io.ktor.server.application.call
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * Main entry point for Aider A2A Server.
 * Pure Kotlin implementation without Spring Boot.
 */
object AiderA2AServer {
    private val logger = KotlinLogging.logger {}

    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 3100
        logger.info { "Starting Koog A2A server on port $port..." }

        val aiderService = AiderService()
        val aiderAgentExecutor = AiderAgentExecutor(aiderService)

        val host = System.getenv("HOST") ?: "0.0.0.0"
        val publicBaseUrl = (System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:$port").trimEnd('/')
        val a2aPath = "/a2a"

        val agentCard =
            ai.koog.a2a.model.AgentCard(
                name = "jervis-coding-agent-aider",
                description = "Coding agent for JERVIS (A2A)",
                version = "0.1.0",
                protocolVersion = "0.3.0",
                url = "$publicBaseUrl$a2aPath",
                preferredTransport = TransportProtocol.JSONRPC,
                capabilities =
                    ai.koog.a2a.model.AgentCapabilities(
                        streaming = false,
                        pushNotifications = false,
                        stateTransitionHistory = false,
                    ),
                defaultInputModes = listOf("text/plain", "text/markdown"),
                defaultOutputModes = listOf("text/plain", "text/markdown"),
                skills = emptyList(),
            )

        // Create A2A server
        val a2aServer =
            A2AServer(
                agentExecutor = aiderAgentExecutor,
                agentCard = agentCard,
            )

        val transport = HttpJSONRPCServerTransport(a2aServer)
        logger.info { "Koog A2A server starting on $publicBaseUrl$a2aPath" }

        runBlocking {
            transport.start(
                engineFactory = Netty,
                port = port,
                path = a2aPath,
                wait = true,
            )
        }
    }
}
