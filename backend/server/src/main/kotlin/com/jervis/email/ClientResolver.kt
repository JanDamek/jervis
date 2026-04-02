package com.jervis.email

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Resolved client association for an email message.
 *
 * @param clientId Resolved client ID
 * @param confidence Resolution confidence (0.0-1.0)
 * @param source How the client was resolved (for diagnostics)
 */
data class ResolvedClient(
    val clientId: ClientId,
    val confidence: Double,
    val source: String,
)

/**
 * Client Resolution Engine for email intelligence.
 *
 * Resolves which client(s) an email belongs to using a multi-step pipeline:
 * 1. Explicit sender->client mapping (ConnectionDocument.senderClientMappings)
 * 2. Domain->client mapping (ConnectionDocument.domainClientMappings)
 * 3. Thread history (previous emails in same thread -> same client)
 * 4. Default: connection's configured client (fallback)
 *
 * Multiple clients are possible — the indexer creates a task for each.
 */
@Service
class ClientResolver(
    private val connectionService: ConnectionService,
    private val emailRepository: EmailMessageIndexRepository,
) {
    /**
     * Resolve client(s) for an email message.
     *
     * Returns list of resolved clients ordered by confidence (highest first).
     * If no specific mapping found, falls back to the email document's existing clientId.
     */
    suspend fun resolveClients(
        doc: EmailMessageIndexDocument,
        connectionId: ConnectionId,
    ): List<ResolvedClient> {
        val connection = connectionService.findById(connectionId) ?: run {
            logger.warn { "Connection not found: $connectionId, using existing clientId" }
            return listOf(
                ResolvedClient(
                    clientId = doc.clientId,
                    confidence = 0.3,
                    source = "fallback-no-connection",
                ),
            )
        }

        val results = mutableListOf<ResolvedClient>()
        val senderEmail = doc.from?.lowercase()?.trim() ?: ""
        val senderDomain = senderEmail.substringAfter("@", "").substringBefore(">").trim()

        // Step 1: Explicit sender->client mapping
        if (senderEmail.isNotBlank()) {
            resolveFromSenderMappings(senderEmail, connection)?.let { results.add(it) }
        }

        // Step 2: Domain->client mapping
        if (senderDomain.isNotBlank() && results.isEmpty()) {
            resolveFromDomainMappings(senderDomain, connection)?.let { results.add(it) }
        }

        // Step 3: Thread history — find client from previous messages in same thread
        if (results.isEmpty() && doc.threadId != null) {
            resolveFromThreadHistory(doc.threadId)?.let { results.add(it) }
        }

        // Step 4: Fallback — connection's default client
        if (results.isEmpty()) {
            results.add(
                ResolvedClient(
                    clientId = doc.clientId,
                    confidence = 0.3,
                    source = "fallback-default",
                ),
            )
        }

        logger.debug {
            "ClientResolver: email from '$senderEmail' → ${results.size} client(s): " +
                results.joinToString { "${it.clientId} (${it.source}, ${it.confidence})" }
        }

        return results.sortedByDescending { it.confidence }
    }

    private fun resolveFromSenderMappings(
        senderEmail: String,
        connection: ConnectionDocument,
    ): ResolvedClient? {
        if (connection.senderClientMappings.isEmpty()) return null

        // Exact match first
        connection.senderClientMappings[senderEmail]?.let { clientIdStr ->
            return ResolvedClient(
                clientId = ClientId.fromString(clientIdStr),
                confidence = 1.0,
                source = "sender-mapping-exact",
            )
        }

        // Pattern match (e.g., "*@company.com" or "john*")
        for ((pattern, clientIdStr) in connection.senderClientMappings) {
            if (pattern.contains("*")) {
                val regex = pattern.replace(".", "\\.").replace("*", ".*").toRegex(RegexOption.IGNORE_CASE)
                if (regex.matches(senderEmail)) {
                    return ResolvedClient(
                        clientId = ClientId.fromString(clientIdStr),
                        confidence = 0.9,
                        source = "sender-mapping-pattern",
                    )
                }
            }
        }

        return null
    }

    private fun resolveFromDomainMappings(
        senderDomain: String,
        connection: ConnectionDocument,
    ): ResolvedClient? {
        if (connection.domainClientMappings.isEmpty()) return null

        // Exact domain match
        connection.domainClientMappings[senderDomain]?.let { clientIdStr ->
            return ResolvedClient(
                clientId = ClientId.fromString(clientIdStr),
                confidence = 0.85,
                source = "domain-mapping",
            )
        }

        // Subdomain match (e.g., "mail.company.com" matches "company.com")
        for ((domain, clientIdStr) in connection.domainClientMappings) {
            if (senderDomain.endsWith(".$domain")) {
                return ResolvedClient(
                    clientId = ClientId.fromString(clientIdStr),
                    confidence = 0.75,
                    source = "domain-mapping-subdomain",
                )
            }
        }

        return null
    }

    private suspend fun resolveFromThreadHistory(threadId: String): ResolvedClient? {
        val threadMessages = mutableListOf<EmailMessageIndexDocument>()
        emailRepository.findByThreadIdOrderBySentDateAsc(threadId).collect { threadMessages.add(it) }

        if (threadMessages.isEmpty()) return null

        // Use the client from the most recent message in the thread that isn't UNCLASSIFIED
        val knownClient = threadMessages
            .filter { it.clientId != ClientId.UNCLASSIFIED }
            .maxByOrNull { it.receivedDate }

        return knownClient?.let {
            ResolvedClient(
                clientId = it.clientId,
                confidence = 0.7,
                source = "thread-history",
            )
        }
    }
}
