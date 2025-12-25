package com.jervis.types

import org.bson.types.ObjectId

/**
 * Type-safe wrapper for MongoDB ObjectId representing a PollingState document ID.
 *
 * Benefits:
 * - Compile-time type safety (cannot mix up PollingStateId with other IDs)
 * - Zero runtime overhead (@JvmInline)
 * - Clear intent in code
 */
@JvmInline
value class PollingStateId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): PollingStateId = PollingStateId(ObjectId(hex))

        fun generate(): PollingStateId = PollingStateId(ObjectId())
    }
}
