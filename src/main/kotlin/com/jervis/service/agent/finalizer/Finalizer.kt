package com.jervis.service.agent.finalizer

import com.jervis.dto.ChatResponse
import org.bson.types.ObjectId

/**
 * Finalizer produces the final user-facing response based on accumulated context and planner outcome.
 */
fun interface Finalizer {
    suspend fun finalize(contextId: ObjectId): ChatResponse
}
