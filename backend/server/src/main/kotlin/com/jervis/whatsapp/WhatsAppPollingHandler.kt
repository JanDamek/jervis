package com.jervis.whatsapp

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.infrastructure.polling.PollingResult
import com.jervis.infrastructure.polling.handler.PollingContext
import com.jervis.infrastructure.polling.handler.PollingHandler
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Polling handler for WhatsApp (Browser Session + VLM scraping).
 *
 * Reads VLM-scraped messages from the whatsapp_scrape_messages MongoDB collection,
 * converts them to WhatsAppMessageIndexDocument, and marks them as PROCESSED.
 * WhatsAppContinuousIndexer then picks them up for KB indexing.
 */
@Component
class WhatsAppPollingHandler(
    private val repository: WhatsAppMessageIndexRepository,
    private val scrapeMessageRepository: WhatsAppScrapeMessageRepository,
    private val connectionService: ConnectionService,
    private val httpClient: HttpClient,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val cloudModelPolicyResolver: com.jervis.infrastructure.llm.CloudModelPolicyResolver,
    @Value("\${jervis.whatsapp-browser.url:http://jervis-whatsapp-browser:8091}")
    private val browserUrl: String,
) : PollingHandler {
    override val provider: ProviderEnum = ProviderEnum.WHATSAPP

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.availableCapabilities.any {
            it == ConnectionCapability.CHAT_READ
        }
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        val browserSessionId = connectionDocument.o365ClientId
        if (browserSessionId.isNullOrBlank()) {
            logger.warn { "WhatsApp connection '${connectionDocument.name}' has no browser session ID" }
            return PollingResult(errors = 1, authenticationError = true)
        }

        // NEW or INVALID: auto-init to recover session from persistent profile on PVC
        if (connectionDocument.state in listOf(ConnectionStateEnum.NEW, ConnectionStateEnum.INVALID)) {
            try {
                val healthResponse = httpClient.get("$browserUrl/health")
                if (healthResponse.status.isSuccess()) {
                    logger.info { "WhatsApp INVALID recovery: browser healthy, auto-init for '${connectionDocument.name}'" }
                    httpClient.post("$browserUrl/session/$browserSessionId/init") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"login_url":"https://web.whatsapp.com","capabilities":["CHAT_READ"]}""")
                    }
                    // Move to DISCOVERING — capabilities callback will finalize
                    connectionService.save(connectionDocument.copy(state = ConnectionStateEnum.DISCOVERING))
                }
            } catch (e: Exception) {
                logger.debug { "WhatsApp INVALID recovery failed: ${e.message}" }
            }
            return PollingResult()
        }

        // DISCOVERING — wait for capabilities callback, don't re-init
        if (connectionDocument.state == ConnectionStateEnum.DISCOVERING) {
            logger.debug { "Skipping WhatsApp poll for '${connectionDocument.name}' — DISCOVERING (waiting for callback)" }
            return PollingResult()
        }

        // Proactive health check: verify browser session is alive, auto-init if expired
        if (connectionDocument.state == ConnectionStateEnum.VALID) {
            try {
                val statusResponse = httpClient.get("$browserUrl/session/$browserSessionId")
                if (statusResponse.status.isSuccess()) {
                    val statusJson = json.parseToJsonElement(
                        statusResponse.body<String>()
                    ).jsonObject
                    val sessionState = statusJson["state"]?.jsonPrimitive?.content
                    if (sessionState in listOf("EXPIRED", "ERROR", null)) {
                        // Auto-init session — browser profile on PVC allows re-login without QR
                        logger.info { "WhatsApp session $sessionState for '${connectionDocument.name}' — auto-init" }
                        try {
                            val initResponse = httpClient.post("$browserUrl/session/$browserSessionId/init") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"login_url":"https://web.whatsapp.com","capabilities":["CHAT_READ"]}""")
                            }
                            logger.info { "WhatsApp auto-init result: ${initResponse.status}" }
                        } catch (initEx: Exception) {
                            logger.warn { "WhatsApp auto-init failed: ${initEx.message}" }
                        }
                        // Don't mark INVALID — let next poll cycle check again
                        return PollingResult()
                    }
                }
            } catch (e: Exception) {
                logger.warn { "WhatsApp browser unreachable for '${connectionDocument.name}': ${e.message}" }
            }
        }

        logger.debug { "WhatsApp polling for '${connectionDocument.name}' (DOM state-aware/$browserSessionId)" }

        // Resolve VLM tier policy for this client/project (used only when an
        // attachment is detected and the VLM is invoked to describe it).
        val clientId = context.clients.firstOrNull()?.id
        val policy = cloudModelPolicyResolver.resolve(clientId = clientId, projectId = null)
        val maxTier = policy.maxOpenRouterTier.name

        // Trigger scrape on Python service (fire-and-forget).
        try {
            httpClient.post("$browserUrl/scrape/$browserSessionId/trigger") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"max_tier":"$maxTier","processing_mode":"BACKGROUND"}""",
                )
            }
        } catch (e: Exception) {
            logger.debug { "WhatsApp scrape trigger for '${connectionDocument.name}': ${e.message}" }
        }

        // Read scraped messages from MongoDB (from previous + current scrape cycles)
        return try {
            pollFromScrapeMessages(connectionDocument, context, browserSessionId)
        } catch (e: Exception) {
            logger.error(e) { "Error polling WhatsApp scrape messages for ${connectionDocument.name}" }
            PollingResult(errors = 1)
        }
    }

    private suspend fun pollFromScrapeMessages(
        connection: ConnectionDocument,
        context: PollingContext,
        browserSessionId: String,
    ): PollingResult {
        val scrapeMessages = scrapeMessageRepository
            .findByConnectionIdAndState(browserSessionId, "NEW")
            .toList()

        if (scrapeMessages.isEmpty()) return PollingResult()

        var discovered = 0
        var created = 0
        var skipped = 0

        for (msg in scrapeMessages) {
            discovered++

            val syntheticMessageId = "wa_scrape_${msg.messageHash}"

            if (repository.existsByConnectionIdAndMessageId(connection.id, syntheticMessageId)) {
                markScrapeMessageState(msg.id, "PROCESSED")
                skipped++
                continue
            }

            val chatName = msg.chatName ?: ""
            val (targetClientId, targetProjectId) = resolveScrapeTarget(connection, context, chatName)

            if (targetClientId == null) {
                markScrapeMessageState(msg.id, "SKIPPED")
                skipped++
                continue
            }

            val body = buildString {
                append(msg.content ?: "")
                if (msg.attachmentType != null) {
                    if (isNotEmpty()) append("\n")
                    append("[${msg.attachmentType}]")
                    msg.attachmentDescription?.let { append(" $it") }
                }
            }

            val doc = WhatsAppMessageIndexDocument(
                connectionId = connection.id,
                clientId = targetClientId,
                projectId = targetProjectId,
                messageId = syntheticMessageId,
                chatName = msg.chatName,
                isGroup = msg.isGroup,
                from = msg.sender,
                body = body,
                createdDateTime = parseInstant(msg.timestamp) ?: Instant.now(),
                attachmentType = msg.attachmentType,
                attachmentDescription = msg.attachmentDescription,
            )
            repository.save(doc)
            markScrapeMessageState(msg.id, "PROCESSED")
            created++
        }

        if (created > 0) {
            logger.info { "Processed $created WhatsApp scrape messages for '${connection.name}'" }
        }

        return PollingResult(
            itemsDiscovered = discovered,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
    }

    private fun resolveScrapeTarget(
        connection: ConnectionDocument,
        context: PollingContext,
        chatName: String,
    ): Pair<ClientId?, ProjectId?> {
        for (project in context.projects) {
            val filter = context.getProjectResourceFilter(
                project.id, project.clientId, ConnectionCapability.CHAT_READ,
            )
            if (filter != null && filter.shouldIndex(chatName)) {
                return Pair(project.clientId, project.id)
            }
        }

        for (client in context.clients) {
            val filter = context.getResourceFilter(client.id, ConnectionCapability.CHAT_READ)
            if (filter != null && filter.shouldIndex(chatName)) {
                return Pair(client.id, null)
            }
        }

        return Pair(null, null)
    }

    private suspend fun markScrapeMessageState(id: org.bson.types.ObjectId, state: String) {
        try {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(id)),
                Update.update("state", state),
                WhatsAppScrapeMessageDocument::class.java,
            ).block()
        } catch (e: Exception) {
            logger.warn { "Failed to mark WhatsApp scrape message $id as $state: ${e.message}" }
        }
    }

    private fun parseInstant(timestamp: String?): Instant? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            Instant.parse(timestamp)
        } catch (_: Exception) {
            null
        }
    }
}
