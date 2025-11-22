package com.jervis.util

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

private val defaultJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Deserialize this JSON string into type of the provided `sampleObject`.
 */
@Suppress("UNCHECKED_CAST")
@OptIn(InternalSerializationApi::class)
fun <T : Any> String.fromJsonToObject(
    sampleObject: T,
    json: Json = defaultJson,
): T {
    val kClass = sampleObject::class as KClass<T>
    val serializer: KSerializer<T> = kClass.serializer()

    return json.decodeFromString(serializer, this)
}
