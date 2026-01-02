package com.jervis.coding.a2a

import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import io.ktor.server.netty.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

/**
 * Configuration for Koog A2A Server running alongside Spring Boot.
 *
 * Starts embedded HTTP JSON-RPC server on a separate port (8082) for A2A endpoint.
 */
@Configuration
class A2AServerConfig(
    private val openHandsAgentExecutor: OpenHandsAgentExecutor,
    @Value("\${a2a.server.port:8082}") private val a2aPort: Int
) {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private lateinit var transport: HttpJSONRPCServerTransport

    @PostConstruct
    fun startA2AServer() {
        logger.info { "Starting Koog A2A server on port $a2aPort..." }

        // Create agent card
        val agentCard = ai.koog.a2a.model.AgentCard(
            name = "JERVIS OpenHands Agent",
            url = "http://localhost:$a2aPort",
            description = "Autonomous coding agent for deep debugging, multi-step fixes, and complex analysis using OpenHands",
            version = "1.0.0",
            protocolVersion = "0.3.0",
            capabilities = ai.koog.a2a.model.AgentCapabilities(
                streaming = false,
                pushNotifications = false
            ),
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain"),
            skills = listOf(
                ai.koog.a2a.model.AgentSkill(
                    id = "debug.from.logs",
                    name = "Debug from Logs",
                    description = "Analyze logs and debug complex issues",
                    tags = listOf("debugging", "logs", "analysis")
                ),
                ai.koog.a2a.model.AgentSkill(
                    id = "repo.search.deep",
                    name = "Deep Repository Search",
                    description = "Search and analyze entire repository structure",
                    tags = listOf("search", "analysis")
                ),
                ai.koog.a2a.model.AgentSkill(
                    id = "code.solve.autonomous",
                    name = "Autonomous Problem Solving",
                    description = "Autonomously solve complex coding problems",
                    tags = listOf("coding", "autonomous", "complex")
                ),
                ai.koog.a2a.model.AgentSkill(
                    id = "run.tests",
                    name = "Run Tests",
                    description = "Execute and analyze test suites",
                    tags = listOf("testing", "verification")
                )
            )
        )

        // Create A2A server
        val a2aServer = A2AServer(
            agentExecutor = openHandsAgentExecutor,
            agentCard = agentCard
        )

        // Start transport
        transport = HttpJSONRPCServerTransport(requestHandler = a2aServer)
        runBlocking {
            transport.start(
                engineFactory = Netty,
                port = a2aPort,
                path = "/a2a",
                wait = false,
                agentCard = agentCard,
                agentCardPath = "/.well-known/agent-card.json"
            )
        }

        logger.info { "Koog A2A server started on http://localhost:$a2aPort/a2a" }
    }

    @PreDestroy
    fun stopA2AServer() {
        logger.info { "Stopping Koog A2A server..." }
        runBlocking {
            transport.stop()
        }
    }
}
