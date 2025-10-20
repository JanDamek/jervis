package com.jervis.repository.mongo

import com.jervis.entity.mongo.CoverageSnapshotDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface CoverageSnapshotMongoRepository : CoroutineCrudRepository<CoverageSnapshotDocument, ObjectId> {
    fun findByProjectKeyOrderByCreatedAtDesc(projectKey: String): Flow<CoverageSnapshotDocument>

    suspend fun findFirstByProjectKeyOrderByCreatedAtDesc(projectKey: String): CoverageSnapshotDocument?
}
