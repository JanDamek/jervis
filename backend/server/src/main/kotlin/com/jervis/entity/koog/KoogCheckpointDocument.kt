package com.jervis.entity.koog

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * KoogCheckpointDocument - stores serialized state of a Koog agent.
 * Enables long-running workflows with pause/resume support.
 */
@Document(collection = "koog_checkpoints")
data class KoogCheckpointDocument(
    @Id
    val id: String, // Typically correlationId
    @Indexed
    val checkpointId: String,
    val stateJson: String, // Serialized agent state
    @Indexed
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant? = null
)
