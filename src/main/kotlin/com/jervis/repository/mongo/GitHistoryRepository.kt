package com.jervis.repository.mongo

import com.jervis.entity.mongo.GitHistoryDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository interface for accessing git history metadata in MongoDB.
 * Uses Kotlin Coroutines for reactive operations.
 */
@Repository
interface GitHistoryRepository : CoroutineCrudRepository<GitHistoryDocument, String>
