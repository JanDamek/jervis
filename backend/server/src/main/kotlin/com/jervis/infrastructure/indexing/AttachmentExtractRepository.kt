package com.jervis.infrastructure.indexing

import com.jervis.infrastructure.indexing.AttachmentExtractDocument
import com.jervis.infrastructure.indexing.ExtractionStatus
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AttachmentExtractRepository : CoroutineCrudRepository<AttachmentExtractDocument, ObjectId> {
    fun findByTaskId(taskId: String): Flow<AttachmentExtractDocument>

    fun findByTaskIdAndTikaStatus(taskId: String, tikaStatus: ExtractionStatus): Flow<AttachmentExtractDocument>

    fun findByTikaStatus(tikaStatus: ExtractionStatus): Flow<AttachmentExtractDocument>
}
