package com.jervis.repository.vector.converter.rag

import com.jervis.domain.rag.RagDocument
import io.qdrant.client.grpc.JsonWithInt
import org.bson.types.ObjectId
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * Convert Properties to Qdrant JsonWithInt.Value payload at the repository boundary
 */
fun Properties.toQdrantPayload(): Map<String, JsonWithInt.Value> = this.entries.mapValues { (_, v) -> anyToJsonValue(v) }.toMap()

private fun anyToJsonValue(value: Any?): JsonWithInt.Value =
    when (value) {
        null -> JsonWithInt.Value.getDefaultInstance()
        is String ->
            JsonWithInt.Value
                .newBuilder()
                .setStringValue(value)
                .build()

        is Int ->
            JsonWithInt.Value
                .newBuilder()
                .setIntegerValue(value.toLong())
                .build()

        is Long ->
            JsonWithInt.Value
                .newBuilder()
                .setIntegerValue(value)
                .build()

        is Float ->
            JsonWithInt.Value
                .newBuilder()
                .setDoubleValue(value.toDouble())
                .build()

        is Double ->
            JsonWithInt.Value
                .newBuilder()
                .setDoubleValue(value)
                .build()

        is Boolean ->
            JsonWithInt.Value
                .newBuilder()
                .setBoolValue(value)
                .build()

        else ->
            JsonWithInt.Value
                .newBuilder()
                .setStringValue(value.toString())
                .build()
    }

/** Bridge: keep legacy function name but delegate to POJO conversion */
fun RagDocument.convertRagDocumentToPayload(): Map<String, JsonWithInt.Value> = this.convertRagDocumentToProperties().toQdrantPayload()

/**
 * Build a POJO Properties map from RagDocument using reflection
 */
fun RagDocument.convertRagDocumentToProperties(): Properties {
    val props = mutableMapOf<String, Any?>()
    val kClass = this::class as KClass<RagDocument>
    for (property in kClass.memberProperties) {
        // Normalize some types to primitives/strings for portability
        val normalized =
            when (val value = property.get(this)) {
                null -> null
                is ObjectId -> value.toString()
                is Enum<*> -> value.name
                else -> value
            }
        if (normalized != null) {
            props[property.name] = normalized
        }
    }
    return Properties(props)
}
