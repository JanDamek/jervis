package com.jervis.service.http

import com.jervis.configuration.ConnectionCredentialsKey
import com.jervis.configuration.ConnectionKey
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse

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
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    get(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
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
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    post(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }

/**
 * Execute PUT request with Connection.
 */
suspend fun HttpClient.putWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    put(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }

/**
 * Execute DELETE request with Connection.
 */
suspend fun HttpClient.deleteWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    delete(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }

/**
 * Execute PATCH request with Connection.
 */
suspend fun HttpClient.patchWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    patch(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }

/**
 * Execute HEAD request with Connection.
 */
suspend fun HttpClient.headWithConnection(
    url: String,
    connection: Connection.HttpConnection,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    head(url) {
        attributes.put(ConnectionKey, connection)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connection.timeoutMs
        }
        block()
    }
