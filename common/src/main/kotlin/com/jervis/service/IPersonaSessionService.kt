package com.jervis.service

import com.jervis.dto.RoleSelectionRequestDto
import com.jervis.dto.RoleSelectionResponseDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.server.WebSession
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for persona session management.
 * Manages user role selection and session state.
 */
@HttpExchange("/api/session/role")
interface IPersonaSessionService {
    /**
     * Get current role from session.
     *
     * @param session WebFlux session
     * @return Role selection response with current role
     */
    @GetExchange
    suspend fun getRole(session: WebSession): RoleSelectionResponseDto

    /**
     * Set role in session.
     *
     * @param body Role selection request
     * @param session WebFlux session
     * @return Role selection response with updated role
     */
    @PostExchange
    suspend fun setRole(
        @RequestBody body: RoleSelectionRequestDto,
        session: WebSession,
    ): RoleSelectionResponseDto
}
