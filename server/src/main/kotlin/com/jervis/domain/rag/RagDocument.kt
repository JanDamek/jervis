package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a document with content and metadata.
 * This class is used for storing and retrieving documents from the vector database.
 */
data class RagDocument(
    val projectId: ObjectId,
    val clientId: ObjectId,
    val summary: String = "",
    val ragSourceType: RagSourceType,
    val language: String? = null,
    val path: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val parentClass: String? = null,
    val methodName: String? = null,
    val createdAt: Instant = Instant.now(),
    val gitCommitHash: String? = null,
    val archivedAt: Instant? = null,
    // Enhanced metadata fields for Joern-based indexing
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    /** Symbol name (method/class name from Joern) */
    val symbolName: String? = null,
    /** Joern CPG node ID for traceability */
    val joernNodeId: String? = null,
    val safetyTags: List<String> = listOf(), // Bezpečnostní značky
    val chunkId: Int? = null, // Index činka
    val chunkOf: Int? = null, // Celkový počet činků (pro map-reduce)
    val branch: String = "main", // Název větve (např. main, develop, release-vX)
)
