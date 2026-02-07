package com.jervis.common.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.types.ObjectId

@Serializable(with = EnvironmentIdSerializer::class)
@JvmInline
value class EnvironmentId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): EnvironmentId = EnvironmentId(ObjectId(hex))

        fun generate(): EnvironmentId = EnvironmentId(ObjectId())
    }
}

object EnvironmentIdSerializer : KSerializer<EnvironmentId> {
    override val descriptor = PrimitiveSerialDescriptor("EnvironmentId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: EnvironmentId) = encoder.encodeString(value.value.toHexString())
    override fun deserialize(decoder: Decoder): EnvironmentId = EnvironmentId(ObjectId(decoder.decodeString()))
}
