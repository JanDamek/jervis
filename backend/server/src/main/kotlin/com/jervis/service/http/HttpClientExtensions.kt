package com.jervis.service.http

import com.jervis.configuration.ConnectionCredentialsKey
import com.jervis.configuration.ConnectionDocumentKey
import com.jervis.entity.connection.ConnectionDocument
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
 * Execute GET request with ConnectionDocument.
 *
 * @param url Full URL (can include query parameters)
 * @param connectionDocument ConnectionDocument.HttpConnectionDocument with auth and rate limit config
 * @param credentials Decrypted HttpCredentials (obtained via ConnectionService)
 * @param block Additional request configuration
 */
suspend fun HttpClient.getWithConnection(
    url: String,
    connectionDocument: ConnectionDocument.HttpConnectionDocument,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    get(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

/**
 * Execute POST request with ConnectionDocument.
 *
 * @param url Full URL
 * @param connectionDocument ConnectionDocument.HttpConnectionDocument with auth and rate limit config
 * @param credentials Decrypted HttpCredentials (obtained via ConnectionService)
 * @param block Additional request configuration (use setBody for request body)
 */
suspend fun HttpClient.postWithConnection(
    url: String,
    connectionDocument: ConnectionDocument.HttpConnectionDocument,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    post(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

/**
 * Execute PUT request with ConnectionDocument.
 */
suspend fun HttpClient.putWithConnection(
    url: String,
    connectionDocument: ConnectionDocument.HttpConnectionDocument,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    put(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

/**
 * Execute DELETE request with ConnectionDocument.
 */
suspend fun HttpClient.deleteWithConnection(
    url: String,
    connectionDocument: ConnectionDocument.HttpConnectionDocument,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    delete(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

/**
 * Execute PATCH request with ConnectionDocument.
 */
suspend fun HttpClient.patchWithConnection(
    url: String,
    connectionDocument: ConnectionDocument.HttpConnectionDocument,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    patch(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

/**
 * Execute HEAD request with ConnectionDocument.
 */
suspend fun HttpClient.headWithConnection(
    url: String,
    connectionDocument: ConnectionDocument.HttpConnectionDocument,
    credentials: HttpCredentials? = null,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    head(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        credentials?.let { attributes.put(ConnectionCredentialsKey, it) }
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }
