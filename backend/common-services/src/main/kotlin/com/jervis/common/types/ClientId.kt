package com.jervis.common.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.types.ObjectId

@Serializable(with = ClientIdSerializer::class)
@JvmInline
value class ClientId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): ClientId = ClientId(ObjectId(hex))

        fun generate(): ClientId = ClientId(ObjectId())
    }
}

object ClientIdSerializer : KSerializer<ClientId> {
    override val descriptor = PrimitiveSerialDescriptor("ClientId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ClientId) = encoder.encodeString(value.value.toHexString())
    override fun deserialize(decoder: Decoder): ClientId = ClientId(ObjectId(decoder.decodeString()))
}
