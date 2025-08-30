package com.jervis.repository.vector.converter.rag

import com.jervis.domain.rag.RagDocument
import org.bson.types.ObjectId
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * Build a POJO Properties map from RagDocument using reflection
 */
internal fun RagDocument.convertRagDocumentToProperties(): Properties {
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
