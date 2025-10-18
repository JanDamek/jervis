package com.jervis.common

import org.bson.types.ObjectId

/**
 * Global constants used across the application.
 */
object Constants {
    /**
     * Global client ObjectId used across the server side as a fallback when no specific client is assigned.
     * It is generated to ensure it is always a valid ObjectId and does not depend on string parsing.
     */
    val GLOBAL_ID: ObjectId = ObjectId.get()
}
