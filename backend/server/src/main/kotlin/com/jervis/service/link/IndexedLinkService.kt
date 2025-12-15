package com.jervis.service.link

import com.jervis.entity.IndexedLinkDocument
import com.jervis.repository.IndexedLinkMongoRepository
import com.jervis.types.ClientId
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class IndexedLinkService(
    private val indexedLinkRepository: IndexedLinkMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Check if a link has already been indexed for a specific client.
     */
    suspend fun isLinkIndexed(
        url: String,
        clientId: ClientId,
    ): Boolean {
        val existing = indexedLinkRepository.findByUrlAndClientId(url, clientId)
        return existing != null
    }

    /**
     * Get indexed link document for a URL and client.
     */
    suspend fun getIndexedLink(
        url: String,
        clientId: ClientId,
    ): IndexedLinkDocument? = indexedLinkRepository.findByUrlAndClientId(url, clientId)

    /**
     * Record a link as indexed after successful processing.
     */
    suspend fun recordIndexedLink(
        url: String,
        clientId: ClientId,
        correlationId: String? = null,
        pendingTaskId: ObjectId? = null,
        contentHash: String? = null,
    ): IndexedLinkDocument {
        val document =
            IndexedLinkDocument(
                url = url,
                clientId = clientId,
                correlationId = correlationId,
                pendingTaskId = pendingTaskId,
                contentHash = contentHash,
            )
        val saved = indexedLinkRepository.save(document)
        logger.info { "Recorded indexed link: $url for client $clientId" }
        return saved
    }

    /**
     * Detect if URL belongs to a known service (Confluence, Jira, etc.)
     * Returns service type and extracted identifiers.
     */
    data class KnownServiceDetection(
        val serviceType: String, // "CONFLUENCE", "JIRA", "GITHUB", etc.
        val identifier: String, // Page ID, Issue key, PR number, etc.
        val domain: String, // Base domain
    )

    fun detectKnownService(url: String): KnownServiceDetection? {
        val lowerUrl = url.lowercase()

        // Confluence detection
        if (lowerUrl.contains("confluence") || lowerUrl.contains("/wiki/")) {
            val pageIdMatch = Regex("""pages/(\d+)""").find(url)
            val spaceKeyMatch = Regex("""display/([A-Z0-9]+)""").find(url)

            val identifier = pageIdMatch?.groupValues?.get(1) ?: spaceKeyMatch?.groupValues?.get(1) ?: "unknown"
            val domain = extractDomain(url)

            return KnownServiceDetection(
                serviceType = "CONFLUENCE",
                identifier = identifier,
                domain = domain,
            )
        }

        // Jira detection
        if (lowerUrl.contains("jira") || lowerUrl.contains("/browse/")) {
            val issueKeyMatch = Regex("""/browse/([A-Z]+-\d+)""").find(url)
            val identifier = issueKeyMatch?.groupValues?.get(1) ?: "unknown"
            val domain = extractDomain(url)

            return KnownServiceDetection(
                serviceType = "JIRA",
                identifier = identifier,
                domain = domain,
            )
        }

        // GitHub detection
        if (lowerUrl.contains("github.com")) {
            val repoMatch = Regex("""github\.com/([^/]+/[^/]+)""").find(url)
            val identifier = repoMatch?.groupValues?.get(1) ?: "unknown"

            return KnownServiceDetection(
                serviceType = "GITHUB",
                identifier = identifier,
                domain = "github.com",
            )
        }

        // Add more services as needed
        return null
    }

    private fun extractDomain(url: String): String {
        val match = Regex("""https?://([^/]+)""").find(url)
        return match?.groupValues?.get(1) ?: url
    }
}
