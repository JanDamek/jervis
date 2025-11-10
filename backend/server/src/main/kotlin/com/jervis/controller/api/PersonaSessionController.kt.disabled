package com.jervis.controller.api

import com.jervis.domain.UserRoleEnum
import com.jervis.dto.RoleSelectionRequestDto
import com.jervis.dto.RoleSelectionResponseDto
import com.jervis.service.IPersonaSessionService
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.WebSession

private const val SESSION_ROLE_KEY = "role"

@RestController
class PersonaSessionController : IPersonaSessionService {
    override suspend fun getRole(session: WebSession): RoleSelectionResponseDto {
        val value = session.getAttribute<String>(SESSION_ROLE_KEY)
        return RoleSelectionResponseDto(role = value)
    }

    override suspend fun setRole(
        @RequestBody body: RoleSelectionRequestDto,
        session: WebSession,
    ): RoleSelectionResponseDto {
        val normalized = body.role.trim().uppercase()
        val valid =
            runCatching { UserRoleEnum.valueOf(normalized) }.getOrNull()
                ?: throw IllegalArgumentException("Unsupported role: ${body.role}")

        session.attributes[SESSION_ROLE_KEY] = valid.name
        return RoleSelectionResponseDto(role = valid.name)
    }
}
