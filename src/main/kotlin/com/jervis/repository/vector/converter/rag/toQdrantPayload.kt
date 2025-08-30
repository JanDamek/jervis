package com.jervis.repository.vector.converter.rag

import io.qdrant.client.grpc.JsonWithInt

/**
 * Convert Properties to Qdrant JsonWithInt.Value payload at the repository boundary
 */
fun Properties.toQdrantPayload(): Map<String, JsonWithInt.Value> = entries.mapValues { (_, v) -> anyToJsonValue(v) }.toMap()

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
