package com.jervis.aider.a2a

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
 * Starts embedded HTTP JSON-RPC server on a separate port (8081) for A2A endpoint.
 */
@Configuration
class A2AServerConfig(
    private val aiderAgentExecutor: AiderAgentExecutor,
    @Value("\${a2a.server.port:8081}") private val a2aPort: Int
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
            name = "JERVIS Aider Agent",
            url = "http://localhost:$a2aPort",
            description = "Surgical code editing agent for targeted, fast file modifications using Aider CLI",
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
                    id = "code.edit.fast",
                    name = "Fast Code Editing",
                    description = "Surgical code edits in specific files using Aider CLI",
                    tags = listOf("coding", "refactoring", "bugfix")
                ),
                ai.koog.a2a.model.AgentSkill(
                    id = "code.refactor.targeted",
                    name = "Targeted Refactoring",
                    description = "Focused refactoring of code in known files",
                    tags = listOf("refactoring", "code-quality")
                )
            )
        )

        // Create A2A server
        val a2aServer = A2AServer(
            agentExecutor = aiderAgentExecutor,
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
