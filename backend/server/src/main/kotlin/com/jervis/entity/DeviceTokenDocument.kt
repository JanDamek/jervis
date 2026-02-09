package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Stores FCM/APNs device tokens for push notifications.
 *
 * One token per device — upserted by deviceId to avoid duplicates.
 */
@Document(collection = "device_tokens")
@CompoundIndex(def = "{'clientId': 1, 'platform': 1}")
@CompoundIndex(def = "{'deviceId': 1}", unique = true)
data class DeviceTokenDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val clientId: String,
    val token: String,
    val platform: String,
    val deviceId: String,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now(),
)
