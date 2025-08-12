import com.jervis.domain.rag.RagDocument
import io.qdrant.client.grpc.JsonWithInt
import org.bson.types.ObjectId
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Convert RagDocument to Qdrant payload using reflection
 * Maps all properties of RagDocument to JSON values
 *
 * @param ragDocument The document to convert
 * @return The Qdrant payload
 */
internal fun RagDocument.convertRagDocumentToPayload(): Map<String, JsonWithInt.Value> {
    val payload = mutableMapOf<String, JsonWithInt.Value>()

    // Get all properties of RagDocument using reflection
    val kClass = this::class as KClass<RagDocument>

    for (property in kClass.memberProperties) {
        val value = property.get(this)
        val jsonValue = convertValueToJsonValue(value)
        if (jsonValue != null) {
            payload[property.name] = jsonValue
        }
    }

    return payload
}

/**
 * Convert payload back to RagDocument using reflection
 * Maps all available JSON values back to RagDocument constructor parameters
 *
 * @param payload The Qdrant payload
 * @return The reconstructed RagDocument
 */
internal fun Map<String, JsonWithInt.Value>.convertPayloadToRagDocument(): RagDocument {
    val kClass = RagDocument::class
    val constructor =
        kClass.primaryConstructor
            ?: throw IllegalStateException("RagDocument must have a primary constructor")

    val args = mutableMapOf<KParameter, Any?>()

    for (parameter in constructor.parameters) {
        val paramName = parameter.name ?: continue
        val jsonValue = this[paramName]

        if (jsonValue != null) {
            val convertedValue = convertJsonValueToValue(jsonValue, parameter.type.classifier as? KClass<*>)
            if (convertedValue != null) {
                args[parameter] = convertedValue
            }
        } else if (!parameter.isOptional && !parameter.type.isMarkedNullable) {
            // Provide default values for required parameters that are missing
            args[parameter] = getDefaultValueForType(parameter.type.classifier as? KClass<*>)
        }
    }

    return constructor.callBy(args)
}

/**
 * Convert Kotlin value to JsonWithInt.Value
 */
private fun convertValueToJsonValue(value: Any?): JsonWithInt.Value? =
    when (value) {
        null -> null
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

        is ObjectId ->
            JsonWithInt.Value
                .newBuilder()
                .setStringValue(value.toString())
                .build()

        is Enum<*> ->
            JsonWithInt.Value
                .newBuilder()
                .setStringValue(value.name)
                .build()

        else ->
            JsonWithInt.Value
                .newBuilder()
                .setStringValue(value.toString())
                .build()
    }

/**
 * Convert JsonWithInt.Value back to Kotlin value
 */
private fun convertJsonValueToValue(
    jsonValue: JsonWithInt.Value,
    targetClass: KClass<*>?,
): Any? =
    when (targetClass) {
        String::class ->
            when {
                jsonValue.hasStringValue() -> jsonValue.stringValue
                jsonValue.hasIntegerValue() -> jsonValue.integerValue.toString()
                jsonValue.hasDoubleValue() -> jsonValue.doubleValue.toString()
                jsonValue.hasBoolValue() -> jsonValue.boolValue.toString()
                else -> null
            }

        Int::class ->
            when {
                jsonValue.hasIntegerValue() -> jsonValue.integerValue.toInt()
                jsonValue.hasDoubleValue() -> jsonValue.doubleValue.toInt()
                jsonValue.hasStringValue() -> jsonValue.stringValue.toIntOrNull()
                else -> null
            }

        Long::class ->
            when {
                jsonValue.hasIntegerValue() -> jsonValue.integerValue
                jsonValue.hasDoubleValue() -> jsonValue.doubleValue.toLong()
                jsonValue.hasStringValue() -> jsonValue.stringValue.toLongOrNull()
                else -> null
            }

        Float::class ->
            when {
                jsonValue.hasDoubleValue() -> jsonValue.doubleValue.toFloat()
                jsonValue.hasIntegerValue() -> jsonValue.integerValue.toFloat()
                jsonValue.hasStringValue() -> jsonValue.stringValue.toFloatOrNull()
                else -> null
            }

        Double::class ->
            when {
                jsonValue.hasDoubleValue() -> jsonValue.doubleValue
                jsonValue.hasIntegerValue() -> jsonValue.integerValue.toDouble()
                jsonValue.hasStringValue() -> jsonValue.stringValue.toDoubleOrNull()
                else -> null
            }

        Boolean::class ->
            when {
                jsonValue.hasBoolValue() -> jsonValue.boolValue
                jsonValue.hasStringValue() -> jsonValue.stringValue.toBooleanStrictOrNull()
                else -> null
            }

        ObjectId::class ->
            when {
                jsonValue.hasStringValue() ->
                    try {
                        ObjectId(jsonValue.stringValue)
                    } catch (_: Exception) {
                        null
                    }

                else -> null
            }

        else -> {
            // Handle enums
            if (targetClass?.isSubclassOf(Enum::class) == true) {
                if (jsonValue.hasStringValue()) {
                    try {
                        // Get enum constants and find matching name
                        val enumConstants = targetClass.java.enumConstants
                        enumConstants?.find { (it as Enum<*>).name == jsonValue.stringValue }
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            } else {
                // Fallback to string representation
                when {
                    jsonValue.hasStringValue() -> jsonValue.stringValue
                    jsonValue.hasIntegerValue() -> jsonValue.integerValue
                    jsonValue.hasDoubleValue() -> jsonValue.doubleValue
                    jsonValue.hasBoolValue() -> jsonValue.boolValue
                    else -> null
                }
            }
        }
    }

/**
 * Get default value for a type when parameter is missing
 */
private fun getDefaultValueForType(targetClass: KClass<*>?): Any? =
    when (targetClass) {
        String::class -> ""
        Int::class -> 0
        Long::class -> 0L
        Float::class -> 0f
        Double::class -> 0.0
        Boolean::class -> false
        ObjectId::class -> ObjectId()
        else -> {
            if (targetClass?.isSubclassOf(Enum::class) == true) {
                // Return first enum constant as default
                targetClass.java.enumConstants?.firstOrNull()
            } else {
                null
            }
        }
    }
