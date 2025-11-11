package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document tracking Weaviate schema configuration versions.
 * Used to detect schema changes and trigger automatic migration.
 *
 * When schema parameters change (distance metric, HNSW config, dimensions),
 * the application must:
 * 1. Drop existing Weaviate collections
 * 2. Delete all vector-dependent MongoDB collections
 * 3. Recreate Weaviate collections with new config
 * 4. Trigger re-indexing of all data sources
 */
@Document(collection = "weaviate_schema_metadata")
data class WeaviateSchemaMetadata(
    @Id
    val id: ObjectId = ObjectId(),
    // Schema version identifier
    val schemaVersion: String, // e.g., "2.0"
    // Vector index configuration
    val distance: String, // "cosine", "euclidean", "dot"
    val ef: Int, // HNSW runtime search quality
    val efConstruction: Int, // HNSW index build quality
    val maxConnections: Int, // HNSW graph density
    val dynamicEfMin: Int, // Dynamic ef tuning
    val dynamicEfMax: Int,
    val dynamicEfFactor: Int,
    val flatSearchCutoff: Int, // Brute-force threshold
    // Embedding dimensions
    val textDimension: Int, // SemanticText embedding dimension
    val codeDimension: Int, // SemanticCode embedding dimension
    // Migration tracking
    val migratedAt: Instant = Instant.now(),
    val previousVersion: String? = null, // Previous schema version (if migrated)
    val migrationReason: String? = null, // Why migration was triggered
)
