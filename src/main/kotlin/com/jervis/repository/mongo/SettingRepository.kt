package com.jervis.repository.mongo

import com.jervis.entity.mongo.SettingDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for setting documents.
 */
@Repository
interface SettingMongoRepository : CoroutineCrudRepository<SettingDocument, String> {

    /**
     * Finds a setting by its key.
     */
    suspend fun findByKey(key: String): SettingDocument?
}
