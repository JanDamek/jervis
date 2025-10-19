package com.jervis.controller.api

import com.jervis.domain.UserRoleEnum
import com.jervis.dto.RoleSelectionRequest
import com.jervis.dto.RoleSelectionResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.WebSession

private const val SESSION_ROLE_KEY = "role"

@RestController
@RequestMapping("/api/session/role")
class PersonaSessionController {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getRole(session: WebSession): RoleSelectionResponse {
        val value = session.getAttribute<String>(SESSION_ROLE_KEY)
        return RoleSelectionResponse(role = value)
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun setRole(
        @RequestBody body: RoleSelectionRequest,
        session: WebSession,
    ): RoleSelectionResponse {
        val normalized = body.role.trim().uppercase()
        val valid =
            runCatching { UserRoleEnum.valueOf(normalized) }.getOrNull()
                ?: throw IllegalArgumentException("Unsupported role: ${body.role}")

        session.attributes[SESSION_ROLE_KEY] = valid.name
        // WebFlux sessions are saved automatically at the end of request if mutated
        return RoleSelectionResponse(role = valid.name)
    }
}
