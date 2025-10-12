package com.jervis.common

import org.bson.types.ObjectId

/**
 * Global constants used across the application.
 */
object Constants {
    /**
     * Global client ID used when no specific client is assigned.
     * This should be used instead of null values for clientId fields.
     */
    val GLOBAL_ID: ObjectId = ObjectId("000000000000000000000000")
}
