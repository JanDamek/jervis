package com.jervis.service.brain

import com.jervis.integration.bugtracker.BugTrackerComment
import com.jervis.integration.bugtracker.BugTrackerIssue
import com.jervis.integration.wiki.WikiPage

/**
 * High-level service for Jervis's internal brain (Jira + Confluence).
 *
 * Reads SystemConfig to resolve the brain connection,
 * then delegates to BugTrackerService / WikiService for actual operations.
 * No approval flow — orchestrator has unrestricted access.
 */
interface BrainWriteService {

    /** Create a Jira issue in the brain project. */
    suspend fun createIssue(
        summary: String,
        description: String? = null,
        issueType: String = "Task",
        priority: String? = null,
        labels: List<String> = emptyList(),
        epicKey: String? = null,
    ): BugTrackerIssue

    /** Update an existing brain issue. */
    suspend fun updateIssue(
        issueKey: String,
        summary: String? = null,
        description: String? = null,
        assignee: String? = null,
        priority: String? = null,
        labels: List<String>? = null,
    ): BugTrackerIssue

    /** Add a comment to a brain issue. */
    suspend fun addComment(
        issueKey: String,
        comment: String,
    ): BugTrackerComment

    /** Transition a brain issue (e.g., "To Do" → "In Progress" → "Done"). */
    suspend fun transitionIssue(
        issueKey: String,
        transitionName: String,
    )

    /** Search brain Jira issues by JQL. */
    suspend fun searchIssues(
        jql: String,
        maxResults: Int = 20,
    ): List<BugTrackerIssue>

    /** Create a Confluence page in the brain wiki. */
    suspend fun createPage(
        title: String,
        content: String,
        parentPageId: String? = null,
    ): WikiPage

    /** Update an existing brain wiki page. */
    suspend fun updatePage(
        pageId: String,
        title: String,
        content: String,
        version: Int,
    ): WikiPage

    /** Search brain Confluence pages. */
    suspend fun searchPages(
        query: String,
        maxResults: Int = 20,
    ): List<WikiPage>

    /** Check if brain is configured (both bugtracker and wiki connections set). */
    suspend fun isConfigured(): Boolean
}
