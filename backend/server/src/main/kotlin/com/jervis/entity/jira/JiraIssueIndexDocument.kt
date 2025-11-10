package com.jervis.entity.jira

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Lightweight Jira issue index state for reconciliation and idempotency.
 */
@Document(collection = "jira_issue_index")
@CompoundIndexes(
    CompoundIndex(name = "client_issue_unique", def = "{'clientId': 1, 'issueKey': 1}", unique = true),
)
data class JiraIssueIndexDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId,
    @Indexed
    val issueKey: String,
    val projectKey: String,
    val lastSeenUpdated: Instant? = null,
    val lastEmbeddedCommentId: String? = null,
    val etag: String? = null,
    val archived: Boolean = false,
    @Indexed
    val updatedAt: Instant = Instant.now(),
)
