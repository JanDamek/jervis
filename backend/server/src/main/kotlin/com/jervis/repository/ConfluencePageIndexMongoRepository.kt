package com.jervis.repository

import com.jervis.entity.confluence.ConfluencePageIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ConfluencePageIndexMongoRepository : CoroutineCrudRepository<ConfluencePageIndexDocument, ObjectId> {
    suspend fun findByConnectionDocumentIdAndPageIdAndVersionNumber(
        connectionId: ObjectId,
        pageId: String,
        versionNumber: Int,
    ): ConfluencePageIndexDocument?
}

@Repository
interface ConfluencePageIndexNewMongoRepository : CoroutineCrudRepository<ConfluencePageIndexDocument.New, ObjectId> {
    fun findAllByOrderByConfluenceUpdatedAtDesc(): Flow<ConfluencePageIndexDocument.New>
}

@Repository
interface ConfluencePageIndexIndexMongoRepository : CoroutineCrudRepository<ConfluencePageIndexDocument.Indexed, ObjectId>
