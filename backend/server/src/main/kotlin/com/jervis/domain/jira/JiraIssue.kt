package com.jervis.domain.jira

import java.time.Instant

/** Minimal issue snapshot used for reconciliation and indexing policy decisions. */
data class JiraIssue(
    val key: String,
    val project: JiraProjectKey,
    val summary: String,
    val description: String? = null,
    val type: String,
    val status: String,
    val assignee: JiraAccountId?,
    val reporter: JiraAccountId?,
    val updated: Instant,
    val created: Instant,
)
