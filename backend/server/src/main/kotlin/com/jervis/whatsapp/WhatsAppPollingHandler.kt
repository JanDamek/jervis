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

        // Skip if connection is invalid or new (no session yet)
        if (connectionDocument.state in listOf(ConnectionStateEnum.INVALID, ConnectionStateEnum.NEW)) {
            logger.debug { "Skipping WhatsApp poll for '${connectionDocument.name}' — state=${connectionDocument.state}" }
            return PollingResult()
        }

        // Proactive health check: verify browser session is alive
        if (connectionDocument.state in listOf(ConnectionStateEnum.VALID, ConnectionStateEnum.DISCOVERING)) {
            try {
                val statusResponse = httpClient.get("$browserUrl/session/$browserSessionId")
                if (statusResponse.status.isSuccess()) {
                    val statusJson = json.parseToJsonElement(
                        statusResponse.body<String>()
                    ).jsonObject
                    val sessionState = statusJson["state"]?.jsonPrimitive?.content
                    if (sessionState in listOf("EXPIRED", "ERROR")) {
                        logger.warn { "WhatsApp browser session $sessionState for '${connectionDocument.name}' — marking INVALID" }
                        connectionService.save(connectionDocument.copy(state = ConnectionStateEnum.INVALID))
                        return PollingResult()
                    }
                }
            } catch (e: Exception) {
                logger.warn { "WhatsApp browser unreachable for '${connectionDocument.name}': ${e.message}" }
            }
        }

        // DISCOVERING — wait for capabilities callback
        if (connectionDocument.state == ConnectionStateEnum.DISCOVERING) {
            logger.debug { "Skipping WhatsApp poll for '${connectionDocument.name}' — still discovering" }
            return PollingResult()
        }

        logger.debug { "WhatsApp polling for '${connectionDocument.name}' (VLM scraping/$browserSessionId)" }

        // Trigger scrape on Python service (fire-and-forget).
        try {
            httpClient.post("$browserUrl/scrape/$browserSessionId/trigger")
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
