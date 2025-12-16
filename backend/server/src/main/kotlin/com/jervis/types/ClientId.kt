package com.jervis.types

import org.bson.types.ObjectId

@JvmInline
value class ClientId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): ClientId = ClientId(ObjectId(hex))

        fun generate(): ClientId = ClientId(ObjectId.get())
    }
}
