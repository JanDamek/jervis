package com.jervis.service.jira

import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Interface for detecting Jira change signals from email notifications.
 * Default implementation may be a no-op; real integration can hook into IMAP listener.
 */
interface JiraEmailSignalService {
    suspend fun hasRecentChangeSignal(
        clientId: ObjectId,
        issueKey: String,
        since: Instant,
    ): Boolean
}

@Service
class NoOpJiraEmailSignalService : JiraEmailSignalService {
    override suspend fun hasRecentChangeSignal(
        clientId: ObjectId,
        issueKey: String,
        since: Instant,
    ): Boolean = false
}
