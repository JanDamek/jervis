package com.jervis.common.client

import com.jervis.common.dto.bugtracker.BugTrackerAddCommentRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerCommentResponse
import com.jervis.common.dto.bugtracker.BugTrackerCreateIssueRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerIssueRequest
import com.jervis.common.dto.bugtracker.BugTrackerIssueResponse
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.bugtracker.BugTrackerProjectsResponse
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.common.dto.bugtracker.BugTrackerSearchResponse
import com.jervis.common.dto.bugtracker.BugTrackerTransitionRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerUpdateIssueRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerUserDto
import com.jervis.common.dto.bugtracker.BugTrackerUserRequest
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IBugTrackerClient {
    // Read operations
    suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto
    suspend fun searchIssues(request: BugTrackerSearchRequest): BugTrackerSearchResponse
    suspend fun getIssue(request: BugTrackerIssueRequest): BugTrackerIssueResponse
    suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse

    // Write operations
    suspend fun createIssue(request: BugTrackerCreateIssueRpcRequest): BugTrackerIssueResponse
    suspend fun updateIssue(request: BugTrackerUpdateIssueRpcRequest): BugTrackerIssueResponse
    suspend fun addComment(request: BugTrackerAddCommentRpcRequest): BugTrackerCommentResponse
    suspend fun transitionIssue(request: BugTrackerTransitionRpcRequest)
}
