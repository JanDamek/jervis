package com.jervis.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date

object ObjectIdSerializer : KSerializer<ObjectId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ObjectId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ObjectId,
    ) {
        encoder.encodeString(value.toHexString())
    }

    override fun deserialize(decoder: Decoder): ObjectId =
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject) {
                val timestamp =
                    element.jsonObject["timestamp"]?.jsonPrimitive?.long
                        ?: error("Missing 'timestamp' field in ObjectId JSON")
                ObjectId(Date(timestamp * 1000))
            } else {
                ObjectId(element.jsonPrimitive.content)
            }
        } else {
            ObjectId(decoder.decodeString())
        }
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
