package com.jervis.repository.mongo

import com.jervis.entity.mongo.AuditLogDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AuditLogMongoRepository : CoroutineCrudRepository<AuditLogDocument, String>
