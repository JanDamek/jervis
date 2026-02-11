package com.jervis.repository

import com.jervis.entity.PollingIntervalSettingsDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PollingIntervalSettingsRepository : CoroutineCrudRepository<PollingIntervalSettingsDocument, String>
