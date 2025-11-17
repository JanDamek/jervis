package com.jervis.api

/**
 * Security constants for client-server communication.
 *
 * This token acts as a simple port scanning protection mechanism.
 * Any request without this header is considered a potential attack and gets no response.
 */
object SecurityConstants {
    /**
     * Unique client identification token.
     * Must be included in X-Jervis-Client header for all HTTP/WebSocket requests.
     */
    const val CLIENT_TOKEN = "a7f3c9e2-4b8d-11ef-9a1c-0242ac120002"

    /**
     * Header name for client token
     */
    const val CLIENT_HEADER = "X-Jervis-Client"

    /**
     * Header name for platform identification (iOS, Android, Desktop)
     */
    const val PLATFORM_HEADER = "X-Jervis-Platform"

    /**
     * Header name for client's local IP address
     */
    const val CLIENT_IP_HEADER = "X-Jervis-Client-IP"

    /**
     * Platform identification values
     */
    const val PLATFORM_IOS = "iOS"
    const val PLATFORM_ANDROID = "Android"
    const val PLATFORM_DESKTOP = "Desktop"
}
