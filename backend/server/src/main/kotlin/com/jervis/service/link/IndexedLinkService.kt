package com.jervis.service.link

import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.IndexedLinkDocument
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.bugtracker.internal.repository.BugTrackerIssueIndexRepository
import com.jervis.repository.IndexedLinkRepository
import com.jervis.integration.wiki.internal.repository.WikiPageIndexRepository
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.TaskId
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class IndexedLinkService(
    private val indexedLinkRepository: IndexedLinkRepository,
    private val jiraIssueIndexRepository: BugTrackerIssueIndexRepository,
    private val confluencePageIndexRepository: WikiPageIndexRepository,
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
        taskId: TaskId? = null,
        contentHash: String? = null,
    ): IndexedLinkDocument {
        val document =
            IndexedLinkDocument(
                url = url,
                clientId = clientId,
                correlationId = correlationId,
                taskId = taskId,
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
                serviceType = "WIKI",
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
                serviceType = "BUGTRACKER",
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

    /**
     * Enqueue a Jira issue for indexing from a link.
     * Creates a minimal BugTrackerIssueIndexDocument with state NEW.
     * BugTrackerContinuousIndexer will pick it up and fetch full details via API.
     *
     * @param issueKey Jira issue key (e.g., "SS-191")
     * @param connection Connection to use for fetching
     * @param clientId Client ID
     * @param projectId Optional project ID
     * @return true if enqueued, false if already exists
     */
    suspend fun enqueueJiraIssueFromLink(
        issueKey: String,
        connection: ConnectionDocument,
        clientId: ClientId,
        projectId: ProjectId? = null,
    ): Boolean {
        // Check if issue already exists (any version)
        val existing = jiraIssueIndexRepository.findByConnectionIdAndIssueKey(connection.id, issueKey)
        if (existing != null) {
            logger.info { "BugTracker issue $issueKey already exists in index (connectionId=${connection.id}), skipping" }
            return false
        }

        // Create minimal index document with state NEW
        // ContinuousIndexer will fetch full details via API
        val doc =
            BugTrackerIssueIndexDocument(
                id = ObjectId(),
                connectionId = connection.id,
                issueKey = issueKey,
                latestChangelogId = "from-link", // Placeholder - will be updated by indexer
                bugtrackerUpdatedAt = Instant.now(),
                clientId = clientId,
                projectId = projectId,
                summary = null, // Will be fetched by indexer
                indexingError = null,
                status = PollingStatusEnum.NEW,
            )

        jiraIssueIndexRepository.save(doc)
        logger.info { "✓ Enqueued BugTracker issue $issueKey for indexing (connectionId=${connection.id})" }
        return true
    }

    /**
     * Enqueue a Confluence page for indexing from a link.
     * Creates a minimal WikiPageIndexDocument with state NEW.
     * WikiContinuousIndexer will pick it up and fetch full details via API.
     *
     * @param pageId Confluence page ID (e.g., "2116157441")
     * @param connection Connection to use for fetching
     * @param clientId Client ID
     * @param projectId Optional project ID
     * @return true if enqueued, false if already exists
     */
    suspend fun enqueueConfluencePageFromLink(
        pageId: String,
        connection: ConnectionDocument,
        clientId: ClientId,
        projectId: ProjectId? = null,
    ): Boolean {
        // Check if page already exists (any version)
        val existing = confluencePageIndexRepository.findByConnectionDocumentIdAndPageId(connection.id, pageId)
        if (existing != null) {
            logger.info { "Wiki page $pageId already exists in index (connectionId=${connection.id}), skipping" }
            return false
        }

        // Create minimal index document with state NEW
        // ContinuousIndexer will fetch full details via API
        val doc =
            WikiPageIndexDocument(
                id = ObjectId(),
                connectionDocumentId = connection.id,
                pageId = pageId,
                versionNumber = null, // Will be fetched by indexer
                wikiUpdatedAt = Instant.now(),
                clientId = clientId,
                projectId = projectId,
                title = "From Link", // Placeholder - will be updated by indexer
                indexingError = null,
                status = PollingStatusEnum.NEW,
            )

        confluencePageIndexRepository.save(doc)
        logger.info { "✓ Enqueued Wiki page $pageId for indexing (connectionId=${connection.id})" }
        return true
    }
}
