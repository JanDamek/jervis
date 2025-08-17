package com.jervis.repository.vector.converter

import com.jervis.domain.rag.RagDocument
import io.qdrant.client.grpc.JsonWithInt
import org.bson.types.ObjectId
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

// POJO for properties used across the project instead of JsonWithInt.Value
data class Properties(val entries: Map<String, Any?>)

/** Bridge: keep legacy function name but delegate to POJO conversion */
internal fun RagDocument.convertRagDocumentToPayload(): Map<String, JsonWithInt.Value> =
    this.convertRagDocumentToProperties().toQdrantPayload()

/**
 * Build a POJO Properties map from RagDocument using reflection
 */
internal fun RagDocument.convertRagDocumentToProperties(): Properties {
    val props = mutableMapOf<String, Any?>()
    val kClass = this::class as KClass<RagDocument>
    for (property in kClass.memberProperties) {
        val value = property.get(this)
        // Normalize some types to primitives/strings for portability
        val normalized = when (value) {
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

/**
 * Convert Properties POJO back to RagDocument using reflection
 */
internal fun Properties.convertToRagDocument(): RagDocument {
    val kClass = RagDocument::class
    val constructor = kClass.primaryConstructor
        ?: throw IllegalStateException("RagDocument must have a primary constructor")

    val args = mutableMapOf<KParameter, Any?>()
    for (parameter in constructor.parameters) {
        val paramName = parameter.name ?: continue
        val value = entries[paramName]
        if (value != null) {
            val convertedValue = convertAnyToType(value, parameter.type.classifier as? KClass<*>)
            if (convertedValue != null) {
                args[parameter] = convertedValue
            }
        } else if (!parameter.isOptional && !parameter.type.isMarkedNullable) {
            args[parameter] = getDefaultValueForType(parameter.type.classifier as? KClass<*>)
        }
    }
    return constructor.callBy(args)
}

/**
 * Convert Properties to Qdrant JsonWithInt.Value payload at repository boundary
 */
internal fun Properties.toQdrantPayload(): Map<String, JsonWithInt.Value> =
    entries.mapValues { (_, v) -> anyToJsonValue(v) }.toMap()

/**
 * Convert Qdrant payload back to Properties
 */
internal fun Map<String, JsonWithInt.Value>.toProperties(): Properties =
    Properties(this.mapValues { (_, v) -> jsonValueToAny(v) })

private fun anyToJsonValue(value: Any?): JsonWithInt.Value = when (value) {
    null -> JsonWithInt.Value.getDefaultInstance()
    is String -> JsonWithInt.Value.newBuilder().setStringValue(value).build()
    is Int -> JsonWithInt.Value.newBuilder().setIntegerValue(value.toLong()).build()
    is Long -> JsonWithInt.Value.newBuilder().setIntegerValue(value).build()
    is Float -> JsonWithInt.Value.newBuilder().setDoubleValue(value.toDouble()).build()
    is Double -> JsonWithInt.Value.newBuilder().setDoubleValue(value).build()
    is Boolean -> JsonWithInt.Value.newBuilder().setBoolValue(value).build()
    else -> JsonWithInt.Value.newBuilder().setStringValue(value.toString()).build()
}

private fun jsonValueToAny(jsonValue: JsonWithInt.Value): Any? = when {
    jsonValue.hasStringValue() -> jsonValue.stringValue
    jsonValue.hasIntegerValue() -> jsonValue.integerValue
    jsonValue.hasDoubleValue() -> jsonValue.doubleValue
    jsonValue.hasBoolValue() -> jsonValue.boolValue
    else -> null
}

private fun convertAnyToType(value: Any?, targetClass: KClass<*>?): Any? {
    if (value == null) return null
    return when {
        targetClass == null -> value
        targetClass == String::class -> value.toString()
        targetClass == Int::class -> when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        targetClass == Long::class -> when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        targetClass == Float::class -> when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }
        targetClass == Double::class -> when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        targetClass == Boolean::class -> when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            is Number -> value.toInt() != 0
            else -> null
        }
        targetClass.isSubclassOf(Enum::class) -> when (value) {
            is String -> try {
                @Suppress("UNCHECKED_CAST")
                java.lang.Enum.valueOf(targetClass.java as Class<out Enum<*>>, value)
            } catch (_: Exception) { null }
            else -> null
        }
        else -> value
    }
}

private fun getDefaultValueForType(targetClass: KClass<*>?): Any? =
    when (targetClass) {
        String::class -> ""
        Int::class -> 0
        Long::class -> 0L
        Float::class -> 0.0f
        Double::class -> 0.0
        Boolean::class -> false
        else -> null
    }

