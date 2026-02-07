package com.jervis.repository

import com.jervis.entity.CodingAgentSettingsDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface CodingAgentSettingsRepository : CoroutineCrudRepository<CodingAgentSettingsDocument, String> {
    suspend fun findByAgentName(agentName: String): CodingAgentSettingsDocument?
}
