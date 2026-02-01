package com.jervis.common.client

import com.jervis.common.dto.bugtracker.*
import kotlinx.rpc.annotations.Rpc

/**
 * Generic Bug Tracker interface.
 * Can be implemented by Jira, GitHub Issues, GitLab Issues, etc.
 */
@Rpc
interface IBugTrackerClient {
    /**
     * Get authenticated user info
     */
    suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto

    /**
     * Search for issues/tickets
     */
    suspend fun searchIssues(request: BugTrackerSearchRequest): BugTrackerSearchResponse

    /**
     * Get single issue/ticket details
     */
    suspend fun getIssue(request: BugTrackerIssueRequest): BugTrackerIssueResponse

    /**
     * List projects/repositories available
     */
    suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse

    /**
     * Download attachment from issue
     */
    suspend fun downloadAttachment(request: BugTrackerAttachmentRequest): ByteArray?
}
