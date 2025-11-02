package com.jervis.service

import com.jervis.dto.jira.JiraBeginAuthRequestDto
import com.jervis.dto.jira.JiraBeginAuthResponseDto
import com.jervis.dto.jira.JiraBoardSelectionDto
import com.jervis.dto.jira.JiraCompleteAuthRequestDto
import com.jervis.dto.jira.JiraProjectSelectionDto
import com.jervis.dto.jira.JiraSetupStatusDto
import com.jervis.dto.jira.JiraUserSelectionDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/jira/setup")
interface IJiraSetupService {
    @GetExchange("/status")
    suspend fun getStatus(
        @RequestParam clientId: String,
    ): JiraSetupStatusDto

    @PostExchange("/begin-auth")
    suspend fun beginAuth(
        @RequestBody request: JiraBeginAuthRequestDto,
    ): JiraBeginAuthResponseDto

    @PostExchange("/complete-auth")
    suspend fun completeAuth(
        @RequestBody request: JiraCompleteAuthRequestDto,
    ): JiraSetupStatusDto

    @PutExchange("/primary-project")
    suspend fun setPrimaryProject(
        @RequestBody request: JiraProjectSelectionDto,
    ): JiraSetupStatusDto

    @PutExchange("/main-board")
    suspend fun setMainBoard(
        @RequestBody request: JiraBoardSelectionDto,
    ): JiraSetupStatusDto

    @PutExchange("/preferred-user")
    suspend fun setPreferredUser(
        @RequestBody request: JiraUserSelectionDto,
    ): JiraSetupStatusDto
}
