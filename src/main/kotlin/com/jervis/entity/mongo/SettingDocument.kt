package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing application settings.
 */
@Document(collection = "settings")
data class SettingDocument(
    /**
     * Unique identifier for the setting.
     */
    @Id
    val id: ObjectId? = null,
    /**
     * Unique key for the setting.
     */
    val key: String,
    /**
     * Value of the setting.
     */
    val value: String? = null,
    /**
     * Type of the setting value.
     */
    val type: SettingType = SettingType.STRING,
    /**
     * Timestamp when the setting was created.
     */
    val createdAt: Instant = Instant.now(),
    /**
     * Timestamp when the setting was last updated.
     */
    val updatedAt: Instant = Instant.now(),
)

/**
 * Enumeration of supported setting value types.
 */
enum class SettingType {
    STRING,
    INTEGER,
    BOOLEAN,
    DOUBLE,
    DATE,
}
