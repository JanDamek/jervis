package com.jervis.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Chunks the Flow into batches of the specified size.
 * The last chunk may contain fewer elements if the flow completes.
 */
fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> =
    flow {
        require(size > 0) { "Chunk size must be positive" }
        val buffer = mutableListOf<T>()

        collect { item ->
            buffer.add(item)
            if (buffer.size >= size) {
                emit(buffer.toList())
                buffer.clear()
            }
        }

        if (buffer.isNotEmpty()) {
            emit(buffer.toList())
        }
    }
