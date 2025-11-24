package com.jervis.service.http

import com.jervis.configuration.ConnectionCredentialsKey
import com.jervis.configuration.ConnectionKey
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Extension functions for HttpClient with Connection support.
 *
 * These functions automatically:
 * - Apply rate limiting per domain
 * - Inject authorization headers from Connection
 * - Use timeout from Connection
 * - Log requests
 *
 * Usage:
 * ```kotlin
 * val response = httpClient.getWithConnection(
 *     url = "${connection.baseUrl}/rest/api/3/myself",
 *     connection = connection,
 *     credentials = decryptedCredentials
 * )
 * ```
 */

/**
 * Execute GET request with Connection.
 *
 * @param url Full URL (can include query parameters)
 * @param connection Connection.HttpConnection with auth and rate limit config
 * @param credentials Decrypted HttpCredentials (obtained via ConnectionService)
 * @param block Additional request configuration
 */
suspend fun HttpClient.getWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse {
    return get(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
}

/**
 * Execute POST request with Connection.
 *
 * @param url Full URL
 * @param connection Connection.HttpConnection with auth and rate limit config
 * @param credentials Decrypted HttpCredentials (obtained via ConnectionService)
 * @param block Additional request configuration (use setBody for request body)
 */
suspend fun HttpClient.postWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse {
    return post(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
}

/**
 * Execute PUT request with Connection.
 */
suspend fun HttpClient.putWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse {
    return put(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
}

/**
 * Execute DELETE request with Connection.
 */
suspend fun HttpClient.deleteWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse {
    return delete(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
}

/**
 * Execute PATCH request with Connection.
 */
suspend fun HttpClient.patchWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse {
    return patch(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
}

/**
 * Execute HEAD request with Connection.
 */
suspend fun HttpClient.headWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {}
): HttpResponse {
    return head(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
}
