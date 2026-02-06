package com.jervis.service.http

import com.jervis.configuration.ConnectionDocumentKey
import com.jervis.entity.connection.ConnectionDocument
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

suspend fun HttpClient.getWithConnection(
    url: String,
    connectionDocument: ConnectionDocument,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    get(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

suspend fun HttpClient.postWithConnection(
    url: String,
    connectionDocument: ConnectionDocument,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    post(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

suspend fun HttpClient.putWithConnection(
    url: String,
    connectionDocument: ConnectionDocument,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    put(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

suspend fun HttpClient.deleteWithConnection(
    url: String,
    connectionDocument: ConnectionDocument,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    delete(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

suspend fun HttpClient.patchWithConnection(
    url: String,
    connectionDocument: ConnectionDocument,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    patch(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }

suspend fun HttpClient.headWithConnection(
    url: String,
    connectionDocument: ConnectionDocument,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    head(url) {
        attributes.put(ConnectionDocumentKey, connectionDocument)
        timeout {
            requestTimeoutMillis = connectionDocument.timeoutMs
        }
        block()
    }
