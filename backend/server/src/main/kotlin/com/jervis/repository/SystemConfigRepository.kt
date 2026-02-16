package com.jervis.repository

import com.jervis.entity.SystemConfigDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SystemConfigRepository : CoroutineCrudRepository<SystemConfigDocument, String>
