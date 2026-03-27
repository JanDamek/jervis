package com.jervis.infrastructure.llm

import com.jervis.infrastructure.llm.OpenRouterSettingsDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface OpenRouterSettingsRepository : CoroutineCrudRepository<OpenRouterSettingsDocument, String>
