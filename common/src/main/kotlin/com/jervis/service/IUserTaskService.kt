package com.jervis.service

import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

@HttpExchange("/api/user-tasks")
interface IUserTaskService {
    @GetExchange("/active")
    suspend fun listActive(
        @RequestParam clientId: String,
    ): List<UserTaskDto>

    @GetExchange("/active-count")
    suspend fun activeCount(
        @RequestParam clientId: String,
    ): UserTaskCountDto
}
