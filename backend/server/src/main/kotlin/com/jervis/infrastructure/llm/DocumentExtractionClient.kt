package com.jervis.infrastructure.llm

import com.google.protobuf.ByteString
import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.document_extraction.DocumentExtractionServiceGrpcKt
import com.jervis.contracts.document_extraction.ExtractRequest
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC client for the Python document-extraction microservice.
 *
 * Replaces the former REST client on `POST /extract-base64`. The base
 * URL is still expressed as "host[:port]" (legacy config shape) — port
 * 5501 is the fixed gRPC target on the document-extraction pod.
 */
class DocumentExtractionClient(baseUrl: String) {

    private val channel: ManagedChannel = run {
        val cleaned = baseUrl.trim().trimEnd('/')
        val hostPart = if ("://" in cleaned) cleaned.substringAfter("://") else cleaned
        val host = hostPart.substringBefore('/').substringBefore(':')
        NettyChannelBuilder.forAddress(host, 5501)
            .usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
    }

    private val stub = DocumentExtractionServiceGrpcKt
        .DocumentExtractionServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun extractText(content: String, mimeType: String = "text/html"): String {
        if (content.isBlank()) return content

        val filename = when {
            mimeType.contains("html") -> "content.html"
            mimeType.contains("xml") -> "content.xml"
            else -> "content.txt"
        }

        val resp = stub.extract(
            ExtractRequest.newBuilder()
                .setCtx(ctx())
                .setContent(ByteString.copyFrom(content.toByteArray(Charsets.UTF_8)))
                .setFilename(filename)
                .setMimeType(mimeType)
                .setMaxTier("NONE")
                .build(),
        )
        logger.info { "Document extraction: ${content.length} chars → ${resp.text.length} chars (method=${resp.method})" }
        return resp.text
    }

    suspend fun extractBytes(
        fileBytes: ByteArray,
        filename: String,
        mimeType: String,
        maxTier: String = "NONE",
    ): String {
        val resp = stub.extract(
            ExtractRequest.newBuilder()
                .setCtx(ctx())
                .setContent(ByteString.copyFrom(fileBytes))
                .setFilename(filename)
                .setMimeType(mimeType)
                .setMaxTier(maxTier)
                .build(),
        )
        logger.info { "Document extraction: $filename (${fileBytes.size} bytes) → ${resp.text.length} chars (method=${resp.method})" }
        return resp.text
    }

    fun shutdown() {
        runCatching { channel.shutdown().awaitTermination(5, TimeUnit.SECONDS) }
    }
}
