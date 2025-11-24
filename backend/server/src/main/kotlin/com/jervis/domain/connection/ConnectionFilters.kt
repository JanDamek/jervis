package com.jervis.domain.connection

import org.bson.types.ObjectId

/**
 * Connection-specific filters for Client or Project.
 *
 * Each connection can have type-specific filters that determine what data to poll/index.
 * Example: Jira connection -> filter by specific projects
 *          Email connection -> filter by specific folders
 *          Confluence connection -> filter by specific spaces
 */
data class ConnectionFilter(
    val connectionId: ObjectId,

    // Jira-specific filters
    val jiraProjects: List<String> = emptyList(), // Filter by Jira project keys (e.g., ["PROJ", "DEV"])
    val jiraBoardIds: List<String> = emptyList(), // Filter by board IDs

    // Confluence-specific filters
    val confluenceSpaces: List<String> = emptyList(), // Filter by space keys (e.g., ["DEV", "SUPPORT"])

    // Email-specific filters
    val emailFolders: List<String> = emptyList(), // Filter by folder names (e.g., ["INBOX", "Support"])
)

/**
 * Helper to get filter for specific connection.
 */
fun List<ConnectionFilter>.forConnection(connectionId: ObjectId): ConnectionFilter? {
    return firstOrNull { it.connectionId == connectionId }
}
