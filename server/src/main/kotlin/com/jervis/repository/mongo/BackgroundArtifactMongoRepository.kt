package com.jervis.repository.mongo

import com.jervis.entity.mongo.BackgroundArtifactDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface BackgroundArtifactMongoRepository : CoroutineCrudRepository<BackgroundArtifactDocument, ObjectId> {
    fun findByTaskIdOrderByCreatedAtDesc(taskId: ObjectId): Flow<BackgroundArtifactDocument>

    fun findByTypeOrderByConfidenceDesc(type: String): Flow<BackgroundArtifactDocument>

    suspend fun existsByContentHash(contentHash: String): Boolean

    suspend fun findByContentHash(contentHash: String): BackgroundArtifactDocument?
}
