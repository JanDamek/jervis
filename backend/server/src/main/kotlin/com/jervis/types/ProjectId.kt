package com.jervis.types

import org.bson.types.ObjectId

@JvmInline
value class ProjectId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): ProjectId = ProjectId(ObjectId(hex))

        fun generate(): ProjectId = ProjectId(ObjectId())
    }
}
