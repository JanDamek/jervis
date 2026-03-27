package com.jervis.preferences

import com.jervis.preferences.SystemConfigDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SystemConfigRepository : CoroutineCrudRepository<SystemConfigDocument, String>
