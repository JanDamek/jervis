package com.jervis.repository

import com.jervis.entity.WhisperSettingsDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface WhisperSettingsRepository : CoroutineCrudRepository<WhisperSettingsDocument, String>
