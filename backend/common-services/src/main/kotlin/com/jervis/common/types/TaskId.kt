package com.jervis.common.types

import org.bson.types.ObjectId

@JvmInline
value class TaskId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): TaskId = TaskId(ObjectId(hex))

        fun generate(): TaskId = TaskId(ObjectId())
    }
}
