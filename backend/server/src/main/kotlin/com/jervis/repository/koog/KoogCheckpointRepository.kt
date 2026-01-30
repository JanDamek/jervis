package com.jervis.repository.koog

import com.jervis.entity.koog.KoogCheckpointDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface KoogCheckpointRepository : CoroutineCrudRepository<KoogCheckpointDocument, String> {
    suspend fun findByCheckpointId(checkpointId: String): KoogCheckpointDocument?
}
