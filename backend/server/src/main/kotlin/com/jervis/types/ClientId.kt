package com.jervis.types

import org.bson.types.ObjectId

@JvmInline
value class ClientId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()
}
