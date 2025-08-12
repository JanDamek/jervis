package com.jervis.repository.mongo

import com.jervis.entity.mongo.ActionDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository interface for accessing action metadata in MongoDB.
 * Uses Kotlin Coroutines for reactive operations.
 */
@Repository
interface ActionRepository : CoroutineCrudRepository<ActionDocument, String>
