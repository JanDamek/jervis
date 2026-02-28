package com.jervis.repository

import com.jervis.entity.OpenRouterSettingsDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface OpenRouterSettingsRepository : CoroutineCrudRepository<OpenRouterSettingsDocument, String>
