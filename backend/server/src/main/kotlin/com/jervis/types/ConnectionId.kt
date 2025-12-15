package com.jervis.types

import org.bson.types.ObjectId

/**
 * Type-safe wrapper for MongoDB ObjectId representing a Connection document ID.
 *
 * Benefits:
 * - Compile-time type safety (cannot mix up ConnectionId with ClientId, ProjectId, etc.)
 * - Zero runtime overhead (@JvmInline)
 * - Clear intent in code
 */
@JvmInline
value class ConnectionId(val value: ObjectId) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): ConnectionId = ConnectionId(ObjectId(hex))
        fun generate(): ConnectionId = ConnectionId(ObjectId.get())
    }
}
